package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The JVM's runtime as an Android app carries it, in its assets: `nja/runtime.zip` (each ABI's java.home under
 * `jdk/<abi>`, the façades' class path under `classpath/`, the JVM options) and `nja/runtime.id`, its hash, which
 * versions the copy the app extracts.
 */
@DisableCachingByDefault(because = "Zipping the runtime is cheaper than storing it in the cache")
abstract class NjaAndroidRuntimeTask : DefaultTask() {
    /** java.home (jdk-mobile's `lib`) by ABI. */
    @get:Input abstract val javaHomes: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaHomeFiles: ConfigurableFileCollection

    @get:Classpath abstract val classPath: ConfigurableFileCollection
    @get:Input abstract val jvmOptions: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val out = outputDir.get().asFile.resolve("nja")
        out.deleteRecursively()
        out.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(out.resolve("runtime.zip").outputStream().buffered()).use { zip ->
            fun add(name: String, file: File) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                digest.update(name.toByteArray())
                digest.update(file.readBytes())
            }
            for ((abi, home) in javaHomes.get().toSortedMap()) {
                val root = File(home)
                root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                    add("jdk/$abi/${it.relativeTo(root).invariantSeparatorsPath}", it)
                }
            }
            val names = HashSet<String>()
            for (jar in classPath.files.filter { it.isFile && it.name.endsWith(".jar") }.sortedBy { it.name }) {
                var name = jar.name
                var i = 1
                while (!names.add(name)) name = "${jar.nameWithoutExtension}-${i++}.jar"
                add("classpath/$name", jar)
            }
            val options = jvmOptions.get().joinToString("\n")
            zip.putNextEntry(ZipEntry("jvm.options"))
            zip.write(options.toByteArray())
            zip.closeEntry()
            digest.update(options.toByteArray())
        }
        out.resolve("runtime.id").writeText(digest.digest().joinToString("") { "%02x".format(it) }.take(16))
    }
}

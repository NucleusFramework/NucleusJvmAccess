package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

/**
 * The façades ([classPath], with NJA's server) compiled into a GraalVM native image for a graalvm-mobile kit's target
 * ([kit]): a relocatable object ([objectFile]) exporting NJA's entry points, which the app's binary links with the kit's
 * libraries. native-image is the kit's GraalVM CE release ([graalvmHome], a file holding its path). Libraries bring their
 * reachability metadata in their jars (`META-INF/native-image`); [options] are native-image's own.
 */
@CacheableTask
abstract class NjaNativeImageTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val graalvmHome: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val kit: DirectoryProperty
    @get:Classpath abstract val classPath: ConfigurableFileCollection
    @get:Input abstract val options: ListProperty<String>
    @get:OutputFile abstract val objectFile: RegularFileProperty

    @TaskAction
    fun build() {
        val home = File(graalvmHome.get().asFile.readText().trim())
        val kitDir = kit.get().asFile
        val properties = Properties().apply { File(kitDir, NjaGraalvmTask.KIT_PROPERTIES).reader().use { load(it) } }
        val target = properties.getProperty("target")
        val work = File(temporaryDir, "native-image").apply { deleteRecursively(); mkdirs() }
        val command = mutableListOf(
            File(home, "bin/native-image").path,
            // The kit's own classes (its platform, for Android x86_64) with the façades
            "-cp", (classPath.files + kitClassPath(properties, kitDir)).joinToString(File.pathSeparator),
            "-Dsvm.platform=${properties.getProperty("native-image.platform")}",
        )
        command += properties.getProperty("native-image.flags").split(' ').filter { it.isNotEmpty() }
        if (target.startsWith("android")) {
            if (!System.getProperty("os.name").lowercase().startsWith("linux")) {
                throw GradleException(
                    "NJA: a GraalVM native image for Android ($target) can only be built on Linux, not on " +
                        "${System.getProperty("os.name")}: native-image compiles its own host's Java classes, and Android needs " +
                        "Linux's. Build it on Linux (a CI runner, a Linux container), or set nucleusJvm.androidRuntime to " +
                        "NjaRuntimeType.JVM (or ART) for builds on this machine.",
                )
            }
            // native-image checks that its bionic C libraries' directory exists, though the app links them (from the kit)
            listOf("amd64", "aarch64").forEach { File(home, "lib/svm/clibraries/linux-$it/bionic").mkdirs() }
            // The NDK compiles native-image's C queries; their results are kept in a cache, required when cross-compiling
            command += listOf("-H:CCompilerPath=${File(kitDir, "bin/android-cc").path}", "-EANDROID_NDK_HOME", "-H:-UseCAPCache", "-H:+NewCAPCache", "-H:CAPCacheDir=${File(work, "cap").path}")
        }
        command += listOf(
            "-H:+UnlockExperimentalVMOptions", "-H:+SharedLibrary", "-H:+ExitAfterRelocatableImageWrite",
            "-H:TempDirectory=${File(work, "tmp").path}", "-o", File(work, "nja").path,
        )
        command += options.get()
        val process = ProcessBuilder(command).directory(work).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            logger.error(output)
            throw GradleException("NJA: native-image failed for $target")
        }
        logger.info(output)
        val built = File(work, "tmp").walkTopDown().firstOrNull { it.name == "nja.o" }
            ?: throw GradleException("NJA: native-image wrote no object for $target")
        built.copyTo(objectFile.get().asFile, overwrite = true)
    }

    private fun kitClassPath(properties: Properties, kitDir: File): List<File> =
        properties.getProperty("native-image.classpath").orEmpty().split(':').filter { it.isNotEmpty() }.map { File(kitDir, it) }
}

package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The façades ([classPath], with NJA's server) compiled into a GraalVM native image for the build host, as a shared
 * library ([libraryFile]: `libnja.dylib`, `libnja.so`, `nja.dll`) exporting NJA's entry points, which a Kotlin/Native
 * desktop binary loads at run time. native-image is [graalvmHome]'s (a file holding its path); on Windows it needs
 * Visual Studio's C toolchain. Libraries bring their reachability metadata in their jars (`META-INF/native-image`);
 * [options] are native-image's own.
 */
@CacheableTask
abstract class NjaNativeLibraryTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val graalvmHome: RegularFileProperty
    @get:Classpath abstract val classPath: ConfigurableFileCollection
    @get:Input abstract val options: ListProperty<String>
    @get:OutputFile abstract val libraryFile: RegularFileProperty

    @TaskAction
    fun build() {
        val home = File(graalvmHome.get().asFile.readText().trim())
        val library = libraryFile.get().asFile
        val work = File(temporaryDir, "native-image").apply { deleteRecursively(); mkdirs() }
        val command = mutableListOf(
            File(home, NjaGraalvmTask.nativeImageExecutable()).path,
            "-cp", classPath.files.joinToString(File.pathSeparator),
            "--shared", "-H:TempDirectory=${File(work, "tmp").path}",
            "-o", File(work, library.nameWithoutExtension).path,
        )
        command += options.get()
        val process = ProcessBuilder(command).directory(work).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            logger.error(output)
            throw GradleException("NJA: native-image failed for ${NjaGraalvmTask.hostPlatform()}")
        }
        logger.info(output)
        val built = File(work, library.name).takeIf { it.isFile }
            ?: throw GradleException("NJA: native-image wrote no ${library.name}")
        built.copyTo(library, overwrite = true)
    }

    companion object {
        /** The library's file name on the build host. */
        fun libraryName(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.startsWith("mac") -> "libnja.dylib"
                os.startsWith("windows") -> "nja.dll"
                else -> "libnja.so"
            }
        }
    }
}

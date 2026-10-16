package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Links `libnja.so` for each ABI: HotSpot and the JDK's natives (jdk-mobile's static archives) with the bridge to
 * ART (nja_android.c), into a shared library ART loads RTLD_LOCAL, bound to its own symbols.
 */
abstract class NjaAndroidLinkTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    /** jdk-mobile's target directory (`static/`, `include/`) by ABI. */
    @get:Input abstract val runtimes: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bridgeSource: RegularFileProperty

    @get:Input abstract val ndkDirectory: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun link() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val ndk = File(ndkDirectory.get())
        val prebuilt = ndk.resolve("toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("NJA: no LLVM toolchain in the NDK at $ndk")
        val bin = prebuilt.resolve("bin")
        for ((abi, runtime) in runtimes.get()) {
            val triple = when (abi) {
                "arm64-v8a" -> "aarch64-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> throw IllegalStateException("NJA: unsupported ABI $abi")
            }
            val root = File(runtime)
            val static = root.resolve("static")
            val include = root.resolve("include")
            val target = out.resolve("$abi/libnja.so")
            target.parentFile.mkdirs()
            val archives = listOf("libjvm.a", "libjava.a", "libnio.a", "libnet.a", "libzip.a", "libjimage.a", "libverify.a",
                "libextnet.a", "libsyslookup.a", "libmanagement.a")
                .map { static.resolve(it) }.filter { it.isFile }
            exec.exec {
                commandLine(
                    listOf(
                        bin.resolve("$triple$API-clang++").path, "-shared", "-fPIC", "-O2", "-Wall",
                        "-x", "c", "-std=c11", "-I$include", "-I$include/android", bridgeSource.get().asFile.path, "-x", "none",
                        "-o", target.path, "-Wl,--whole-archive",
                    ) + archives.map { it.path } + listOf(
                        "-Wl,--no-whole-archive", "-Wl,-Bsymbolic", "-Wl,-z,max-page-size=16384", "-Wl,-z,noexecstack",
                        "-Wl,--no-undefined", "-static-libstdc++", "-lz", "-ldl", "-llog", "-lm",
                    ),
                )
            }
            exec.exec { commandLine(bin.resolve("llvm-strip").path, "--strip-debug", target.path) }
        }
    }

    private companion object {
        /** Android 10: the first with the native ELF TLS HotSpot's aarch64 code needs (as jdk-mobile). */
        const val API = 29
    }
}

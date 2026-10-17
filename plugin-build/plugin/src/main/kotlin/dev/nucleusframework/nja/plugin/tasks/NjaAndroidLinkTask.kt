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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Links `libnja.so` for each ABI: HotSpot and the JDK's natives (jdk-mobile's static archives) with the bridge to
 * ART (nja_android.c), into a shared library ART loads RTLD_LOCAL, bound to its own symbols. With [nativeImage], the
 * façades' native image ([nativeImages], by ABI) with its graalvm-mobile kit's libraries ([kits], by ABI) and the bridge
 * for a native image ([nativeImageBridgeSource]).
 */
@DisableCachingByDefault(because = "Links with the local NDK")
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

    @get:Input abstract val nativeImage: Property<Boolean>

    /** The native image's object (from `njaNativeImage<Target>`) by ABI. */
    @get:Input abstract val nativeImages: MapProperty<String, String>

    /** graalvm-mobile's kit (`lib/`) by ABI. */
    @get:Input abstract val kits: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeImageFiles: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeImageBridgeSource: RegularFileProperty

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
        if (nativeImage.get()) {
            for ((abi, image) in nativeImages.get()) linkNativeImage(bin, abi, File(image), File(kits.get().getValue(abi)), out)
            return
        }
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

    private fun linkNativeImage(bin: File, abi: String, image: File, kit: File, out: File) {
        val target = out.resolve("$abi/libnja.so")
        target.parentFile.mkdirs()
        val archives = NATIVE_IMAGE_LIBRARIES.map { kit.resolve("lib/lib$it.a") }.filter { it.isFile }
        exec.exec {
            commandLine(
                listOf(
                    bin.resolve("${triple(abi)}$API-clang++").path, "-shared", "-fPIC", "-O2", "-Wall",
                    "-x", "c", "-std=c11", nativeImageBridgeSource.get().asFile.path, "-x", "none", image.path, "-o", target.path,
                ) + archives.map { it.path } + listOf(
                    "-Wl,-Bsymbolic", "-Wl,-z,max-page-size=16384", "-Wl,-z,noexecstack", "-Wl,--no-undefined",
                    "-static-libstdc++", "-lz", "-ldl", "-llog", "-lm",
                ),
            )
        }
        exec.exec { commandLine(bin.resolve("llvm-strip").path, "--strip-debug", target.path) }
    }

    private fun triple(abi: String) = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "x86_64" -> "x86_64-linux-android"
        else -> throw IllegalStateException("NJA: unsupported ABI $abi")
    }

    private companion object {
        /** A native image's libraries, from graalvm-mobile's kit: Substrate VM's, the JDK's, the glibc compatibility. */
        val NATIVE_IMAGE_LIBRARIES = listOf("jvm", "libchelper", "svm_container", "java", "nio", "net", "zip", "extnet", "jimage", "verify",
            "management_ext", "management", "prefs", "glibc_compat")

        /** Android 10: the first with the native ELF TLS HotSpot's aarch64 code needs (as jdk-mobile). */
        const val API = 29
    }
}

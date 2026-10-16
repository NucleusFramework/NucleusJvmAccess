package dev.nucleusframework.nja.plugin

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasDeviceTests
import dev.nucleusframework.nja.plugin.tasks.NjaAndroidLinkTask
import dev.nucleusframework.nja.plugin.tasks.NjaAndroidRuntimeTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import java.util.Properties

abstract class NjaExtension(private val project: Project) {
    /**
     * jdk-mobile's runtimes, one directory per target (`android-aarch64`, `android-x64`, `ios-simulator-aarch64`,
     * `ios-device-aarch64`), each with `static/` (the VM and the JDK's natives), `include/` and `lib/` (java.home).
     */
    abstract val jdkMobile: DirectoryProperty

    /** Options of the JVM (`-Xmx256m`...). */
    abstract val jvmOptions: ListProperty<String>

    /** The Android ABIs the app ships the JVM for. */
    abstract val androidAbis: SetProperty<String>

    /** A façade (a project applying `dev.nucleusframework.nja.facade`) whose API this module uses. */
    fun facade(notation: Any) {
        project.dependencies.add(NjaPlugin.FACADES, notation)
    }
}

/**
 * Applied to a Kotlin Multiplatform module (Android and/or iOS): its common code gets the façades' proxies, and its
 * binaries the JVM: on Android HotSpot, in the app's process next to ART; on iOS Zero, linked into the app.
 */
class NjaPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("nucleusJvm", NjaExtension::class.java, project)
        extension.androidAbis.convention(setOf("arm64-v8a"))
        extension.jvmOptions.convention(emptyList())

        val facades = project.configurations.dependencyScope(FACADES).get()
        val clientSources = project.configurations.resolvable("njaClientSources") {
            extendsFrom(facades)
            attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, NjaFacadePlugin.NJA_CLIENT_SOURCES_USAGE))
        }
        val classPath = project.configurations.resolvable("njaClasspath") {
            extendsFrom(facades)
            jvmRuntimeAttributes(project)
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(clientSources.map { it.incoming.files })
            project.dependencies.add("commonMainApi", "dev.nucleusframework.nja:nja-client:$NJA_VERSION")
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
                if (konanTarget.family == Family.IOS) configureIos(project, extension, classPath.get(), this)
            }
        }
        listOf("com.android.kotlin.multiplatform.library", "com.android.application", "com.android.library").forEach { id ->
            project.pluginManager.withPlugin(id) { configureAndroid(project, extension, classPath.get()) }
        }
    }

    private fun Configuration.jvmRuntimeAttributes(project: Project) {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, project.objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }

    private fun configureAndroid(project: Project, extension: NjaExtension, classPath: Configuration) {
        val abiDirs = mapOf("arm64-v8a" to "android-aarch64", "x86_64" to "android-x64")
        val runtimes = extension.androidAbis.zip(extension.jdkMobile) { abis, root ->
            abis.associateWith { abi -> root.asFile.resolve(abiDirs[abi] ?: error("NJA: unsupported ABI $abi")).path }
        }
        val runtime = project.tasks.register("njaAndroidRuntime", NjaAndroidRuntimeTask::class.java) {
            javaHomes.set(runtimes.map { m -> m.mapValues { File(it.value, "lib").path } })
            javaHomeFiles.from(runtimes.map { m -> m.values.map { File(it, "lib") } })
            this.classPath.from(classPath)
            jvmOptions.set(extension.jvmOptions)
            outputDir.set(project.layout.buildDirectory.dir("generated/nja/android/assets"))
        }
        val bridge = project.tasks.register("njaAndroidBridgeSource") {
            val out = project.layout.buildDirectory.file("generated/nja/android/nja_android.c")
            outputs.file(out)
            doLast {
                val file = out.get().asFile
                file.parentFile.mkdirs()
                file.writeBytes(NjaPlugin::class.java.getResourceAsStream("/nja_android.c")!!.use { it.readBytes() })
            }
        }
        val link = project.tasks.register("njaAndroidLink", NjaAndroidLinkTask::class.java) {
            this.runtimes.set(runtimes)
            runtimeFiles.from(runtimes.map { m -> m.values.flatMap { listOf(File(it, "static"), File(it, "include")) } })
            bridgeSource.fileProvider(bridge.map { it.outputs.files.singleFile })
            ndkDirectory.set(project.provider { ndkDirectory(project) })
            outputDir.set(project.layout.buildDirectory.dir("generated/nja/android/jniLibs"))
        }
        // A KMP library packages assets only with its Android resources on
        project.extensions.findByType(KotlinMultiplatformExtension::class.java)?.targets?.configureEach {
            (this as? KotlinMultiplatformAndroidLibraryExtension)?.androidResources?.enable = true
        }
        project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(runtime, NjaAndroidRuntimeTask::outputDir)
            variant.sources.jniLibs?.addGeneratedSourceDirectory(link, NjaAndroidLinkTask::outputDir)
            (variant as? HasDeviceTests)?.deviceTests?.values?.forEach { test ->
                test.sources.assets?.addGeneratedSourceDirectory(runtime, NjaAndroidRuntimeTask::outputDir)
            }
        }
    }

    private fun ndkDirectory(project: Project): String {
        System.getenv("ANDROID_NDK_HOME")?.let { return it }
        val local = Properties().apply {
            project.rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        return File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.path
            ?: throw IllegalStateException("NJA: no NDK found (set ANDROID_NDK_HOME)")
    }

    private fun configureIos(project: Project, extension: NjaExtension, classPath: Configuration, target: KotlinNativeTarget) {
        val dirName = when (target.konanTarget) {
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-simulator-aarch64"
            KonanTarget.IOS_ARM64 -> "ios-device-aarch64"
            else -> return
        }
        val runtime = extension.jdkMobile.dir(dirName)
        val name = target.name.replaceFirstChar { it.uppercaseChar() }
        // What the app's bundle carries: java.home (the VM sets it to <executable dir>/lib), the class path and options
        val bundle = project.tasks.register("njaIosBundle$name", Copy::class.java) {
            into(project.layout.buildDirectory.dir("nja/ios/${target.name}"))
            // jdk-mobile's files are read-only: copies must stay writable to be refreshed
            filePermissions { user { read = true; write = true } }
            from(runtime.map { it.dir("lib") }) { into("lib") }
            from(classPath) { into("nja/classpath") }
            val options = extension.jvmOptions
            inputs.property("jvmOptions", options)
            doLast {
                destinationDir.resolve("nja/jvm.options").writeText(options.get().joinToString("\n"))
            }
        }
        target.binaries.configureEach {
            val binary = this
            // Read when the link task is configured: the jdk-mobile directory is set by the module's build script by then
            binary.linkTaskProvider.configure {
                val static = runtime.get().asFile.resolve("static")
                binary.linkerOpts += listOf("jvm", "java", "nio", "net", "zip", "jimage", "verify", "extnet", "fallbackLinker", "syslookup")
                    .flatMap { listOf("-force_load", static.resolve("lib$it.a").path) } +
                    listOf(static.resolve("libffi.a").path, "-export_dynamic", "-lz", "-lc++", "-framework", "Foundation",
                        "-framework", "CoreFoundation", "-framework", "Security", "-framework", "SystemConfiguration")
            }
            if (binary is Executable || binary is TestExecutable) {
                val install = project.tasks.register("njaInstall${binary.name.replaceFirstChar { it.uppercaseChar() }}$name", Copy::class.java) {
                    from(bundle)
                    into(binary.outputDirectory)
                    filePermissions { user { read = true; write = true } }
                }
                binary.linkTaskProvider.configure { finalizedBy(install) }
                if (binary is TestExecutable) {
                    project.tasks.matching { it.name == "${target.name}Test" }.configureEach { dependsOn(install) }
                }
            }
        }
    }

    companion object {
        const val FACADES = "njaFacades"
    }
}

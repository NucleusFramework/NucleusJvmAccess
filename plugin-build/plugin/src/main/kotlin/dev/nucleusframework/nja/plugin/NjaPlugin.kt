package dev.nucleusframework.nja.plugin

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasDeviceTests
import dev.nucleusframework.nja.plugin.tasks.NjaAndroidLinkTask
import dev.nucleusframework.nja.plugin.tasks.NjaAndroidRuntimeTask
import dev.nucleusframework.nja.plugin.tasks.NjaGraalvmTask
import dev.nucleusframework.nja.plugin.tasks.NjaJdkMobileTask
import dev.nucleusframework.nja.plugin.tasks.NjaNativeImageTask
import dev.nucleusframework.nja.plugin.tasks.NjaNativeLibraryTask
import dev.nucleusframework.nja.plugin.tasks.PACKAGES_FILE
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.konan.target.Architecture
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import java.util.Properties

/** What runs the façades on a platform of the app (a JVM target runs them in its own JVM, whatever this says). */
enum class NjaRuntimeType {
    /**
     * The façades compiled ahead of time into a GraalVM native image (GraalVM CE), the default: on iOS and Android
     * linked into the app (with graalvm-mobile's libraries), on macOS, Linux and Windows a shared library next to the
     * executable. Much faster than Zero on iOS, lighter than HotSpot on Android, no JVM to ship on the desktop. An image
     * builds on its own platform's host (Android's on Linux); no FFM API on Android. Libraries bring their reachability
     * metadata (`META-INF/native-image`).
     */
    GRAALVM,

    /**
     * A JVM in the app's process: jdk-mobile's OpenJDK on mobile (HotSpot on Android, Zero, an interpreter, on iOS), a
     * JDK's HotSpot on macOS, Linux and Windows (the app's `nja/runtime`, or `JAVA_HOME`'s).
     */
    JVM,

    /**
     * Android only: the façades on ART itself, dexed with the app, for libraries ART runs as they are (whose module
     * targets iOS through NJA): no runtime in the app, nothing below Android 10 required.
     */
    ART,
}

abstract class NjaExtension(private val project: Project) {
    /**
     * What runs the façades on every platform, unless [iosRuntime], [androidRuntime] or [desktopRuntime] say
     * otherwise: GRAALVM by default. ART applies to Android only (the others then run GRAALVM).
     */
    abstract val runtime: Property<NjaRuntimeType>

    /** What runs the façades on iOS ([runtime] by default). */
    abstract val iosRuntime: Property<NjaRuntimeType>

    /**
     * What runs the façades on Android: [runtime] by default, except on a build host other than Linux, which can't
     * build an Android native image: the JVM there, unless set.
     */
    abstract val androidRuntime: Property<NjaRuntimeType>

    /** What runs the façades in a Kotlin/Native macOS, Linux or Windows binary ([runtime] by default). */
    abstract val desktopRuntime: Property<NjaRuntimeType>

    /**
     * The GraalVM CE release (graalvm/graalvm-ce-builds' tag, e.g. `graal-25.5.5.0.3`) building the desktop's native images:
     * `latest` by default. The mobile ones use the release their graalvm-mobile kit is for.
     */
    abstract val graalvmRelease: Property<String>

    /**
     * The graalvm-mobile release a native image's libraries come from: `latest` (the default) or a tag
     * (`graalvm-mobile-25.4.4.1.1b1.1`), downloaded from [graalvmMobileRepository]'s releases. native-image is the
     * GraalVM CE release it names, downloaded for the build host.
     */
    abstract val graalvmMobileRelease: Property<String>

    /** The GitHub repository of graalvm-mobile's releases. */
    abstract val graalvmMobileRepository: Property<String>

    /** native-image's own options (with [jvmOptions]' `-Xmx` as the image's maximum heap, `-D` as is). */
    abstract val nativeImageOptions: ListProperty<String>

    /**
     * The jdk-mobile release the runtimes come from: `latest` (the default) or a tag (`jdk-mobile-27b35.3`), downloaded
     * from [jdkMobileRepository]'s releases, checked against their `.sha256` and kept in Gradle's cache.
     */
    abstract val jdkMobileRelease: Property<String>

    /** The GitHub repository of jdk-mobile's releases. */
    abstract val jdkMobileRepository: Property<String>

    /**
     * jdk-mobile's runtimes, one directory per target (`android-aarch64`, `android-x64`, `ios-simulator-aarch64`,
     * `ios-device-aarch64`), each with `static/` (the VM and the JDK's natives), `include/` and `lib/` (java.home). By
     * default the ones of [jdkMobileRelease], downloaded; set, a runtime of one's own, as it is.
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
 * Applied to a Kotlin Multiplatform module (Android, iOS, macOS, Linux, Windows, the JVM): its common code gets the
 * façades' proxies, and its binaries the runtime running them (see [NjaRuntimeType]): a GraalVM native image by default,
 * linked into the app on mobile, next to the executable on the desktop; or a JVM in the app's process. On a JVM target
 * the app's own JVM, the façades on its class path.
 */
class NjaPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("nucleusJvm", NjaExtension::class.java, project)
        extension.androidAbis.convention(setOf("arm64-v8a"))
        extension.jvmOptions.convention(emptyList())
        extension.jdkMobileRelease.convention(NjaJdkMobileTask.LATEST)
        extension.jdkMobileRepository.convention("NucleusFramework/openjdk-mobile")
        extension.runtime.convention(NjaRuntimeType.GRAALVM)
        // ART is Android's: the others run a native image then
        val notArt = extension.runtime.map { if (it == NjaRuntimeType.ART) NjaRuntimeType.GRAALVM else it }
        extension.iosRuntime.convention(notArt)
        extension.desktopRuntime.convention(notArt)
        // An Android native image builds on Linux only: elsewhere, Android runs the JVM unless told otherwise
        extension.androidRuntime.convention(
            extension.runtime.map { if (it == NjaRuntimeType.GRAALVM && !isLinuxHost()) NjaRuntimeType.JVM else it },
        )
        extension.graalvmRelease.convention(NjaJdkMobileTask.LATEST)
        extension.graalvmMobileRelease.convention(NjaJdkMobileTask.LATEST)
        extension.graalvmMobileRepository.convention("NucleusFramework/graalvm-mobile")
        extension.nativeImageOptions.convention(emptyList())
        val staged = project.layout.buildDirectory.dir("nja/jdkMobile")
        extension.jdkMobile.convention(staged)
        // One task per runtime, which the tasks reading it depend on: they download only when jdkMobile is the default
        val stagedPath = staged.map { it.asFile.path }
        val jdkMobileTasks = JDK_MOBILE_TARGETS.associateWith { target ->
            project.tasks.register("njaJdkMobile${target.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}", NjaJdkMobileTask::class.java) {
                description = "Downloads and extracts jdk-mobile's runtime for $target."
                repository.set(extension.jdkMobileRepository)
                release.set(extension.jdkMobileRelease)
                this.target.set(target)
                cacheDir.set(project.gradle.gradleUserHomeDir.resolve("caches/nja/jdk-mobile"))
                offline.set(project.gradle.startParameter.isOffline)
                outputDir.set(staged.map { it.dir(target) })
                val chosen = extension.jdkMobile.map { it.asFile.path }
                onlyIf("jdkMobile is the default (else a runtime of one's own)") { chosen.get() == stagedPath.get() }
            }
        }

        val facades = project.configurations.dependencyScope(FACADES).get()
        val nativeImageClassPath = project.configurations.resolvable("njaNativeImageClasspath") {
            extendsFrom(facades)
            jvmRuntimeAttributes(project)
        }
        // A native image per target, built only when a binary needs it
        val nativeImages = JDK_MOBILE_TARGETS.associateWith { target ->
            registerNativeImage(project, extension, target, nativeImageClassPath)
        }
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
            val nativeLibrary = lazy { registerNativeLibrary(project, extension, nativeImageClassPath) }
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
                when (konanTarget.family) {
                    Family.IOS -> configureIos(project, extension, classPath.get(), this, jdkMobileTasks, nativeImages)
                    Family.OSX, Family.LINUX, Family.MINGW -> configureDesktop(project, extension, classPath.get(), this, nativeLibrary)
                    else -> Unit
                }
            }
            kotlin.targets.withType(KotlinJvmTarget::class.java).configureEach { configureJvm(project, this, facades, clientSources.get()) }
        }
        listOf("com.android.kotlin.multiplatform.library", "com.android.application", "com.android.library").forEach { id ->
            project.pluginManager.withPlugin(id) { configureAndroid(project, extension, classPath.get(), jdkMobileTasks, nativeImages) }
        }
    }

    /** A target's native image: graalvm-mobile's kit, GraalVM CE for the host, native-image. */
    private fun registerNativeImage(
        project: Project,
        extension: NjaExtension,
        target: String,
        classPath: org.gradle.api.provider.Provider<out Configuration>,
    ): TaskProvider<NjaNativeImageTask> {
        val suffix = target.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val cache = project.gradle.gradleUserHomeDir.resolve("caches/nja")
        val kit = project.tasks.register("njaGraalvmMobile$suffix", NjaJdkMobileTask::class.java) {
            description = "Downloads and extracts graalvm-mobile's libraries for $target."
            product.set(NjaJdkMobileTask.GRAALVM_MOBILE)
            repository.set(extension.graalvmMobileRepository)
            release.set(extension.graalvmMobileRelease)
            this.target.set(target)
            cacheDir.set(cache.resolve("graalvm-mobile"))
            offline.set(project.gradle.startParameter.isOffline)
            outputDir.set(project.layout.buildDirectory.dir("nja/graalvmMobile/$target"))
        }
        val graalvm = project.tasks.register("njaGraalvm$suffix", NjaGraalvmTask::class.java) {
            description = "Downloads GraalVM CE for this host, the release graalvm-mobile's $target libraries are for."
            this.kit.set(kit.flatMap { it.outputDir })
            cacheDir.set(cache.resolve("graalvm"))
            offline.set(project.gradle.startParameter.isOffline)
            homeFile.set(project.layout.buildDirectory.file("nja/graalvm/$target.home"))
        }
        return project.tasks.register("njaNativeImage$suffix", NjaNativeImageTask::class.java) {
            description = "Compiles the façades into a GraalVM native image for $target."
            graalvmHome.set(graalvm.flatMap { it.homeFile })
            this.kit.set(kit.flatMap { it.outputDir })
            this.classPath.from(classPath)
            options.set(extension.jvmOptions.zip(extension.nativeImageOptions) { jvm, own -> nativeImageOptions(jvm) + own })
            objectFile.set(project.layout.buildDirectory.file("nja/nativeImage/$target/nja.o"))
        }
    }

    /** The desktop's native image: GraalVM CE for the host ([NjaExtension.graalvmRelease]), native-image's shared library. */
    private fun registerNativeLibrary(
        project: Project,
        extension: NjaExtension,
        classPath: org.gradle.api.provider.Provider<out Configuration>,
    ): TaskProvider<NjaNativeLibraryTask> {
        val graalvm = project.tasks.register("njaGraalvmHost", NjaGraalvmTask::class.java) {
            description = "Downloads GraalVM CE for this host, for the desktop's native image."
            release.set(extension.graalvmRelease)
            cacheDir.set(project.gradle.gradleUserHomeDir.resolve("caches/nja/graalvm"))
            offline.set(project.gradle.startParameter.isOffline)
            homeFile.set(project.layout.buildDirectory.file("nja/graalvm/host.home"))
        }
        return project.tasks.register("njaNativeLibrary", NjaNativeLibraryTask::class.java) {
            description = "Compiles the façades into a GraalVM native image for this host, a shared library."
            graalvmHome.set(graalvm.flatMap { it.homeFile })
            this.classPath.from(classPath)
            options.set(extension.jvmOptions.zip(extension.nativeImageOptions) { jvm, own -> nativeImageOptions(jvm) + own })
            libraryFile.set(project.layout.buildDirectory.file("nja/nativeLibrary/${NjaNativeLibraryTask.libraryName()}"))
        }
    }

    // The JVM's options a native image has: its maximum heap and system properties
    private fun nativeImageOptions(jvmOptions: List<String>): List<String> = jvmOptions.mapNotNull {
        when {
            it.startsWith("-Xmx") -> "-R:MaxHeapSize=${it.removePrefix("-Xmx")}"
            it.startsWith("-D") -> it
            else -> null
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

    private fun configureAndroid(
        project: Project,
        extension: NjaExtension,
        classPath: Configuration,
        jdkMobileTasks: Map<String, TaskProvider<NjaJdkMobileTask>>,
        nativeImages: Map<String, TaskProvider<NjaNativeImageTask>>,
    ) {
        val abiDirs = mapOf("arm64-v8a" to "android-aarch64", "x86_64" to "android-x64")
        val graal = extension.androidRuntime.map { it == NjaRuntimeType.GRAALVM }
        val art = extension.androidRuntime.map { it == NjaRuntimeType.ART }
        // The runtimes of the ABIs shipped: jdk-mobile's, the native images, or none on ART
        val staging = extension.androidAbis.zip(extension.androidRuntime) { abis, type ->
            abis.mapNotNull { abi ->
                val target = abiDirs[abi] ?: error("NJA: unsupported ABI $abi")
                when (type) {
                    NjaRuntimeType.GRAALVM -> nativeImages.getValue(target)
                    NjaRuntimeType.ART -> null
                    else -> jdkMobileTasks.getValue(target)
                }
            }
        }
        // On ART, the façades (and NJA's server) are the app's own dependencies
        val facadeDependencies = art.map { if (it) project.configurations.getByName(FACADES).dependencies.toList() else emptyList() }
        project.configurations.matching { it.name == "androidMainImplementation" || it.name == "implementation" }.configureEach {
            dependencies.addAllLater(facadeDependencies)
        }
        val imageObjects = extension.androidAbis.map { abis ->
            abis.associateWith { abi -> project.layout.buildDirectory.file("nja/nativeImage/${abiDirs.getValue(abi)}/nja.o").get().asFile.path }
        }
        val kitDirs = extension.androidAbis.map { abis ->
            abis.associateWith { abi -> project.layout.buildDirectory.dir("nja/graalvmMobile/${abiDirs.getValue(abi)}").get().asFile.path }
        }
        val runtimes = extension.androidAbis.zip(extension.jdkMobile) { abis, root ->
            abis.associateWith { abi -> root.asFile.resolve(abiDirs[abi] ?: error("NJA: unsupported ABI $abi")).path }
        }
        // jdk-mobile's runtimes when the JVM runs the façades
        val jvmRuntimes = runtimes.zip(extension.androidRuntime) { m, type -> if (type == NjaRuntimeType.JVM) m else emptyMap() }
        val runtime = project.tasks.register("njaAndroidRuntime", NjaAndroidRuntimeTask::class.java) {
            dependsOn(staging)
            javaHomes.set(jvmRuntimes.map { m -> m.mapValues { File(it.value, "lib").path } })
            javaHomeFiles.from(jvmRuntimes.map { m -> m.values.map { File(it, "lib") } })
            this.classPath.from(classPath)
            jvmOptions.set(extension.jvmOptions)
            nativeImage.set(graal)
            this.art.set(art)
            outputDir.set(project.layout.buildDirectory.dir("generated/nja/android/assets"))
        }
        val bridgeDir = project.layout.buildDirectory.dir("generated/nja/android/bridge")
        val bridge = project.tasks.register("njaAndroidBridgeSource") {
            outputs.dir(bridgeDir)
            doLast {
                for (name in listOf("nja_android.c", "nja_android_graal.c")) {
                    val file = bridgeDir.get().file(name).asFile
                    file.parentFile.mkdirs()
                    file.writeBytes(NjaPlugin::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() })
                }
            }
        }
        val link = project.tasks.register("njaAndroidLink", NjaAndroidLinkTask::class.java) {
            dependsOn(staging)
            this.runtimes.set(jvmRuntimes)
            runtimeFiles.from(jvmRuntimes.map { m -> m.values.flatMap { listOf(File(it, "static"), File(it, "include")) } })
            bridgeSource.fileProvider(bridge.map { File(it.outputs.files.singleFile, "nja_android.c") })
            nativeImage.set(graal)
            this.nativeImages.set(imageObjects.zip(graal) { m, isGraal -> if (isGraal) m else emptyMap() })
            kits.set(kitDirs.zip(graal) { m, isGraal -> if (isGraal) m else emptyMap() })
            nativeImageFiles.from(imageObjects.zip(graal) { m, isGraal -> if (isGraal) m.values.map(::File) else emptyList() })
            nativeImageBridgeSource.fileProvider(bridge.map { File(it.outputs.files.singleFile, "nja_android_graal.c") })
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

    /**
     * A JVM target runs the façades in its own JVM: they go on its runtime class path only (its code sees the proxies,
     * not the façades), so the proxies need a package of their own, checked before it compiles.
     */
    private fun configureJvm(project: Project, target: KotlinJvmTarget, facades: Configuration, clientSources: Configuration) {
        val main = target.compilations.getByName("main")
        project.configurations.getByName(main.defaultSourceSet.runtimeOnlyConfigurationName).extendsFrom(facades)
        // The client sources' directories (built by the façades' generation tasks)
        val sourceDirs = project.files(clientSources)
        val check = project.tasks.register("checkNja${target.name.replaceFirstChar(Char::uppercase)}ClientPackages") {
            description = "Checks that the façades' proxies have packages of their own, apart from the façades'."
            inputs.files(sourceDirs)
            doLast {
                for (file in sourceDirs.files.map { File(it, PACKAGES_FILE) }.filter { it.isFile }) {
                    val packages = Properties().apply { file.reader().use { load(it) } }
                    val client = packages.getProperty("client")
                    val facade = packages.getProperty("facade").orEmpty().split(',').filter { it.isNotEmpty() }
                    check(client !in facade) {
                        "NJA: the proxies of a façade used on the JVM need a package of their own (both are on its class path): " +
                            "set njaFacade.clientPackage, not $client"
                    }
                }
            }
        }
        project.tasks.named(main.compileKotlinTaskName) { dependsOn(check) }
    }

    private fun configureIos(
        project: Project,
        extension: NjaExtension,
        classPath: Configuration,
        target: KotlinNativeTarget,
        jdkMobileTasks: Map<String, TaskProvider<NjaJdkMobileTask>>,
        nativeImages: Map<String, TaskProvider<NjaNativeImageTask>>,
    ) {
        val dirName = when (target.konanTarget) {
            KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-simulator-aarch64"
            KonanTarget.IOS_ARM64 -> "ios-device-aarch64"
            else -> return
        }
        val runtime = extension.jdkMobile.dir(dirName)
        val name = target.name.replaceFirstChar { it.uppercaseChar() }
        // What the app's bundle carries: java.home (the VM sets it to <executable dir>/lib), the class path and options
        val staging = jdkMobileTasks.getValue(dirName)
        val nativeImage = nativeImages.getValue(dirName)
        val graal = extension.iosRuntime.map {
            check(it != NjaRuntimeType.ART) { "NJA: ART is Android's: iosRuntime is JVM or GRAALVM" }
            it == NjaRuntimeType.GRAALVM
        }
        val bundle = project.tasks.register("njaIosBundle$name", Copy::class.java) {
            // A native image needs nothing next to the app's binary
            onlyIf("the runtime is the JVM") { !graal.get() }
            dependsOn(graal.map { if (it) emptyList() else listOf(staging) })
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
                if (graal.get()) {
                    dependsOn(nativeImage)
                    val lib = project.layout.buildDirectory.dir("nja/graalvmMobile/$dirName/lib").get().asFile
                    // The image's entry points are looked up at run time (dlsym): exported
                    binary.linkerOpts += listOf(nativeImage.get().objectFile.get().asFile.path) +
                        IOS_NATIVE_IMAGE_LIBRARIES.map { lib.resolve("lib$it.a").path } +
                        listOf("-export_dynamic", "-lz", "-lc++", "-framework", "Foundation", "-framework", "CoreFoundation")
                    return@configure
                }
                dependsOn(staging)
                val static = runtime.get().asFile.resolve("static")
                binary.linkerOpts += listOf("jvm", "java", "nio", "net", "zip", "jimage", "verify", "extnet", "fallbackLinker", "syslookup")
                    .flatMap { listOf("-force_load", static.resolve("lib$it.a").path) } +
                    listOf(static.resolve("libffi.a").path, "-export_dynamic", "-lz", "-lc++", "-framework", "Foundation",
                        "-framework", "CoreFoundation", "-framework", "Security", "-framework", "SystemConfiguration")
            }
            if (binary is Executable || binary is TestExecutable) {
                val install = project.tasks.register("njaInstall${binary.name.replaceFirstChar { it.uppercaseChar() }}$name", Copy::class.java) {
                    onlyIf("the runtime is the JVM") { !graal.get() }
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

    /**
     * A Kotlin/Native macOS, Linux or Windows target: its executables find the runtime in `nja/` next to them (or in
     * `NJA_HOME`), loaded at run time, so they link (and cross-compile) without it. A native image builds on its own
     * platform's host only: built elsewhere, the executables get no runtime (a warning says so).
     */
    private fun configureDesktop(
        project: Project,
        extension: NjaExtension,
        classPath: Configuration,
        target: KotlinNativeTarget,
        nativeLibrary: Lazy<TaskProvider<NjaNativeLibraryTask>>,
    ) {
        val name = target.name.replaceFirstChar { it.uppercaseChar() }
        val graal = extension.desktopRuntime.map {
            check(it != NjaRuntimeType.ART) { "NJA: ART is Android's: desktopRuntime is GRAALVM or JVM" }
            it == NjaRuntimeType.GRAALVM
        }
        val hostTarget = isHostTarget(target.konanTarget)
        val targetName = target.name
        val bundle = project.tasks.register("njaDesktopBundle$name", Sync::class.java) {
            description = "Lays out what ${target.name}'s executables load: the native image, or the JVM's class path and options."
            into(project.layout.buildDirectory.dir("nja/desktop/${target.name}"))
            dependsOn(graal.map { if (it && hostTarget) listOf(nativeLibrary.value) else emptyList() })
            from(graal.map { if (it && hostTarget) listOf(nativeLibrary.value.flatMap { task -> task.libraryFile }) else emptyList<Any>() }) {
                into("nja")
            }
            from(graal.map { if (it) emptyList<Any>() else listOf(classPath) }) { into("nja/classpath") }
            val options = extension.jvmOptions
            inputs.property("jvmOptions", options)
            inputs.property("graal", graal)
            doLast {
                if (graal.get()) {
                    if (!hostTarget) {
                        logger.warn(
                            "nja: $targetName's native image builds on its own platform only (not ${NjaGraalvmTask.hostPlatform()}): " +
                                "its executables have no runtime",
                        )
                    }
                } else {
                    destinationDir.resolve("nja").mkdirs()
                    destinationDir.resolve("nja/jvm.options").writeText(options.get().joinToString("\n"))
                }
            }
        }
        // The JVM on this host: a JDK 25 (Gradle's toolchain) runs the tests
        val testJavaHome = project.provider {
            project.extensions.findByType(JavaToolchainService::class.java)
                ?.launcherFor { languageVersion.set(JavaLanguageVersion.of(JAVA_VERSION)) }?.get()?.metadata?.installationPath?.asFile?.path
        }
        target.binaries.configureEach {
            val binary = this
            if (binary !is Executable && binary !is TestExecutable) return@configureEach
            val install = project.tasks.register("njaInstall${binary.name.replaceFirstChar { it.uppercaseChar() }}$name", Copy::class.java) {
                from(bundle)
                into(binary.outputDirectory)
                // The previous runtime goes: a native image and a class path don't mix
                val runtimeDir = binary.outputDirectory.resolve("nja")
                doFirst { runtimeDir.deleteRecursively() }
            }
            binary.linkTaskProvider.configure { finalizedBy(install) }
            if (binary is TestExecutable) {
                project.tasks.withType(KotlinNativeTest::class.java).matching { it.name == "${target.name}Test" }.configureEach {
                    dependsOn(install)
                    if (!graal.get()) testJavaHome.get()?.let { environment("JAVA_HOME", it) }
                }
            }
        }
    }

    private fun isHostTarget(target: KonanTarget): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val family = when {
            os.startsWith("mac") -> Family.OSX
            os.startsWith("linux") -> Family.LINUX
            os.startsWith("windows") -> Family.MINGW
            else -> return false
        }
        val architecture = when (System.getProperty("os.arch")) {
            "aarch64", "arm64" -> Architecture.ARM64
            "x86_64", "amd64" -> Architecture.X64
            else -> return false
        }
        return target.family == family && target.architecture == architecture
    }

    private fun isLinuxHost() = System.getProperty("os.name").lowercase().startsWith("linux")

    companion object {
        const val FACADES = "njaFacades"

        /** The façades' Java version: the desktop JVM's tests run on a JDK of it. */
        private const val JAVA_VERSION = 25

        /** jdk-mobile's targets, as its releases name their runtimes. */
        val JDK_MOBILE_TARGETS = listOf("android-aarch64", "android-x64", "ios-simulator-aarch64", "ios-device-aarch64")

        /** A native image's libraries on iOS, from graalvm-mobile's kit: Substrate VM's and the JDK's. */
        val IOS_NATIVE_IMAGE_LIBRARIES = listOf("jvm", "libchelper", "darwin", "java", "nio", "net", "zip", "extnet", "jimage",
            "verify", "management_ext", "management", "prefs")
    }
}

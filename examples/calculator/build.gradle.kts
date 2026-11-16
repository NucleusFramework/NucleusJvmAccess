plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("dev.nucleusframework.nja")
}

kotlin {
    android {
        namespace = "com.example.calculator"
        compileSdk = 35
        // The tests' names have spaces: DEX supports them from Android 11 (the bridge itself runs from Android 10)
        minSdk = 30
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    iosSimulatorArm64()
    iosArm64()
    // Kotlin/Native on the desktop: the same suites, the runtime next to the test executable
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    // The desktop: the façades in this JVM, the same suites
    jvm()

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
        }
    }
}

nucleusJvm {
    facade(project(":examples:calculator-facade"))
    // A second façade in the same JVM: NJA's own stress tests
    facade(project(":examples:torture-facade"))
    jvmOptions.addAll("-Xss2m", "-Xmx512m")
    // The same suites on a JVM (jdk-mobile's on iOS and Android, a JDK's on the desktop): -Pnja.runtime=JVM
    providers.gradleProperty("nja.runtime").orNull?.let { runtime.set(dev.nucleusframework.nja.plugin.NjaRuntimeType.valueOf(it)) }
    // -Pnja.androidAbis=arm64-v8a,x86_64
    providers.gradleProperty("nja.androidAbis").orNull?.let { androidAbis.set(it.split(',').toSet()) }
}

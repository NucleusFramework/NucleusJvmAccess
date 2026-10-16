import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("dev.nucleusframework.nja")
}

kotlin {
    android {
        namespace = "com.example.bench"
        compileSdk = 35
        minSdk = 29
    }
    iosSimulatorArm64 {
        binaries.executable("bench", listOf(NativeBuildType.RELEASE)) {
            entryPoint = "com.example.bench.main"
        }
    }
    iosArm64()

    compilerOptions {
        optIn.add("dev.nucleusframework.nja.NjaInternal")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // The baseline: the same workloads, run by ART
            implementation(project(":examples:benchmark-workloads"))
        }
    }
}

nucleusJvm {
    facade(project(":examples:benchmark-facade"))
    jdkMobile = rootProject.layout.projectDirectory.dir(".jdk-mobile/stage")
    jvmOptions.addAll("-Xmx512m", "-Xss2m")
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.nucleusframework.nja"
        compileSdk = 35
        minSdk = 29
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("jni") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/jni.def"))
            includeDirs(project.file("src/nativeInterop/cinterop/include"))
        }
    }

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("dev.nucleusframework.nja.NjaInternal")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}

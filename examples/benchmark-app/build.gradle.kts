plugins {
    id("com.android.application")
}

// The Android benchmarks in a release (non debuggable) app: ART's JIT at full speed for the baseline
android {
    namespace = "com.example.benchapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.benchapp"
        minSdk = 29
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":examples:benchmark"))
    implementation(project(":examples:benchmark-workloads"))
}

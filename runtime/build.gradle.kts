plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

subprojects {
    group = "dev.nucleusframework.nja"
    version = "0.1.0-SNAPSHOT"
}

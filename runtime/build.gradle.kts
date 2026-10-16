plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

// A release's version is its tag's (v0.1.0 -> 0.1.0)
val njaVersion = providers.environmentVariable("GITHUB_REF").orNull?.takeIf { it.startsWith("refs/tags/v") }
    ?.removePrefix("refs/tags/v") ?: "0.1.0-SNAPSHOT"

subprojects {
    group = "dev.nucleusframework.nja"
    version = njaVersion
}

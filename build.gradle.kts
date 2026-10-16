plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    id("dev.nucleusframework.nja") apply false
    id("dev.nucleusframework.nja.facade") apply false
}

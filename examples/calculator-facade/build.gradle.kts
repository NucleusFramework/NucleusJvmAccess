plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.nucleusframework.nja.facade")
}

// A calculator exercising every supported type and declaration: it runs on the JVM, used from iOS and Android
kotlin {
    jvmToolchain(25)
}

njaFacade {
    libName = "calculator"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

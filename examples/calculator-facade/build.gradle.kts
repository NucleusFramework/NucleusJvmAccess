plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.nucleusframework.nja.facade")
}

// NNA's calculator example, unchanged: here it runs on the JVM, used from iOS and Android
kotlin {
    jvmToolchain(25)
}

njaFacade {
    libName = "calculator"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

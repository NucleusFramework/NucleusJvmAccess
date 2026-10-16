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
    // Apart from the façade's: the JVM target has both on its class path
    clientPackage = "com.example.calculator.client"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

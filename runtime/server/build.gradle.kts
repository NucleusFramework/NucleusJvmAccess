plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Runs in the mobile JVM (jdk-mobile, JDK 27): its bytecode targets Java 25, the façades' level
kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}

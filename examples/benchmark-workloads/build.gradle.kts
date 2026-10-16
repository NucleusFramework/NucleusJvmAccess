plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Workloads on well-known Java libraries, shared by the façade (run by NJA's JVM) and the Android app (run by ART,
// the baseline): Java 17 bytecode, which ART runs too
kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    api("org.apache.commons:commons-math3:3.6.1")
}

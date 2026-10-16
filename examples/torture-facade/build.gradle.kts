plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.nucleusframework.nja.facade")
}

// Stresses what's specific to NJA: the JVM's signals (implicit null checks, stack banging) next to ART's, nested
// JVM <-> app calls, the JVM's own threads, and the bridge's lifecycles (handles, channels, async calls)
kotlin {
    jvmToolchain(25)
}

njaFacade {
    libName = "torture"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

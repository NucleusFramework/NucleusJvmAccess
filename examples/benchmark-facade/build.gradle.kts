plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.nucleusframework.nja.facade")
}

kotlin {
    jvmToolchain(25)
}

njaFacade {
    libName = "benchmark"
}

dependencies {
    implementation(project(":examples:benchmark-workloads"))
    // Lucene 10 needs Java 21: what ART can't run
    implementation("org.apache.lucene:lucene-core:10.5.2")
    implementation(libs.kotlinx.coroutines.core)
}

// The same workloads on the desktop's HotSpot (the build's JDK 25), as a reference
val host by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
tasks.register<JavaExec>("runHostBenchmark") {
    classpath = host.runtimeClasspath
    mainClass = "com.example.bench.HostBenchmarkKt"
}

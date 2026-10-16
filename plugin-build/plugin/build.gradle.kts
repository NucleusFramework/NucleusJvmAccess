plugins {
    `kotlin-dsl`
    alias(libs.plugins.plugin.publish)
}

group = "dev.nucleusframework.nja"
// A release's version is its tag's (v0.1.0 -> 0.1.0), the runtimes' too: the plugin adds them at its own version
version = providers.environmentVariable("GITHUB_REF").orNull?.takeIf { it.startsWith("refs/tags/v") }
    ?.removePrefix("refs/tags/v") ?: "0.1.0-SNAPSHOT"

dependencies {
    // kotlin-compiler-embeddable for PSI parsing (compileOnly: loaded at run time in an isolated worker classloader)
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.android.gradle.plugin)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.compiler.embeddable)
}

kotlin {
    jvmToolchain(21)
}

// The Android bridge's C source, compiled by the consumer's build (with its NDK)
sourceSets.main {
    resources.srcDir(layout.projectDirectory.dir("../../runtime/android-native/src"))
}

val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/nucleusframework/nja/plugin/NjaVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package dev.nucleusframework.nja.plugin\n\ninternal const val NJA_VERSION = \"$v\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateVersion) }

gradlePlugin {
    website.set("https://github.com/NucleusFramework/NucleusJvmAccess")
    vcsUrl.set("https://github.com/NucleusFramework/NucleusJvmAccess")
    plugins {
        create("nja") {
            id = "dev.nucleusframework.nja"
            implementationClass = "dev.nucleusframework.nja.plugin.NjaPlugin"
            displayName = "Nucleus JVM Access"
            description = "Use Java libraries from Kotlin Multiplatform iOS and Android apps, on a real JVM in the app's process"
            tags.set(listOf("kotlin", "multiplatform", "kmp", "jvm", "android", "ios", "java"))
        }
        create("njaFacade") {
            id = "dev.nucleusframework.nja.facade"
            implementationClass = "dev.nucleusframework.nja.plugin.NjaFacadePlugin"
            displayName = "Nucleus JVM Access façade"
            description = "Exports a Kotlin/JVM module's API to Kotlin Multiplatform apps through NJA"
            tags.set(listOf("kotlin", "multiplatform", "kmp", "jvm", "android", "ios", "java"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// The Plugin Portal's credentials, from the CI's environment
tasks.register("setupPluginUploadFromEnvironment") {
    doLast {
        val key = System.getenv("GRADLE_PUBLISH_KEY")
        val secret = System.getenv("GRADLE_PUBLISH_SECRET")
        if (key == null || secret == null) throw GradleException("GRADLE_PUBLISH_KEY and GRADLE_PUBLISH_SECRET must be set")
        System.setProperty("gradle.publish.key", key)
        System.setProperty("gradle.publish.secret", secret)
    }
}

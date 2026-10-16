plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
}

kotlin {
    android {
        namespace = "dev.nucleusframework.nja"
        compileSdk = 35
        minSdk = 29
    }

    // A JVM app (the desktop): the façades run in its own JVM (see Platform.jvm.kt)
    jvm()
    jvmToolchain(25)

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("jni") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/jni.def"))
            includeDirs(project.file("src/nativeInterop/cinterop/include"))
        }
    }

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("dev.nucleusframework.nja.NjaInternal")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            // The dispatcher the façades run behind, in the app's JVM
            implementation(project(":nja-server"))
        }
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework.nja", project.name, project.version.toString())
    pom {
        name.set("Nucleus JVM Access client")
        description.set("The part of Nucleus JVM Access running in the app (Android, iOS, the JVM): starts the JVM and calls the façades")
        url.set("https://github.com/NucleusFramework/NucleusJvmAccess")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("nucleusframework")
                name.set("NucleusFramework")
                url.set("https://github.com/NucleusFramework")
            }
        }
        scm {
            url.set("https://github.com/NucleusFramework/NucleusJvmAccess")
            connection.set("scm:git:git://github.com/NucleusFramework/NucleusJvmAccess.git")
            developerConnection.set("scm:git:ssh://git@github.com/NucleusFramework/NucleusJvmAccess.git")
        }
    }
    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}

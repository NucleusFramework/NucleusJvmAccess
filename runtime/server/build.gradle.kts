plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

// Runs in the mobile JVM (jdk-mobile, JDK 27): its bytecode targets Java 25, the façades' level
kotlin {
    jvmToolchain(25)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}

mavenPublishing {
    coordinates("dev.nucleusframework.nja", project.name, project.version.toString())
    pom {
        name.set("Nucleus JVM Access server")
        description.set("The part of Nucleus JVM Access running in the mobile JVM: dispatches the app's calls to the façades")
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

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "nja-runtime"

// Named after their artifacts (dev.nucleusframework.nja:nja-server, :nja-client): the plugin adds them by these
// coordinates, and the composite builds substitute these projects for them
include(":nja-server")
project(":nja-server").projectDir = file("server")
include(":nja-client")
project(":nja-client").projectDir = file("client")

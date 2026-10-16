pluginManagement {
    // The NJA plugin from source
    includeBuild("plugin-build")
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
}

rootProject.name = "nucleus-jvm-access"

// The runtimes (dev.nucleusframework.nja:nja-server and :nja-client), substituted for the plugin's dependencies
includeBuild("runtime")

include(":examples:calculator-facade")
include(":examples:calculator")
include(":examples:torture-facade")
include(":examples:benchmark-workloads")
include(":examples:benchmark-facade")
include(":examples:benchmark")
include(":examples:benchmark-app")

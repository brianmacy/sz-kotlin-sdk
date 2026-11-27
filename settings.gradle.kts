rootProject.name = "sz-kotlin-sdk"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":sdk", ":test-support", ":examples", ":tools")

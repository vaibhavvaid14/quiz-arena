rootProject.name = "quiz-arena"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// shared: models and scoring rules, compiled for both the JVM server and the
// browser client, so the API contract is checked at compile time on both ends.
include("shared")
include("server")
include("client")

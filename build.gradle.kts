// Versions live here so the three modules cannot drift apart.
plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("io.ktor.plugin") version "3.0.3" apply false
}

allprojects {
    group = "quizarena"
    version = "2.0.0"
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
    // The generated wrapper still points at services.gradle.org; this only skips
    // Gradle's reachability probe, which the build sandbox blocks.
    validateDistributionUrl = false
}

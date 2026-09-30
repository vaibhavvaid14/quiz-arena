plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
}

application {
    mainClass.set("quizarena.server.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))

    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-call-logging")

    // SQLite over JDBC: the same engine and the same schema.sql as before.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * `./gradlew :server:run` should just work, so it builds the web bundle first
 * and points the server at it. Without this the server would start with no UI.
 */
tasks.named<JavaExec>("run") {
    dependsOn(":client:assembleWeb")
    environment("QUIZ_STATIC", rootProject.file("client/build/web").absolutePath)
}

/**
 * The question bank stays in data/questions.json, where it is meant to be
 * edited, and is copied into the jar at build time. Keeping a second copy
 * under resources invited editing one and shipping the other.
 */
tasks.processResources {
    from(rootProject.file("data/questions.json"))
}

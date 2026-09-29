plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "quiz-arena.js"
            }
            // The client needs a real DOM, so its tests run in headless Chrome
            // rather than on Node.
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        // Produces a single bundle the page loads; no other build step.
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

/**
 * Assembles the deployable web root: the page, the stylesheet, the theme
 * bootstrap and the compiled bundle. The server serves this directory
 * (QUIZ_STATIC), and the Dockerfile copies it.
 */
val assembleWeb by tasks.registering(Sync::class) {
    dependsOn("jsBrowserProductionWebpack")
    into(layout.buildDirectory.dir("web"))
    from("src/jsMain/resources") // index.html
    from(rootProject.file("css")) { into("css") }
    from(rootProject.file("js/theme-init.js")) { into("js") }
    from(layout.buildDirectory.file("kotlin-webpack/js/productionExecutable/quiz-arena.js")) { into("js") }
}

package quizarena.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Starts Quiz Arena: the JSON API, the SQLite database and the web app.
 *
 * Configuration comes from the environment, so a host like Render can supply
 * PORT (and a writable QUIZ_DB path) without changing the start command.
 *
 *   HOST         interface to bind (default 127.0.0.1; Render needs 0.0.0.0)
 *   PORT         port to listen on (default 8000)
 *   QUIZ_DB      SQLite file (default data/quiz.db)
 *   QUIZ_STATIC  directory holding index.html, css/ and js/ (default .)
 *   QUIZ_TEST    "1" for a throwaway database that also serves /tests/
 */
object Config {
    val host: String get() = System.getenv("HOST") ?: "127.0.0.1"
    val port: Int get() = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val dbPath: String get() = System.getenv("QUIZ_DB") ?: "data/quiz.db"
    val testMode: Boolean get() = System.getenv("QUIZ_TEST") == "1"
    val staticRoot: Path get() = Paths.get(System.getenv("QUIZ_STATIC") ?: ".")
}

fun main() {
    val testMode = Config.testMode
    val dbPath = if (testMode) {
        Files.createTempDirectory("quiz-arena-test-").resolve("test.db").toString()
    } else {
        Config.dbPath
    }
    Paths.get(dbPath).toAbsolutePath().parent?.let { Files.createDirectories(it) }

    val db = Db.connect(dbPath)
    Db.migrate(db)
    val counts = Seeder.seed(db, Seeder.parse(Seeder.loadBankText()))

    val url = "http://${Config.host}:${Config.port}/"
    println("Quiz Arena running at $url")
    println("  database: ${if (testMode) "(temporary test database)" else dbPath}")
    println("  questions: ${counts.inserted} added, ${counts.updated} updated, ${counts.deactivated} retired")
    if (testMode) println("  browser tests: ${url}tests/")
    println("  Ctrl+C to stop")

    embeddedServer(Netty, port = Config.port, host = Config.host) {
        module(db, testMode = testMode, staticRoot = Config.staticRoot)
    }.start(wait = true)
}

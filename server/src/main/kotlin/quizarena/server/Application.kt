package quizarena.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Starts Quiz Arena: the JSON API, the SQLite database and the web app.
 *
 * Configuration comes from the environment first, so a host like Render can
 * supply PORT (and a writable QUIZ_DB path) without changing the start command.
 */
object Config {
    val host: String get() = System.getenv("HOST") ?: "127.0.0.1"
    val port: Int get() = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val dbPath: String get() = System.getenv("QUIZ_DB") ?: "data/quiz.db"
}

fun main() {
    val db = Db.connect(Config.dbPath)
    val version = Db.migrate(db)
    println("Quiz Arena starting on http://${Config.host}:${Config.port}/")
    println("  database: ${Config.dbPath} (schema v$version)")

    embeddedServer(Netty, port = Config.port, host = Config.host) {
        module(db)
    }.start(wait = true)
}

package quizarena.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import quizarena.shared.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Bodies are small; anything larger is a mistake or an attack. */
private const val MAX_BODY_BYTES = 64 * 1024

private val ATTEMPT_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

/** Only these may be served. Everything else (server source, the bank) 404s. */
private val STATIC_FILES = setOf("index.html")
private val STATIC_PREFIXES = listOf("css/", "js/")
private val TEST_PREFIXES = listOf("tests/") // only in test mode, which uses a throwaway database

private val MIME_TYPES = mapOf(
    "html" to "text/html; charset=utf-8",
    "css" to "text/css; charset=utf-8",
    "js" to "text/javascript; charset=utf-8",
    "json" to "application/json; charset=utf-8",
    "svg" to "image/svg+xml",
    "png" to "image/png",
    "ico" to "image/x-icon",
)

private val SECURITY_HEADERS = mapOf(
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to listOf(
        "default-src 'self'; script-src 'self'",
        "style-src 'self' https://fonts.googleapis.com",
        "font-src 'self' https://fonts.gstatic.com",
        "img-src 'self' data:",
        "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'",
    ).joinToString("; "),
)

fun Application.module(db: Db, testMode: Boolean = Config.testMode, staticRoot: Path = Config.staticRoot) {
    val service = QuizService(db)
    val lenient = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    install(ContentNegotiation) { json(lenient) }

    install(DefaultHeaders) {
        SECURITY_HEADERS.forEach { (name, value) -> header(name, value) }
    }

    // One place turns a domain error into the API's error envelope. Anything
    // unexpected becomes a generic 500: internals never reach the player.
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.noStore()
            call.respond(HttpStatusCode.fromValue(cause.status), ErrorResponse(ErrorBody(cause.code, cause.message)))
        }
        exception<SeedException> { call, cause ->
            call.application.environment.log.error("Seed failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorBody("server_error", "Question bank error.")))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled failure at ${call.request.uri}", cause)
            call.noStore()
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody("server_error", "Something went wrong. Please try again.")),
            )
        }
    }

    routing {
        // ---------------------------------------------------------- open API
        get("/api/health") {
            call.noStore()
            call.respond(HealthResponse(ok = true, time = System.currentTimeMillis()))
        }
        get("/api/catalog") {
            call.noStore()
            call.respond(service.catalog())
        }
        post("/api/players") {
            call.noStore()
            call.respond(HttpStatusCode.Created, service.createPlayer(call.body(lenient)))
        }
        get("/api/leaderboard") {
            call.noStore()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            // A valid key also returns your own rank; an absent one is fine here.
            val player = runCatching { service.authenticate(call.playerKey()) }.getOrNull()
            call.respond(service.leaderboard(limit, player))
        }

        // ------------------------------------------------------ authenticated
        get("/api/me") {
            call.noStore()
            call.respond(service.authenticate(call.playerKey()))
        }
        get("/api/me/history") {
            call.noStore()
            call.respond(service.history(service.authenticate(call.playerKey())))
        }
        delete("/api/me/history") {
            call.noStore()
            call.respond(service.clearHistory(service.authenticate(call.playerKey())))
        }
        post("/api/attempts") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            call.respond(HttpStatusCode.Created, service.createAttempt(player, call.body(lenient)))
        }
        get("/api/attempts/{id}") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            call.respond(service.getState(player, call.attemptId()))
        }
        delete("/api/attempts/{id}") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            service.discard(player, call.attemptId())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/attempts/{id}/answer") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            call.respond(service.answer(player, call.attemptId(), call.body(lenient)))
        }
        post("/api/attempts/{id}/next") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            call.respond(service.next(player, call.attemptId(), call.body(lenient)))
        }
        post("/api/attempts/{id}/finish") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            call.respond(service.finish(player, call.attemptId()))
        }
        get("/api/attempts/{id}/results") {
            call.noStore()
            val player = service.authenticate(call.playerKey())
            call.respond(service.results(player, call.attemptId()))
        }

        // Anything else under /api/ is a genuine 404, never a static lookup.
        route("/api/{...}") {
            handle { throw NotFoundException("Unknown endpoint.") }
        }

        // ------------------------------------------------------------ static
        get("/{path...}") {
            call.serveStatic(staticRoot, testMode)
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun ApplicationCall.noStore() {
    response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
}

private fun ApplicationCall.playerKey(): String? = request.headers["X-Player-Key"]

private fun ApplicationCall.attemptId(): String {
    val id = parameters["id"].orEmpty()
    // Same answer as a missing quiz: a malformed id reveals nothing.
    if (!ATTEMPT_ID.matches(id)) throw NotFoundException("Quiz not found.")
    return id
}

private suspend inline fun <reified T> ApplicationCall.body(json: Json): T {
    val text = receiveText()
    if (text.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
        throw ApiException("Request body is too large.", status = 413, code = "too_large")
    }
    if (text.isBlank()) return json.decodeFromString<T>("{}")
    return try {
        json.decodeFromString<T>(text)
    } catch (t: Throwable) {
        throw ValidationException("Request body must be a JSON object.")
    }
}

/**
 * Serves the web app. The traversal check is made on the RESOLVED path, so
 * "js/../server/QuizService.kt" cannot pass by starting with an allowed prefix.
 */
private suspend fun ApplicationCall.serveStatic(root: Path, testMode: Boolean) {
    val raw = request.uri.substringBefore('?').removePrefix("/")
    val requested = when {
        raw.isEmpty() -> "index.html"
        raw.endsWith("/") -> raw + "index.html"
        else -> raw
    }
    val base = root.toAbsolutePath().normalize()
    val target = base.resolve(requested).normalize()

    if (!target.startsWith(base) || !target.isRegularFile()) {
        throw NotFoundException("File not found.")
    }
    val relative = base.relativize(target).joinToString("/")
    val allowed = STATIC_PREFIXES + if (testMode) TEST_PREFIXES else emptyList()
    if (relative !in STATIC_FILES && allowed.none { relative.startsWith(it) }) {
        throw NotFoundException("File not found.")
    }

    val type = MIME_TYPES[target.extension.lowercase()]
        ?: Files.probeContentType(target)
        ?: "application/octet-stream"
    response.headers.append(HttpHeaders.CacheControl, "no-cache", safeOnly = false)
    respondBytes(Files.readAllBytes(target), ContentType.parse(type))
}

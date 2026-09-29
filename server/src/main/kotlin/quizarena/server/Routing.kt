package quizarena.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import quizarena.shared.ErrorBody
import quizarena.shared.ErrorResponse
import quizarena.shared.HealthResponse

/**
 * The same Content-Security-Policy the previous server sent: everything from
 * this origin, plus exactly the two Google Fonts hosts the UI needs.
 */
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

fun Application.module(db: Db) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }

    install(DefaultHeaders) {
        SECURITY_HEADERS.forEach { (name, value) -> header(name, value) }
    }

    // One place turns a domain error into the API's error envelope. Anything
    // unexpected becomes a generic 500: internals never reach the player.
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.status),
                ErrorResponse(ErrorBody(cause.code, cause.message)),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled failure", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody("server_error", "Something went wrong. Please try again.")),
            )
        }
    }

    routing {
        get("/api/health") {
            call.respond(HealthResponse(ok = true, time = System.currentTimeMillis()))
        }
    }
}

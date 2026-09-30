package quizarena.server

/**
 * Domain errors that map directly onto HTTP responses. [Routing] installs a
 * single StatusPages handler that turns any of these into the API's error
 * envelope, `{"error": {"code", "message"}}`.
 *
 * The message is always safe to show a player: nothing here leaks internals.
 */
open class ApiException(
    override val message: String,
    val status: Int = 400,
    val code: String = "bad_request",
) : RuntimeException(message)

class ValidationException(message: String, code: String = "invalid_input") :
    ApiException(message, status = 400, code = code)

class UnauthorizedException(message: String) :
    ApiException(message, status = 401, code = "unauthorized")

class NotFoundException(message: String) :
    ApiException(message, status = 404, code = "not_found")

class ConflictException(message: String, code: String = "conflict") :
    ApiException(message, status = 409, code = code)

package quizarena.client

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import quizarena.shared.*
import kotlin.js.json

/**
 * The browser's half of the API. Request and response types come from the
 * `shared` module, so this file and the server cannot disagree about the wire
 * format without failing to compile.
 */
class ApiError(
    message: String,
    val status: Int,
    val code: String,
) : RuntimeException(message)

class Api(
    private val baseUrl: String = "",
    private val getKey: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend inline fun <reified T> get(path: String): T = request("GET", path, null)

    suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        request("POST", path, json.encodeToString(body))

    suspend inline fun <reified T> delete(path: String): T = request("DELETE", path, null)

    suspend inline fun <reified T> request(method: String, path: String, body: String?): T {
        val headers = json("Content-Type" to "application/json").unsafeCast<dynamic>()
        getKey()?.let { headers["X-Player-Key"] = it }

        val response = try {
            window.fetch(
                baseUrl + path,
                RequestInit(method = method, headers = headers, body = body),
            ).await()
        } catch (t: Throwable) {
            // A failed fetch means the network or the server, not a bad request.
            throw ApiError("Can't reach the quiz server.", status = 0, code = "offline")
        }

        val text = response.text().await()
        if (!response.ok) {
            val error = runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull()
            throw ApiError(
                error?.message ?: "Something went wrong (${response.status}).",
                status = response.status.toInt(),
                code = error?.code ?: "http_error",
            )
        }
        if (text.isBlank()) return json.decodeFromString<T>("{}")
        return json.decodeFromString<T>(text)
    }

    // ------------------------------------------------------------ endpoints

    suspend fun catalog(): CatalogResponse = get("/api/catalog")

    suspend fun createPlayer(name: String): PlayerCreated =
        post("/api/players", CreatePlayerRequest(name))

    suspend fun me(): PlayerIdentity = get("/api/me")

    suspend fun history(): HistoryResponse = get("/api/me/history")

    suspend fun clearHistory(): DeletedCount = delete("/api/me/history")

    suspend fun createAttempt(request: CreateAttemptRequest): AttemptState =
        post("/api/attempts", request)

    suspend fun attempt(id: String): AttemptState = get("/api/attempts/$id")

    suspend fun discard(id: String): Unit = delete("/api/attempts/$id")

    suspend fun answer(id: String, position: Int, optionId: Long?): AttemptState =
        post("/api/attempts/$id/answer", AnswerRequest(position, optionId))

    suspend fun next(id: String, position: Int): AttemptState =
        post("/api/attempts/$id/next", NextRequest(position))

    suspend fun finish(id: String): AttemptState =
        post<Unit?, AttemptState>("/api/attempts/$id/finish", null)

    suspend fun results(id: String): ResultsSummary = get("/api/attempts/$id/results")

    suspend fun leaderboard(limit: Int = 20): LeaderboardResponse =
        get("/api/leaderboard?limit=$limit")
}

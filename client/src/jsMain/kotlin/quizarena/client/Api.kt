package quizarena.client

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import quizarena.shared.*

/** A failure the UI can show: either the server's own message, or a network one. */
class ApiError(
    message: String,
    val status: Int,
    val code: String,
) : RuntimeException(message) {
    val isOffline: Boolean get() = status == 0
}

/**
 * The browser's half of the API.
 *
 * Request and response types come from the `shared` module, the same ones the
 * server builds its replies from, so this file and the server cannot disagree
 * about the wire format without failing to compile.
 *
 * Serializers are passed explicitly rather than reified: a public inline
 * function may not touch private state, and the key and the Json instance both
 * need to stay private.
 */
class Api(
    private val baseUrl: String = "",
    private val getKey: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun fetch(method: String, path: String, body: String?): Pair<Response, String> {
        val headers: dynamic = js("({})")
        headers["Content-Type"] = "application/json"
        getKey()?.let { headers["X-Player-Key"] = it }

        val response = try {
            window.fetch(baseUrl + path, RequestInit(method = method, headers = headers, body = body)).await()
        } catch (t: Throwable) {
            // A rejected fetch is the network or a stopped server, not a bad request.
            throw ApiError("Can't reach the quiz server.", status = 0, code = "offline")
        }
        val text = response.text().await()
        if (!response.ok) {
            val error = runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
            throw ApiError(
                error?.message ?: "Something went wrong (${response.status}).",
                status = response.status.toInt(),
                code = error?.code ?: "http_error",
            )
        }
        return response to text
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        body: String?,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val (_, text) = fetch(method, path, body)
        return json.decodeFromString(deserializer, text)
    }

    private suspend fun send(method: String, path: String, body: String? = null) {
        fetch(method, path, body)
    }

    private fun <B> encode(serializer: SerializationStrategy<B>, value: B): String =
        json.encodeToString(serializer, value)

    // ------------------------------------------------------------ endpoints

    suspend fun catalog(): CatalogResponse =
        call("GET", "/api/catalog", null, CatalogResponse.serializer())

    suspend fun createPlayer(name: String): PlayerCreated =
        call(
            "POST", "/api/players",
            encode(CreatePlayerRequest.serializer(), CreatePlayerRequest(name)),
            PlayerCreated.serializer(),
        )

    suspend fun me(): PlayerIdentity =
        call("GET", "/api/me", null, PlayerIdentity.serializer())

    suspend fun history(): HistoryResponse =
        call("GET", "/api/me/history", null, HistoryResponse.serializer())

    suspend fun clearHistory(): DeletedCount =
        call("DELETE", "/api/me/history", null, DeletedCount.serializer())

    suspend fun createAttempt(request: CreateAttemptRequest): AttemptState =
        call(
            "POST", "/api/attempts",
            encode(CreateAttemptRequest.serializer(), request),
            AttemptState.serializer(),
        )

    suspend fun attempt(id: String): AttemptState =
        call("GET", "/api/attempts/${encodeSegment(id)}", null, AttemptState.serializer())

    suspend fun discard(id: String) =
        send("DELETE", "/api/attempts/${encodeSegment(id)}")

    suspend fun answer(id: String, position: Int, optionId: Long?): AttemptState =
        call(
            "POST", "/api/attempts/${encodeSegment(id)}/answer",
            encode(AnswerRequest.serializer(), AnswerRequest(position, optionId)),
            AttemptState.serializer(),
        )

    suspend fun next(id: String, position: Int): AttemptState =
        call(
            "POST", "/api/attempts/${encodeSegment(id)}/next",
            encode(NextRequest.serializer(), NextRequest(position)),
            AttemptState.serializer(),
        )

    suspend fun finish(id: String): AttemptState =
        call("POST", "/api/attempts/${encodeSegment(id)}/finish", "{}", AttemptState.serializer())

    suspend fun results(id: String): ResultsSummary =
        call("GET", "/api/attempts/${encodeSegment(id)}/results", null, ResultsSummary.serializer())

    suspend fun leaderboard(limit: Int = 20): LeaderboardResponse =
        call("GET", "/api/leaderboard?limit=$limit", null, LeaderboardResponse.serializer())
}

/** Attempt ids are opaque tokens; encode them rather than trusting their shape. */
private fun encodeSegment(value: String): String = encodeURIComponent(value)

private external fun encodeURIComponent(value: String): String

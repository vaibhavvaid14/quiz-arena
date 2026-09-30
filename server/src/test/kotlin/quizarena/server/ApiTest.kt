package quizarena.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The HTTP layer: auth, the error envelope, request limits and the static file
 * allowlist. The quiz rules themselves are covered by [ServiceTest]; these tests
 * are about what the wire does with them.
 */
class ApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A static root holding only what the allowlist should permit, plus a file it should not. */
    private fun staticRoot(): Path {
        val root = Files.createTempDirectory("quiz-static-")
        Files.writeString(root.resolve("index.html"), "<!doctype html><title>Quiz Arena</title>")
        Files.createDirectory(root.resolve("css"))
        Files.writeString(root.resolve("css").resolve("styles.css"), "body{}")
        Files.createDirectory(root.resolve("secret"))
        Files.writeString(root.resolve("secret").resolve("answers.json"), "{}")
        return root
    }

    private fun ApplicationTestBuilder.setup(root: Path = staticRoot()) {
        val db = freshDb()
        application { module(db, testMode = false, staticRoot = root) }
    }

    private suspend fun HttpResponse.json(): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    // -------------------------------------------------------------- open API

    @Test
    fun `health and catalog are public and uncached`() = testApplication {
        setup()
        val health = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.json()["ok"]!!.jsonPrimitive.boolean)
        assertEquals("no-store", health.headers[HttpHeaders.CacheControl])
        assertTrue(health.headers["Content-Security-Policy"]!!.contains("default-src 'self'"))

        val catalog = client.get("/api/catalog").json()
        assertEquals(2, catalog["topics"]!!.jsonArray.size)
        assertEquals(30, catalog["rules"]!!.jsonObject["difficultyPoints"]!!.jsonObject["hard"]!!.jsonPrimitive.int)
    }

    @Test
    fun `claiming a name returns a key and a repeat is a conflict`() = testApplication {
        setup()
        val first = client.post("/api/players") {
            contentType(ContentType.Application.Json); setBody("""{"name":"Ada"}""")
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val key = first.json()["key"]!!.jsonPrimitive.content
        assertTrue(key.isNotBlank())

        val again = client.post("/api/players") {
            contentType(ContentType.Application.Json); setBody("""{"name":"ada"}""")
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("name_taken", again.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val me = client.get("/api/me") { header("X-Player-Key", key) }
        assertEquals("Ada", me.json()["name"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ auth

    @Test
    fun `protected routes need a key`() = testApplication {
        setup()
        for (path in listOf("/api/me", "/api/me/history")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, response.status, path)
            assertEquals("unauthorized", response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/me") { header("X-Player-Key", "nonsense") }.status,
        )
    }

    // ----------------------------------------------------------- error shape

    @Test
    fun `errors use the envelope and never leak internals`() = testApplication {
        setup()
        val response = client.post("/api/players") {
            contentType(ContentType.Application.Json); setBody("""{"name":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.json()["error"]!!.jsonObject
        assertEquals("invalid_input", error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `malformed json is a validation error, not a crash`() = testApplication {
        setup()
        val response = client.post("/api/players") {
            contentType(ContentType.Application.Json); setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `oversized bodies are refused`() = testApplication {
        setup()
        val response = client.post("/api/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${"x".repeat(70_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `a malformed attempt id is a plain not-found`() = testApplication {
        setup()
        val key = client.post("/api/players") {
            contentType(ContentType.Application.Json); setBody("""{"name":"Sam"}""")
        }.json()["key"]!!.jsonPrimitive.content

        val response = client.get("/api/attempts/!!!") { header("X-Player-Key", key) }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `unknown api paths are not treated as static files`() = testApplication {
        setup()
        val response = client.get("/api/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        // A missing endpoint, not a missing file.
        assertEquals("Unknown endpoint.", response.json()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a known path with the wrong method is a 405, not a 404`() = testApplication {
        setup()
        assertEquals(HttpStatusCode.MethodNotAllowed, client.post("/api/catalog").status)
    }

    @Test
    fun `a non-numeric limit is refused rather than silently defaulted`() = testApplication {
        setup()
        val response = client.get("/api/leaderboard?limit=abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_input", response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        // An absent limit still takes the default.
        assertEquals(HttpStatusCode.OK, client.get("/api/leaderboard").status)
    }

    @Test
    fun `an oversized body is refused on its declared length`() = testApplication {
        setup()
        val response = client.post("/api/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${"x".repeat(70_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    // ---------------------------------------------------------------- static

    @Test
    fun `allowlisted static files are served`() = testApplication {
        setup()
        val index = client.get("/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.bodyAsText().contains("Quiz Arena"))
        assertEquals("no-cache", index.headers[HttpHeaders.CacheControl])

        val css = client.get("/css/styles.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
    }

    @Test
    fun `files outside the allowlist are refused`() = testApplication {
        setup()
        assertEquals(HttpStatusCode.NotFound, client.get("/secret/answers.json").status)
    }

    @Test
    fun `path traversal is refused on the resolved path`() = testApplication {
        setup()
        // Each of these starts with an allowed prefix but resolves outside it.
        for (path in listOf("/css/../secret/answers.json", "/css/../../etc/passwd", "/js/../secret/answers.json")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }
    }

    @Test
    fun `the test suite is only served in test mode`() = testApplication {
        val root = staticRoot()
        Files.createDirectory(root.resolve("tests"))
        Files.writeString(root.resolve("tests").resolve("index.html"), "tests")
        setup(root)
        assertEquals(HttpStatusCode.NotFound, client.get("/tests/").status)
    }
}

package quizarena.server

import kotlinx.serialization.json.*
import quizarena.shared.Rules
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.*

class MigrationTest {
    @Test
    fun `migrate is idempotent and versioned`() {
        val db = Db.connect(Db.MEMORY)
        assertEquals(1, Db.migrate(db))
        assertEquals(1, Db.migrate(db))
        val tables = db.read { conn ->
            conn.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString("name") }
        }.toSet()
        assertTrue(
            tables.containsAll(
                setOf("topics", "questions", "question_options", "players", "attempts", "attempt_questions"),
            ),
        )
    }

    @Test
    fun `file database uses WAL`() {
        val dir = Files.createTempDirectory("quiz-wal-")
        val db = Db.connect(dir.resolve("t.db").toString())
        val mode = db.read { conn ->
            conn.queryOne("PRAGMA journal_mode") { it.getString(1) }
        }
        assertEquals("wal", mode?.lowercase())
        db.close()
    }

    @Test
    fun `transaction rolls back on error`() {
        val db = freshDb()
        assertFailsWith<RuntimeException> {
            db.transaction { conn ->
                conn.update("UPDATE topics SET name = 'Changed' WHERE id = 'alpha'")
                throw RuntimeException("boom")
            }
        }
        val name = db.read { conn -> conn.queryOne("SELECT name FROM topics WHERE id = 'alpha'") { it.getString("name") } }
        assertEquals("Alpha", name)
    }

    @Test
    fun `statement splitter ignores comments`() {
        val statements = Db.splitStatements("-- a comment\nCREATE TABLE a (x INT); -- trailing\nCREATE TABLE b (y INT);")
        assertEquals(listOf("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)"), statements)
    }
}

class ConstraintTest {
    private val db = freshDb()

    @Test
    fun `foreign keys are enforced`() {
        assertFailsWith<SQLException> {
            db.read { conn ->
                conn.update(
                    "INSERT INTO questions (id, topic_id, difficulty, prompt, explanation, updated_at) " +
                        "VALUES ('x', 'ghost', 'easy', 'p', 'e', 0)",
                )
            }
        }
    }

    @Test
    fun `only one correct option per question`() {
        assertFailsWith<SQLException> {
            db.read { conn -> conn.update("UPDATE question_options SET is_correct = 1 WHERE question_id = 'a-e1'") }
        }
    }

    @Test
    fun `check constraints`() {
        assertFailsWith<SQLException> {
            db.read { conn -> conn.update("UPDATE questions SET difficulty = 'extreme' WHERE id = 'a-e1'") }
        }
        assertFailsWith<SQLException> {
            db.read { conn ->
                conn.update("INSERT INTO players (name, key_hash, created_at) VALUES ('   ', 'h', 0)")
            }
        }
    }

    @Test
    fun `player names are case insensitively unique`() {
        db.read { conn -> conn.update("INSERT INTO players (name, key_hash, created_at) VALUES ('Alex', 'h1', 0)") }
        assertFailsWith<SQLException> {
            db.read { conn -> conn.update("INSERT INTO players (name, key_hash, created_at) VALUES ('alex', 'h2', 0)") }
        }
    }
}

class SeedTest {
    @Test
    fun `shipped question bank is valid and balanced`() {
        val bank = Seeder.parse(Seeder.loadBankText())
        assertEquals(emptyList(), Seeder.validateBank(bank))

        val topics = bank["topics"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val questions = bank["questions"]!!.jsonArray.map { it.jsonObject }
        for (topic in topics) {
            val mine = questions.filter { it["topic"]!!.jsonPrimitive.content == topic }
            for (difficulty in Rules.DIFFICULTIES) {
                val n = mine.count { it["difficulty"]!!.jsonPrimitive.content == difficulty }
                assertTrue(n >= 4, "$topic/$difficulty has only $n")
            }
            // The correct answer must not cluster on one position, or players
            // could guess by position rather than by knowing the answer.
            val positions = mine.map { it["answer"]!!.jsonPrimitive.int }
            val worst = (0..3).maxOf { p -> positions.count { it == p } }
            assertTrue(worst.toDouble() / mine.size <= 0.35, "$topic answer bias")
        }
    }

    @Test
    fun `seed loads bank and is idempotent`() {
        val db = Db.connect(Db.MEMORY)
        Db.migrate(db)
        assertEquals(8, Seeder.seed(db, bank()).inserted)

        val again = Seeder.seed(db, bank())
        assertEquals(SeedCounts(inserted = 0, updated = 0, unchanged = 8, deactivated = 0), again)
        val options = db.read { conn -> conn.queryOne("SELECT COUNT(*) AS n FROM question_options") { it.getInt("n") } }
        assertEquals(32, options)
    }

    @Test
    fun `seed updates changed and deactivates removed`() {
        val db = freshDb()
        val trimmed = bank(BANK_QUESTION_IDS.dropLast(1)).edit { questions ->
            questions.mapIndexed { i, q ->
                if (i == 0) JsonObject(q.jsonObject + ("explanation" to JsonPrimitive("Better explanation."))) else q
            }
        }
        val counts = Seeder.seed(db, trimmed)
        assertEquals(1, counts.updated)
        assertEquals(1, counts.deactivated)
        val active = db.read { conn ->
            conn.queryOne("SELECT is_active FROM questions WHERE id = 'b-h2'") { it.getInt("is_active") }
        }
        assertEquals(0, active)

        // Bringing it back re-activates it.
        assertEquals(2, Seeder.seed(db, bank()).updated)
    }

    @Test
    fun `seed rejects option changes to played questions`() {
        val f = Fixture()
        f.start(count = 8)
        val changed = bank().edit { questions ->
            questions.mapIndexed { i, q ->
                if (i == 0) {
                    JsonObject(
                        q.jsonObject + ("options" to buildJsonArray { listOf("new", "set", "of", "options").forEach { add(it) } }),
                    )
                } else {
                    q
                }
            }
        }
        assertFailsWith<SeedException> { Seeder.seed(f.db, changed) }
        // Nothing was half-applied.
        val first = f.db.read { conn ->
            conn.queryOne("SELECT text FROM question_options WHERE question_id = 'a-e1' AND position = 0") {
                it.getString("text")
            }
        }
        assertEquals("zero", first)
    }

    @Test
    fun `invalid bank is rejected as a whole`() {
        val db = freshDb()
        val broken = bank().edit { questions ->
            questions + listOf(
                questions[0], // duplicate id
                buildJsonObject {
                    put("id", "x1"); put("topic", "ghost"); put("difficulty", "easy")
                    put("question", "Q?"); putJsonArray("options") { add("a"); add("b") }
                    put("answer", 0); put("explanation", "e")
                },
                buildJsonObject {
                    put("id", "x2"); put("topic", "alpha"); put("difficulty", "easy")
                    put("question", "Q?"); putJsonArray("options") { add("a"); add("b") }
                    put("answer", 9); put("explanation", "e") // answer out of range
                },
            )
        }
        assertEquals(3, Seeder.validateBank(broken).size)
        assertFailsWith<SeedException> { Seeder.seed(db, broken) }
    }

    @Test
    fun `shipped json round trips`() {
        val bank = Seeder.parse(Seeder.loadBankText())
        assertEquals(1, bank["version"]!!.jsonPrimitive.int)
    }
}

/** Rewrites the questions array of a bank, for tests that need a damaged one. */
private fun JsonObject.edit(transform: (List<JsonElement>) -> List<JsonElement>): JsonObject =
    JsonObject(this + ("questions" to JsonArray(transform(this["questions"]!!.jsonArray))))

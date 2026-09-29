package quizarena.server

import kotlinx.serialization.json.*
import quizarena.shared.Rules
import java.sql.Connection

/**
 * Loads the question bank (data/questions.json) into the database.
 *
 * Idempotent sync, all-or-nothing:
 *  - new topics/questions are inserted, changed ones updated
 *  - questions missing from the file are deactivated (never deleted: past
 *    attempts still reference them)
 *  - a question that has already been played keeps its options fixed; changing
 *    them is rejected, because history would silently change meaning. Give the
 *    changed question a new id instead.
 *
 * The bank is validated as raw JSON rather than parsed into data classes, so a
 * malformed file reports every problem at once instead of dying on the first.
 */
class SeedException(message: String) : RuntimeException(message)

data class SeedCounts(
    var inserted: Int = 0,
    var updated: Int = 0,
    var unchanged: Int = 0,
    var deactivated: Int = 0,
) {
    override fun toString() =
        "inserted: $inserted, updated: $updated, unchanged: $unchanged, deactivated: $deactivated"
}

internal data class BankTopic(val id: String, val name: String, val icon: String, val description: String)

internal data class BankQuestion(
    val id: String,
    val topic: String,
    val difficulty: String,
    val prompt: String,
    val code: String?,
    val explanation: String,
    val options: List<String>,
    val answer: Int,
)

internal data class Bank(val topics: List<BankTopic>, val questions: List<BankQuestion>)

object Seeder {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadBankText(): String = Db.resourceText("/questions.json")

    fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text).jsonObject
    } catch (t: Throwable) {
        throw SeedException("Question bank is not a JSON object: ${t.message}")
    }

    /** Returns a list of problems (empty when the file is valid). */
    fun validateBank(bank: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        val topics = bank["topics"] as? JsonArray
        val questions = bank["questions"] as? JsonArray
        if (topics == null || questions == null) {
            return listOf("file must be an object with 'topics' and 'questions' arrays")
        }

        val topicIds = mutableSetOf<String>()
        for (element in topics) {
            val topic = element as? JsonObject
            val id = topic?.str("id")
            val name = topic?.str("name")
            when {
                topic == null || id.isNullOrBlank() || name.isNullOrBlank() ->
                    problems.add("topic $element: needs id and name")
                id in topicIds -> problems.add("topic $id: duplicate id")
                else -> topicIds.add(id)
            }
        }

        val seen = mutableSetOf<String>()
        questions.forEachIndexed { index, element ->
            val q = element as? JsonObject
            val label = q?.str("id") ?: "#$index"
            if (q == null) {
                problems.add("question $label: not an object")
                return@forEachIndexed
            }
            val issues = mutableListOf<String>()
            val id = q.str("id")
            if (id.isNullOrBlank()) issues.add("missing id") else if (id in seen) issues.add("duplicate id")
            val topic = q.str("topic")
            if (topic !in topicIds) issues.add("unknown topic ${topic?.let { "'$it'" }}")
            val difficulty = q.str("difficulty")
            if (difficulty !in Rules.DIFFICULTIES) issues.add("invalid difficulty ${difficulty?.let { "'$it'" }}")
            if (q.str("question").isNullOrBlank()) issues.add("missing question text")
            if (q.str("explanation").isNullOrBlank()) issues.add("missing explanation")
            val code = q["code"]
            if (code != null && code !is JsonNull && code.stringOrNull() == null) issues.add("code must be a string")

            val options = (q["options"] as? JsonArray)?.map { it.stringOrNull() }
            if (options == null || options.size !in 2..6 || options.any { it.isNullOrBlank() }) {
                issues.add("options must be 2-6 non-empty strings")
            } else {
                if (options.map { it!!.trim().lowercase() }.toSet().size != options.size) {
                    issues.add("options must be unique")
                }
                val answer = q["answer"]?.intOrNull()
                if (answer == null || answer !in options.indices) issues.add("answer must be a valid option index")
            }

            if (issues.isNotEmpty()) problems.add("question $label: ${issues.joinToString("; ")}")
            else if (id != null) seen.add(id)
        }
        return problems
    }

    internal fun toBank(bank: JsonObject): Bank = Bank(
        topics = bank["topics"]!!.jsonArray.map {
            val t = it.jsonObject
            BankTopic(t.str("id")!!, t.str("name")!!, t.str("icon") ?: "", t.str("description") ?: "")
        },
        questions = bank["questions"]!!.jsonArray.map {
            val q = it.jsonObject
            BankQuestion(
                id = q.str("id")!!,
                topic = q.str("topic")!!,
                difficulty = q.str("difficulty")!!,
                prompt = q.str("question")!!,
                code = q.str("code")?.takeIf { c -> c.isNotEmpty() },
                explanation = q.str("explanation")!!,
                options = q["options"]!!.jsonArray.map { o -> o.jsonPrimitive.content },
                answer = q["answer"]!!.jsonPrimitive.int,
            )
        },
    )

    /** Syncs the bank into the database. Returns counts of what changed. */
    fun seed(db: Db, bankJson: JsonObject, nowMs: Long = System.currentTimeMillis()): SeedCounts {
        val problems = validateBank(bankJson)
        if (problems.isNotEmpty()) {
            throw SeedException("Question bank is invalid:\n  " + problems.joinToString("\n  "))
        }
        val bank = toBank(bankJson)
        val counts = SeedCounts()

        db.transaction { conn ->
            bank.topics.forEachIndexed { position, topic ->
                conn.update(
                    """INSERT INTO topics (id, name, icon, description, position) VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT (id) DO UPDATE SET name = excluded.name, icon = excluded.icon,
                         description = excluded.description, position = excluded.position""",
                    topic.id, topic.name, topic.icon, topic.description, position,
                )
            }

            bank.questions.forEachIndexed { position, q ->
                when (upsertQuestion(conn, q, position, nowMs)) {
                    "inserted" -> counts.inserted++
                    "updated" -> counts.updated++
                    else -> counts.unchanged++
                }
            }

            val fileIds = bank.questions.map { it.id }
            counts.deactivated = conn.update(
                "UPDATE questions SET is_active = 0, updated_at = ? WHERE is_active = 1 " +
                    "AND id NOT IN (${List(fileIds.size) { "?" }.joinToString(",")})",
                nowMs, *fileIds.toTypedArray(),
            )
        }
        return counts
    }

    private fun upsertQuestion(conn: Connection, q: BankQuestion, position: Int, nowMs: Long): String {
        val wantedOptions = q.options.mapIndexed { i, text -> text to (i == q.answer) }

        val existing = conn.queryOne(
            "SELECT topic_id, difficulty, prompt, code, explanation, position, is_active FROM questions WHERE id = ?",
            q.id,
        ) { rs ->
            listOf(
                rs.getString("topic_id"), rs.getString("difficulty"), rs.getString("prompt"),
                rs.getString("code"), rs.getString("explanation"), rs.getInt("position"),
            ) to rs.bool("is_active")
        }

        if (existing == null) {
            conn.update(
                """INSERT INTO questions (id, topic_id, difficulty, prompt, code, explanation, position, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                q.id, q.topic, q.difficulty, q.prompt, q.code, q.explanation, position, nowMs,
            )
            insertOptions(conn, q.id, wantedOptions)
            return "inserted"
        }

        val currentOptions = conn.query(
            "SELECT text, is_correct FROM question_options WHERE question_id = ? ORDER BY position", q.id,
        ) { rs -> rs.getString("text") to rs.bool("is_correct") }

        val wantedFields = listOf<Any?>(q.topic, q.difficulty, q.prompt, q.code, q.explanation, position)
        val fieldsChanged = existing.first != wantedFields || !existing.second
        val optionsChanged = currentOptions != wantedOptions

        if (!fieldsChanged && !optionsChanged) return "unchanged"

        if (optionsChanged) {
            val played = conn.queryOne("SELECT 1 FROM attempt_questions WHERE question_id = ? LIMIT 1", q.id) { true }
            if (played == true) {
                throw SeedException(
                    "question ${q.id}: its options changed but it has already been played. " +
                        "Give the edited question a new id (the old one will be deactivated).",
                )
            }
            conn.update("DELETE FROM question_options WHERE question_id = ?", q.id)
            insertOptions(conn, q.id, wantedOptions)
        }

        conn.update(
            """UPDATE questions SET topic_id = ?, difficulty = ?, prompt = ?, code = ?, explanation = ?,
                 position = ?, is_active = 1, updated_at = ? WHERE id = ?""",
            q.topic, q.difficulty, q.prompt, q.code, q.explanation, position, nowMs, q.id,
        )
        return "updated"
    }

    private fun insertOptions(conn: Connection, questionId: String, options: List<Pair<String, Boolean>>) {
        options.forEachIndexed { i, (text, correct) ->
            conn.update(
                "INSERT INTO question_options (question_id, position, text, is_correct) VALUES (?, ?, ?, ?)",
                questionId, i, text, if (correct) 1 else 0,
            )
        }
    }
}

// --------------------------------------------------------- JSON helpers

private fun JsonObject.str(key: String): String? = this[key]?.stringOrNull()

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.intOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

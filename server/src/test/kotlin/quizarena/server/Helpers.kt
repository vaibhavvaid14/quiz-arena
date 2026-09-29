package quizarena.server

import kotlinx.serialization.json.*
import quizarena.shared.AttemptState
import quizarena.shared.PlayerIdentity
import kotlin.random.Random

/** Shared fixtures: a small question bank, a controllable clock, fresh databases. */

private val TOPICS = listOf(
    Triple("alpha", "Alpha", "First topic"),
    Triple("beta", "Beta", "Second topic"),
)

private data class Q(val id: String, val topic: String, val difficulty: String, val answer: Int)

private val QUESTIONS = listOf(
    Q("a-e1", "alpha", "easy", 0),
    Q("a-e2", "alpha", "easy", 1),
    Q("a-m1", "alpha", "medium", 2),
    Q("a-h1", "alpha", "hard", 3),
    Q("b-e1", "beta", "easy", 1),
    Q("b-m1", "beta", "medium", 0),
    Q("b-h1", "beta", "hard", 2),
    Q("b-h2", "beta", "hard", 3),
)

val BANK_QUESTION_IDS = QUESTIONS.map { it.id }

fun bank(questions: List<String> = BANK_QUESTION_IDS): JsonObject = buildJsonObject {
    put("version", 1)
    putJsonArray("topics") {
        TOPICS.forEach { (id, name, description) ->
            addJsonObject {
                put("id", id)
                put("name", name)
                put("icon", name.first().toString())
                put("description", description)
            }
        }
    }
    putJsonArray("questions") {
        QUESTIONS.filter { it.id in questions }.forEach { q ->
            addJsonObject {
                put("id", q.id)
                put("topic", q.topic)
                put("difficulty", q.difficulty)
                put("question", "Question ${q.id}?")
                putJsonArray("options") { listOf("zero", "one", "two", "three").forEach { add(it) } }
                put("answer", q.answer)
                put("explanation", "Because ${q.answer}.")
            }
        }
    }
}

/** A clock the tests drive by hand, so timer behaviour is deterministic. */
class FakeClock(var now: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

fun freshDb(): Db = Db.connect(Db.MEMORY).also {
    Db.migrate(it)
    Seeder.seed(it, bank(), nowMs = 1)
}

class Fixture(seed: Int = 7) {
    val db: Db = freshDb()
    val clock = FakeClock()
    val service = QuizService(db, clock, Random(seed))
    val created = service.createPlayer(quizarena.shared.CreatePlayerRequest("Sam"))
    val player = PlayerIdentity(created.id, created.name)

    fun newPlayer(name: String): PlayerIdentity =
        service.createPlayer(quizarena.shared.CreatePlayerRequest(name)).let { PlayerIdentity(it.id, it.name) }

    /** The correct option id for the current question (tests may peek; clients may not). */
    fun correctOption(state: AttemptState): Long = db.read { conn ->
        conn.queryOne(
            "SELECT id FROM question_options WHERE question_id = ? AND is_correct = 1",
            state.current!!.question.id,
        ) { it.getLong("id") }!!
    }

    fun wrongOption(state: AttemptState): Long {
        val right = correctOption(state)
        return state.current!!.question.options.first { it.id != right }.id
    }

    fun start(
        topics: List<String> = listOf("alpha", "beta"),
        difficulty: String = "mixed",
        count: Int = 5,
        timerMode: String = "off",
        secondsPerQuestion: Int = 30,
        shuffle: Boolean = true,
        negativeMarking: Boolean = false,
        questionIds: List<String>? = null,
    ): AttemptState = service.createAttempt(
        player,
        config(topics, difficulty, count, timerMode, secondsPerQuestion, shuffle, negativeMarking, questionIds),
    )

    fun answer(state: AttemptState, right: Boolean = true, afterMs: Long = 0): AttemptState {
        clock.advance(afterMs)
        val option = if (right) correctOption(state) else wrongOption(state)
        return service.answer(player, state.id, quizarena.shared.AnswerRequest(state.currentIndex, option))
    }

    fun next(state: AttemptState): AttemptState =
        service.next(player, state.id, quizarena.shared.NextRequest(state.currentIndex))
}

fun config(
    topics: List<String> = listOf("alpha", "beta"),
    difficulty: String = "mixed",
    count: Int = 5,
    timerMode: String = "off",
    secondsPerQuestion: Int = 30,
    shuffle: Boolean = true,
    negativeMarking: Boolean = false,
    questionIds: List<String>? = null,
) = quizarena.shared.CreateAttemptRequest(
    topics = topics,
    difficulty = difficulty,
    count = count,
    timerMode = timerMode,
    secondsPerQuestion = secondsPerQuestion,
    shuffle = shuffle,
    negativeMarking = negativeMarking,
    questionIds = questionIds,
)

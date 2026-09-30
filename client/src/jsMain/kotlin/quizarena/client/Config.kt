package quizarena.client

/**
 * Front-end constants. Scoring rules are NOT here: the server owns them and
 * sends them with the catalog (GET /api/catalog), so they cannot drift apart.
 * The ones that are shared outright live in `quizarena.shared.Rules`.
 */
object Config {
    val DIFFICULTIES = listOf("easy", "medium", "hard")
    val DIFFICULTY_FILTERS = listOf("mixed") + DIFFICULTIES
    val DIFFICULTY_LABELS = mapOf(
        "mixed" to "Mixed", "easy" to "Easy", "medium" to "Medium", "hard" to "Hard",
    )

    val QUESTION_COUNT_OPTIONS = listOf(5, 10, 15, 20)
    val SECONDS_PER_QUESTION_OPTIONS = listOf(10, 15, 20, 30, 45, 60)

    /** Remaining-time fraction below which the timer takes its warning / danger look. */
    const val TIMER_WARNING_FRACTION = 0.5
    const val TIMER_DANGER_FRACTION = 0.2

    const val STORAGE_NAMESPACE = "quizapp"
    const val STORAGE_SCHEMA_VERSION = 2
    const val LEADERBOARD_SIZE = 20
}

/** Countdown behaviour. */
object TimerModes {
    const val QUESTION = "question" // countdown restarts on every question
    const val SESSION = "session" // one countdown for the whole quiz
    const val OFF = "off" // untimed (time is still measured for stats)
}

object ItemStatus {
    const val PENDING = "pending"
    const val CORRECT = "correct"
    const val WRONG = "wrong"
    const val TIMEOUT = "timeout"
    const val SKIPPED = "skipped"
}

object EndReasons {
    const val COMPLETED = "completed"
    const val TIME_UP = "time-up"
    const val QUIT = "quit"
}

/** The setup screen's working state, before it becomes a CreateAttemptRequest. */
data class QuizSetup(
    var topics: List<String> = emptyList(),
    var difficulty: String = "mixed",
    var count: Int = 10,
    var timerMode: String = TimerModes.QUESTION,
    var secondsPerQuestion: Int = 30,
    var shuffle: Boolean = true,
    var negativeMarking: Boolean = false,
)

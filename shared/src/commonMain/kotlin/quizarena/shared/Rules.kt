package quizarena.shared

import kotlinx.serialization.Serializable

/**
 * Game rules: the single source of truth for scoring and timing.
 *
 * This file compiles for both the JVM server and the browser client, so unlike
 * the previous Python/JavaScript split there is no chance of the two drifting.
 * The server still publishes [publicRules] via GET /api/catalog, because the
 * values are also part of the public API contract.
 */
object Rules {
    val DIFFICULTIES = listOf("easy", "medium", "hard")
    val DIFFICULTY_FILTERS = listOf("mixed") + DIFFICULTIES
    val DIFFICULTY_POINTS = mapOf("easy" to 10, "medium" to 20, "hard" to 30)

    val TIMER_MODES = listOf("question", "session", "off")

    /** Up to +50% of base for an instant answer (per-question timer only). */
    const val SPEED_BONUS_RATIO = 0.5

    /** -25% of base for a wrong answer when negative marking is on. */
    const val NEGATIVE_MARK_RATIO = 0.25

    const val MIN_QUESTIONS = 1
    const val MAX_QUESTIONS = 50
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 600
    const val PLAYER_NAME_MAX = 30

    /** Network/render latency we do not charge to the player when timing an answer. */
    const val LATENCY_ALLOWANCE_MS = 300L

    /** An answer arriving this long after the deadline still counts (slow networks). */
    const val DEADLINE_GRACE_MS = 1500L

    /**
     * Leaderboard eligibility: finished (not quit) attempts of at least this many
     * questions that were not "retry missed" runs.
     */
    const val LEADERBOARD_MIN_QUESTIONS = 5

    val GRADE_BANDS = listOf(
        GradeBand(90, "A", "Outstanding"),
        GradeBand(75, "B", "Great work"),
        GradeBand(60, "C", "Good effort"),
        GradeBand(40, "D", "Keep practising"),
        GradeBand(0, "F", "Time to review"),
    )

    fun gradeFor(percentage: Int): Grade =
        GRADE_BANDS.firstOrNull { percentage >= it.min }
            ?.let { Grade(it.grade, it.label) }
            ?: Grade("F", GRADE_BANDS.last().label)

    /** Points for one resolved question. */
    fun scoreAnswer(
        status: String,
        difficulty: String,
        timerMode: String,
        negativeMarking: Boolean,
        remainingFraction: Double = 0.0,
    ): Int {
        val base = DIFFICULTY_POINTS.getValue(difficulty)
        if (status == "correct") {
            if (timerMode != "question") return base
            val fraction = remainingFraction.coerceIn(0.0, 1.0)
            return base + roundHalfUp(base * SPEED_BONUS_RATIO * fraction)
        }
        if (status == "wrong" && negativeMarking) {
            return -roundHalfUp(base * NEGATIVE_MARK_RATIO)
        }
        return 0
    }

    fun maxPointsFor(difficulty: String, timerMode: String): Int {
        val base = DIFFICULTY_POINTS.getValue(difficulty)
        return if (timerMode == "question") base + roundHalfUp(base * SPEED_BONUS_RATIO) else base
    }

    /**
     * Half-up rounding, away from zero. Both Python's `round()` and Kotlin's
     * `Math.round()` would disagree with this on a tie (banker's rounding, and
     * -2.5 -> -2 respectively); scores want 2.5 -> 3 and -2.5 -> -3.
     */
    fun roundHalfUp(value: Double): Int =
        if (value >= 0) (value + 0.5).toInt() else -((-value + 0.5).toInt())

    fun publicRules(): PublicRules = PublicRules(
        difficultyPoints = DIFFICULTY_POINTS,
        speedBonusRatio = SPEED_BONUS_RATIO,
        negativeMarkRatio = NEGATIVE_MARK_RATIO,
        minQuestions = MIN_QUESTIONS,
        maxQuestions = MAX_QUESTIONS,
        minSeconds = MIN_SECONDS,
        maxSeconds = MAX_SECONDS,
        playerNameMax = PLAYER_NAME_MAX,
        leaderboardMinQuestions = LEADERBOARD_MIN_QUESTIONS,
        gradeBands = GRADE_BANDS,
    )
}

@Serializable
data class GradeBand(val min: Int, val grade: String, val label: String)

@Serializable
data class Grade(val grade: String, val label: String)

@Serializable
data class PublicRules(
    val difficultyPoints: Map<String, Int>,
    val speedBonusRatio: Double,
    val negativeMarkRatio: Double,
    val minQuestions: Int,
    val maxQuestions: Int,
    val minSeconds: Int,
    val maxSeconds: Int,
    val playerNameMax: Int,
    val leaderboardMinQuestions: Int,
    val gradeBands: List<GradeBand>,
)

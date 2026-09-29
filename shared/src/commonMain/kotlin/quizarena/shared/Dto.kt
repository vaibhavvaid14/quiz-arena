package quizarena.shared

import kotlinx.serialization.Serializable

/**
 * The wire format of the API, compiled for both the JVM server and the browser
 * client. A field renamed here fails to compile on both sides at once, which is
 * the main reason this project is worth having as one Kotlin codebase rather
 * than a server and a client that merely agree by convention.
 *
 * Nulls are encoded rather than omitted, matching the contract the browser
 * already expects (`"current": null` on a finished quiz, and so on).
 */

// ------------------------------------------------------------------ catalog

@Serializable
data class DifficultyCounts(val easy: Int, val medium: Int, val hard: Int)

@Serializable
data class TopicSummary(
    val id: String,
    val name: String,
    val icon: String? = null,
    val description: String? = null,
    val counts: DifficultyCounts,
)

@Serializable
data class CatalogResponse(val topics: List<TopicSummary>, val rules: PublicRules)

// ------------------------------------------------------------------ players

@Serializable
data class CreatePlayerRequest(val name: String? = null)

@Serializable
data class PlayerCreated(val id: Long, val name: String, val key: String)

@Serializable
data class PlayerIdentity(val id: Long, val name: String)

// ------------------------------------------------------------------ attempts

@Serializable
data class QuizConfig(
    val topics: List<String>,
    val difficulty: String,
    val count: Int,
    val timerMode: String,
    val secondsPerQuestion: Int,
    val shuffle: Boolean,
    val negativeMarking: Boolean,
    val isRetry: Boolean = false,
)

@Serializable
data class CreateAttemptRequest(
    val topics: List<String>? = null,
    val difficulty: String? = null,
    val count: Int? = null,
    val timerMode: String? = null,
    val secondsPerQuestion: Int? = null,
    val shuffle: Boolean = false,
    val negativeMarking: Boolean = false,
    /** Present for a "retry missed" run: pins the quiz to these questions. */
    val questionIds: List<String>? = null,
)

@Serializable
data class AnswerRequest(val position: Int? = null, val optionId: Long? = null)

@Serializable
data class NextRequest(val position: Int? = null)

@Serializable
data class LiveStats(val points: Int, val correct: Int, val answered: Int, val streak: Int)

@Serializable
data class ClockState(
    val questionLimitMs: Long?,
    val sessionLimitMs: Long?,
    val sessionElapsedMs: Long,
    val questionElapsedMs: Long,
)

@Serializable
data class OptionView(val id: Long, val text: String)

@Serializable
data class QuestionView(
    val id: String,
    val topic: String,
    val difficulty: String,
    val prompt: String,
    val code: String? = null,
    val options: List<OptionView>,
)

/**
 * The question being played. [correctOptionId] and [explanation] stay null until
 * the question is resolved: the answer key never reaches the browser early.
 */
@Serializable
data class CurrentItem(
    val position: Int,
    val status: String,
    val points: Int,
    val isLast: Boolean,
    val question: QuestionView,
    val selectedOptionId: Long? = null,
    val correctOptionId: Long? = null,
    val explanation: String? = null,
)

@Serializable
data class AttemptState(
    val id: String,
    val status: String,
    val endReason: String? = null,
    val config: QuizConfig,
    val currentIndex: Int,
    val total: Int,
    val live: LiveStats,
    val clock: ClockState,
    val current: CurrentItem? = null,
)

// ------------------------------------------------------------------ results

@Serializable
data class ReviewOption(val text: String, val isCorrect: Boolean, val isSelected: Boolean)

@Serializable
data class ReviewItem(
    val number: Int,
    val questionId: String,
    val topic: String,
    val difficulty: String,
    val question: String,
    val code: String? = null,
    val explanation: String,
    val status: String,
    val points: Int,
    val timeSpentMs: Long,
    val options: List<ReviewOption>,
)

@Serializable
data class TopicBreakdown(
    val key: String,
    val label: String,
    val icon: String? = null,
    val correct: Int,
    val total: Int,
    val percentage: Int,
)

@Serializable
data class DifficultyBreakdown(
    val key: String,
    val label: String,
    val correct: Int,
    val total: Int,
    val percentage: Int,
)

@Serializable
data class ResultsSummary(
    val id: String,
    val config: QuizConfig,
    val endReason: String? = null,
    val finishedAt: Long? = null,
    val total: Int,
    val answered: Int,
    val correct: Int,
    val wrong: Int,
    val timedOut: Int,
    val skipped: Int,
    val percentage: Int,
    val accuracy: Int,
    val points: Int,
    val maxPoints: Int,
    val grade: Grade,
    val bestStreak: Int,
    val durationMs: Long,
    val averageTimeMs: Long,
    val byTopic: List<TopicBreakdown>,
    val byDifficulty: List<DifficultyBreakdown>,
    val missedQuestionIds: List<String>,
    val review: List<ReviewItem>? = null,
)

// ------------------------------------------------------------------ history

@Serializable
data class HistoryStats(
    val attempts: Int,
    val questions: Int,
    val correct: Int,
    val averagePercentage: Int,
    val bestPercentage: Int,
    val overallAccuracy: Int,
)

/** Like [TopicBreakdown] but also counts how many quizzes touched the topic. */
@Serializable
data class TopicMastery(
    val key: String,
    val label: String,
    val icon: String? = null,
    val correct: Int,
    val total: Int,
    val attempts: Int,
    val percentage: Int,
)

@Serializable
data class HistoryAttempt(
    val id: String,
    val finishedAt: Long? = null,
    val endReason: String? = null,
    val config: QuizConfig,
    val total: Int,
    val correct: Int,
    val percentage: Int,
    val points: Int,
    val maxPoints: Int,
    val grade: String,
    val durationMs: Long,
)

@Serializable
data class HistoryPlayer(val name: String)

@Serializable
data class HistoryResponse(
    val player: HistoryPlayer,
    val stats: HistoryStats,
    val byTopic: List<TopicMastery>,
    val attempts: List<HistoryAttempt>,
)

@Serializable
data class DeletedCount(val deleted: Int)

// -------------------------------------------------------------- leaderboard

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val name: String,
    val points: Int,
    val percentage: Int,
    val questionCount: Int,
    val difficulty: String,
    val timerMode: String,
    val finishedAt: Long?,
    val attempts: Int,
)

@Serializable
data class LeaderboardResponse(
    val entries: List<LeaderboardEntry>,
    val you: LeaderboardEntry? = null,
    val players: Int,
    val minQuestions: Int,
)

// ------------------------------------------------------------------- errors

@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class HealthResponse(val ok: Boolean, val time: Long)

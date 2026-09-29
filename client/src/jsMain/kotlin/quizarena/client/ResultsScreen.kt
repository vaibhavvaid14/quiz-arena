package quizarena.client

import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import quizarena.shared.CreateAttemptRequest
import quizarena.shared.ResultsSummary
import quizarena.shared.ReviewItem

/**
 * Results screen: score hero, stat tiles, topic/difficulty breakdown and a full
 * question-by-question review, plus retake actions. Data comes from
 * GET /api/attempts/{id}/results (scored by the server).
 */

private val END_REASON_NOTES = mapOf(
    EndReasons.TIME_UP to
        "Time ran out, so the quiz was submitted automatically. Questions you didn't reach are marked as skipped.",
    EndReasons.QUIT to
        "You ended this quiz early. Unanswered questions are marked as skipped, and it does not count for the leaderboard.",
)

private val REVIEW_FILTERS = listOf("all" to "All", "missed" to "Missed", "correct" to "Correct")

fun renderResultsScreen(root: HTMLElement, ctx: AppContext, params: ScreenParams): (() -> Unit) {
    val args = params as? ScreenParams.Results
        ?: error("The results screen needs an attempt id")
    val screen = h("section", "screen screen-results") { +loadingState("Loading your results…") }
    root.appendChild(screen)
    var alive = true

    ctx.scope.launchCatching(
        block = {
            val summary = ctx.api.results(args.attemptId)
            if (!alive) return@launchCatching
            screen.replaceChildren(buildResults(summary, ctx, args.fresh))
            if (args.fresh) {
                ctx.announce(
                    "Quiz complete. You scored ${summary.percentage} percent, grade ${summary.grade.grade}.",
                )
            }
        },
        onError = { error ->
            if (!alive) return@launchCatching
            screen.replaceChildren(
                emptyState(
                    "⚠️",
                    "Couldn't load these results",
                    error.message ?: "Something went wrong.",
                    button("Back to history") { ctx.navigate(Screen.HISTORY) },
                ),
            )
        },
    )

    return { alive = false }
}

private fun buildResults(summary: ResultsSummary, ctx: AppContext, fresh: Boolean): List<HTMLElement> {
    val name = ctx.player?.name

    /* ---------- hero ---------- */

    val hero = h("header", "card results-hero") {
        +scoreRing(summary.percentage)
        +h("div", "results-hero-text") {
            +t(
                "p", "eyebrow",
                if (fresh) "Quiz complete" else summary.finishedAt?.let { formatDate(it) } ?: "",
            )
            +t(
                "h1",
                text = if (fresh) "${summary.grade.label}${name?.let { ", $it" } ?: ""}!" else "Quiz review",
            )
            +h("p", "results-line") {
                +t("span", "grade-badge", "Grade ${summary.grade.grade}")
                +t("span", text = "${summary.correct} of ${summary.total} correct")
                +t("span", text = "${summary.points} / ${summary.maxPoints} pts")
            }
            END_REASON_NOTES[summary.endReason]?.let { +t("p", "notice", it) }
        }
    }

    /* ---------- actions ---------- */

    val config = summary.config
    val missed = summary.missedQuestionIds
    var starting = false

    fun start(request: CreateAttemptRequest) {
        if (starting) return
        starting = true
        ctx.scope.launchCatching(
            block = {
                try {
                    ctx.startQuiz(request)
                } finally {
                    starting = false
                }
            },
            onError = { error ->
                starting = false
                ctx.handleError(error)
            },
        )
    }

    fun requestFrom(count: Int = config.count, questionIds: List<String>? = null) = CreateAttemptRequest(
        topics = config.topics,
        difficulty = config.difficulty,
        count = count,
        timerMode = config.timerMode,
        secondsPerQuestion = config.secondsPerQuestion,
        shuffle = config.shuffle,
        negativeMarking = config.negativeMarking,
        questionIds = questionIds,
    )

    val actions = h("div", "results-actions") {
        +h("button", "btn btn-primary") {
            attr("type", "button")
            data("action", "retake")
            +"↻ Retake quiz"
            on("click") { start(requestFrom()) }
        }
        if (missed.isNotEmpty()) {
            +h("button", "btn btn-secondary") {
                attr("type", "button")
                data("action", "retry-missed")
                +"Retry ${missed.size} missed"
                on("click") { start(requestFrom(count = missed.size, questionIds = missed)) }
            }
        }
        +button("New quiz", "btn btn-ghost") { ctx.navigate(Screen.SETUP) }
        +button("Leaderboard", "btn btn-ghost") { ctx.navigate(Screen.LEADERBOARD) }
    }

    /* ---------- stat tiles ---------- */

    val tiles = h("section", "stat-grid") {
        attr("aria-label", "Summary statistics")
        +statTile("Correct", summary.correct.toString(), "${summary.accuracy}% of answered")
        +statTile("Incorrect", summary.wrong.toString())
        +statTile("Timed out", summary.timedOut.toString())
        +statTile("Skipped", summary.skipped.toString())
        +statTile("Best streak", summary.bestStreak.toString())
        +statTile(
            "Total time",
            formatDuration(summary.durationMs),
            "${formatDuration(summary.averageTimeMs)} per question",
        )
    }

    /* ---------- breakdown ---------- */

    val breakdown = h("section", "card breakdown") {
        +t("h2", "card-title", "Performance breakdown")
        +h("div", "breakdown-grid") {
            +accuracyBars(
                summary.byTopic.map { BarRow(it.label, it.correct, it.total, it.percentage, it.icon) },
                caption = "Accuracy by topic",
            )
            +accuracyBars(
                summary.byDifficulty.map { BarRow(it.label, it.correct, it.total, it.percentage) },
                caption = "Accuracy by difficulty",
            )
        }
    }

    /* ---------- review ---------- */

    val reviewCards = summary.review.orEmpty().map { it.status to reviewCard(it, ctx) }
    val reviewList = h("ol", "review-list") { +reviewCards.map { it.second } }
    val reviewEmpty = h("p", "hint review-empty") { node.hidden = true }

    fun applyFilter(filter: String) {
        var visible = 0
        for ((status, el) in reviewCards) {
            val show = when (filter) {
                "all" -> true
                "correct" -> status == ItemStatus.CORRECT
                else -> status != ItemStatus.CORRECT
            }
            el.hidden = !show
            if (show) visible++
        }
        reviewEmpty.hidden = visible > 0
        reviewEmpty.textContent = if (filter == "missed") {
            "Nothing missed — every answer was correct. 🎉"
        } else {
            "No correct answers this time."
        }
    }

    val filterGroup = h("div", "segmented segmented-small") {
        attr("role", "radiogroup")
        attr("aria-label", "Filter questions")
        REVIEW_FILTERS.forEach { (value, label) ->
            +h("label", "segment") {
                +h("input") {
                    val input = node as HTMLInputElement
                    input.type = "radio"
                    input.name = "review-filter"
                    input.value = value
                    input.checked = value == "all"
                    on("change") { applyFilter(value) }
                }
                +t("span", text = label)
            }
        }
    }

    val review = h("section", "review") {
        +h("div", "review-header") {
            +t("h2", text = "Review your answers")
            +filterGroup
        }
        +reviewList
        +reviewEmpty
    }

    return listOf(hero, actions, tiles, breakdown, review)
}

private fun reviewCard(entry: ReviewItem, ctx: AppContext): HTMLElement {
    val topic = ctx.getTopic(entry.topic)
    return h("li", "card review-card status-${entry.status}") {
        +h("div", "review-card-head") {
            +t("span", "review-number", "Q${entry.number}")
            +chip("${topic?.icon ?: ""} ${topic?.name ?: entry.topic}".trim())
            +difficultyChip(entry.difficulty)
            +h("span", "review-spacer")
            +statusPill(entry.status)
        }
        +h("h3", "review-question") { rich(entry.question) }
        entry.code?.let { code ->
            +h("pre", "code-block") { +t("code", text = code) }
        }
        +h("ul", "review-options") {
            entry.options.forEach { option ->
                val classes = listOfNotNull(
                    "review-option",
                    "is-correct".takeIf { option.isCorrect },
                    "is-wrong".takeIf { option.isSelected && !option.isCorrect },
                ).joinToString(" ")
                +h("li", classes) {
                    +t("span", "review-option-text", option.text)
                    if (option.isCorrect) +t("span", "review-tag", "✓ Correct answer")
                    if (option.isSelected && !option.isCorrect) +t("span", "review-tag", "✗ Your answer")
                }
            }
        }
        +h("p", "review-explanation") {
            +t("strong", text = "Why: ")
            rich(entry.explanation)
        }
        +t(
            "p", "review-meta",
            "${formatDuration(entry.timeSpentMs)} · ${if (entry.points > 0) "+" else ""}${entry.points} pts",
        )
    }
}

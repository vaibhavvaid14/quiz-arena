package quizarena.client

import org.w3c.dom.HTMLElement
import quizarena.shared.HistoryAttempt
import quizarena.shared.HistoryResponse

/**
 * History screen: the current player's lifetime stats, topic mastery and past
 * attempts (each can be reopened for a full review). Data: GET /api/me/history.
 */
fun renderHistoryScreen(root: HTMLElement, ctx: AppContext, params: ScreenParams): (() -> Unit) {
    val screen = h("section", "screen screen-history")
    root.appendChild(screen)
    var alive = true

    fun playNow() = button("Start a quiz") { ctx.navigate(Screen.SETUP) }

    // Declared up front so header() can call load() and load() can call header().
    lateinit var load: () -> Unit

    fun header(history: HistoryResponse?): HTMLElement {
        val count = history?.stats?.attempts ?: 0
        return h("header", "screen-header screen-header-row") {
            +h("div") {
                +t("h1", text = history?.let { "${it.player.name}'s history" } ?: "Your history")
                +t("p", "lede", "Every finished quiz is saved in the quiz database.")
            }
            if (count > 0) {
                +h("button", "btn btn-ghost") {
                    attr("type", "button")
                    +"Clear history"
                    on("click") {
                        ctx.run {
                            val plural = if (count == 1) "" else "s"
                            val ok = confirmDialog(
                                title = "Clear all history?",
                                message = "This permanently deletes $count saved attempt$plural, " +
                                    "including their leaderboard scores.",
                                confirmText = "Clear history",
                                danger = true,
                            )
                            if (ok) {
                                ctx.api.clearHistory()
                                ctx.announce("History cleared.")
                                load()
                            }
                        }
                    }
                }
            }
        }
    }

    fun attemptRow(attempt: HistoryAttempt, allTopics: Int): HTMLElement {
        val config = attempt.config
        val topicNames = config.topics.map { ctx.getTopic(it)?.name ?: it }
        val topicsLabel = when {
            config.isRetry -> "Retry of missed questions"
            topicNames.size == allTopics -> "All topics"
            else -> topicNames.joinToString(", ")
        }
        val timer = when (config.timerMode) {
            TimerModes.OFF -> "Untimed"
            TimerModes.QUESTION -> "${config.secondsPerQuestion}s per question"
            else -> "${formatClock(config.secondsPerQuestion.toLong() * attempt.total * 1000)} total"
        }
        val difficulty = Config.DIFFICULTY_LABELS[config.difficulty] ?: config.difficulty
        val when_ = attempt.finishedAt?.let { formatDate(it) } ?: ""

        return h("tr") {
            +t("td", text = when_)
            +h("td") {
                +topicsLabel
                +t("span", "cell-sub", "$difficulty · $timer")
            }
            +t("td", "num", "${attempt.correct}/${attempt.total}")
            +h("td", "num") {
                +t("strong", text = "${attempt.percentage}%")
                +t("span", "cell-sub", "Grade ${attempt.grade}")
            }
            +t("td", "num", attempt.points.toString())
            +t("td", "num", formatDuration(attempt.durationMs))
            +h("td") {
                +h("button", "btn btn-small btn-ghost") {
                    attr("type", "button")
                    attr("aria-label", "Review quiz from $when_")
                    +"Review"
                    on("click") { ctx.navigate(Screen.RESULTS, ScreenParams.Results(attempt.id)) }
                }
            }
        }
    }

    fun render(history: HistoryResponse) {
        val stats = history.stats
        if (stats.attempts == 0) {
            screen.replaceChildren(
                header(history),
                emptyState(
                    "📭",
                    "No quizzes yet",
                    "Finish a quiz and your scores, streaks and topic mastery will show up here.",
                    playNow(),
                ),
            )
            return
        }

        val tiles = h("section", "stat-grid") {
            attr("aria-label", "Lifetime statistics")
            +statTile("Quizzes taken", stats.attempts.toString())
            +statTile("Average score", "${stats.averagePercentage}%")
            +statTile("Best score", "${stats.bestPercentage}%")
            +statTile(
                "Questions answered",
                stats.questions.toString(),
                "${stats.overallAccuracy}% correct overall",
            )
        }

        val mastery = h("section", "card") {
            +t("h2", "card-title", "Topic mastery")
            +accuracyBars(
                history.byTopic.map { BarRow(it.label, it.correct, it.total, it.percentage, it.icon) },
                caption = "Share of questions answered correctly, across all attempts",
            )
        }

        val allTopics = ctx.catalog?.topics?.size ?: 0
        val table = h("section", "card") {
            +t("h2", "card-title", "Past attempts")
            +h("div", "table-scroll") {
                attr("tabindex", "0")
                attr("role", "region")
                attr("aria-label", "Past attempts table")
                +h("table", "data-table") {
                    +h("thead") {
                        +h("tr") {
                            listOf("Date", "Quiz", "Correct", "Score", "Points", "Time", "")
                                .forEachIndexed { i, label ->
                                    +h("th", if (i in 2..5) "num" else "") {
                                        attr("scope", "col")
                                        +label
                                    }
                                }
                        }
                    }
                    +h("tbody") { +history.attempts.map { attemptRow(it, allTopics) } }
                }
            }
        }

        screen.replaceChildren(header(history), tiles, mastery, table)
    }

    load = {
        if (ctx.player == null) {
            screen.replaceChildren(
                header(null),
                emptyState(
                    "👋",
                    "Who is playing?",
                    "Enter your name on the setup screen and finish a quiz to build your history.",
                    playNow(),
                ),
            )
        } else {
            screen.replaceChildren(header(null), loadingState("Loading your history…"))
            ctx.scope.launchCatching(
                block = {
                    val history = ctx.api.history()
                    if (alive) render(history)
                },
                onError = { error ->
                    if (!alive) return@launchCatching
                    if (error is ApiError && error.status == 401) {
                        ctx.handleError(error)
                    } else {
                        screen.replaceChildren(
                            header(null),
                            emptyState(
                                "⚠️",
                                "Couldn't load your history",
                                error.message ?: "Something went wrong.",
                            ),
                        )
                    }
                },
            )
        }
    }

    load()
    return { alive = false }
}

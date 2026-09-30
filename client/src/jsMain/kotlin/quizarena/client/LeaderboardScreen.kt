package quizarena.client

import org.w3c.dom.HTMLElement
import quizarena.shared.LeaderboardResponse

/**
 * Leaderboard: every player's single best eligible quiz, ranked by points.
 * Data: GET /api/leaderboard (the eligibility rules live on the server).
 */

private val MEDALS = mapOf(1 to "🥇", 2 to "🥈", 3 to "🥉")

private val TIMER_LABELS = mapOf(
    TimerModes.QUESTION to "per question",
    TimerModes.SESSION to "whole quiz",
    TimerModes.OFF to "untimed",
)

fun renderLeaderboardScreen(root: HTMLElement, ctx: AppContext, params: ScreenParams): (() -> Unit) {
    val screen = h("section", "screen screen-leaderboard")
    root.appendChild(screen)
    var alive = true

    fun header(minQuestions: Int = 5) = h("header", "screen-header") {
        +t("h1", text = "Leaderboard")
        +t(
            "p", "lede",
            "Each player's best quiz, ranked by points. Counts finished quizzes of " +
                "$minQuestions+ questions; retries and quizzes ended early are left out.",
        )
    }

    fun rowFor(entry: quizarena.shared.LeaderboardEntry, me: String?): HTMLElement {
        val isMe = entry.name.equals(me, ignoreCase = true)
        return h("tr", if (isMe) "is-me" else "") {
            +h("td", "rank") {
                val medal = MEDALS[entry.rank]
                if (medal != null) {
                    +h("span") {
                        attr("aria-label", "Rank ${entry.rank}")
                        +medal
                    }
                } else {
                    +entry.rank.toString()
                }
            }
            +h("td") {
                +entry.name
                if (isMe) +t("span", "you-badge", "You")
                val plural = if (entry.attempts == 1) "" else "zes"
                +t("span", "cell-sub", "${entry.attempts} eligible qui${if (plural.isEmpty()) "z" else "z$plural"}")
            }
            +h("td", "num") { +t("strong", text = entry.points.toString()) }
            +t("td", "num", "${entry.percentage}%")
            +h("td") {
                +"${entry.questionCount} questions"
                val difficulty = Config.DIFFICULTY_LABELS[entry.difficulty] ?: entry.difficulty
                val timer = TIMER_LABELS[entry.timerMode] ?: entry.timerMode
                +t("span", "cell-sub", "$difficulty · $timer")
            }
            +t("td", text = entry.finishedAt?.let { formatDate(it) } ?: "")
        }
    }

    fun renderBoard(board: LeaderboardResponse) {
        if (board.entries.isEmpty()) {
            screen.replaceChildren(
                header(board.minQuestions),
                emptyState(
                    "🏆",
                    "No scores yet",
                    "Be the first on the board: finish a quiz of at least five questions.",
                    button("Start a quiz") { ctx.navigate(Screen.SETUP) },
                ),
            )
            return
        }

        val me = ctx.player?.name
        val rows = board.entries.map { rowFor(it, me) }

        // Outside the top list? Still tell the player where they stand.
        val inTable = board.you != null && board.entries.any { it.rank == board.you!!.rank }
        val yourRank: HTMLElement? = when {
            board.you != null && !inTable -> h("p", "your-rank") {
                +t("strong", text = "You're #${board.you!!.rank} of ${board.players}")
                +" — ${board.you!!.name}, best ${board.you!!.points} points (${board.you!!.percentage}%). Keep climbing!"
            }
            ctx.player != null && board.you == null -> t(
                "p", "your-rank",
                "Finish a quiz of ${board.minQuestions}+ questions to get ranked, ${ctx.player!!.name}.",
            )
            else -> null
        }

        val table = h("section", "card") {
            +h("div", "table-scroll") {
                attr("tabindex", "0")
                attr("role", "region")
                attr("aria-label", "Leaderboard table")
                +h("table", "data-table leaderboard-table") {
                    +h("thead") {
                        +h("tr") {
                            listOf("Rank", "Player", "Points", "Score", "Quiz", "Date")
                                .forEachIndexed { i, label ->
                                    +h("th", if (i == 2 || i == 3) "num" else "") {
                                        attr("scope", "col")
                                        +label
                                    }
                                }
                        }
                    }
                    +h("tbody") { +rows }
                }
            }
        }

        val children = listOfNotNull(header(board.minQuestions), yourRank, table)
        screen.replaceChildren(children)
    }

    fun load() {
        screen.replaceChildren(header(), loadingState("Loading the leaderboard…"))
        ctx.scope.launchCatching(
            block = {
                val board = ctx.api.leaderboard(Config.LEADERBOARD_SIZE)
                if (alive) renderBoard(board)
            },
            onError = { error ->
                if (!alive) return@launchCatching
                screen.replaceChildren(
                    header(),
                    emptyState(
                        "⚠️",
                        "Couldn't load the leaderboard",
                        error.message ?: "Something went wrong.",
                        button("Try again") { load() },
                    ),
                )
            },
        )
    }

    load()
    return { alive = false }
}

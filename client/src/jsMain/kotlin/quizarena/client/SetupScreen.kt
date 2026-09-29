package quizarena.client

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import quizarena.shared.AttemptState
import quizarena.shared.CreateAttemptRequest
import quizarena.shared.TopicSummary
import kotlin.math.roundToInt

/**
 * Setup screen: player name, topic selection, difficulty, length, timer mode and
 * scoring options. Also offers to resume an unfinished quiz.
 */

private data class TimerModeOption(val value: String, val label: String, val hint: String)

private val TIMER_MODE_OPTIONS = listOf(
    TimerModeOption(
        TimerModes.QUESTION, "Per question",
        "A fresh countdown for every question. Faster answers earn a speed bonus.",
    ),
    TimerModeOption(
        TimerModes.SESSION, "Whole quiz",
        "One countdown for the entire quiz. When it hits zero the quiz is submitted.",
    ),
    TimerModeOption(
        TimerModes.OFF, "No timer",
        "Take your time. Time spent is still recorded in your stats.",
    ),
)

/** Restores a saved setup, dropping anything the current catalog no longer offers. */
private fun restoreConfig(saved: StoredConfig?, topics: List<TopicSummary>): QuizSetup {
    val topicIds = topics.map { it.id }
    val defaults = QuizSetup()
    if (saved == null) return defaults.copy(topics = topicIds)
    return QuizSetup(
        topics = saved.topics.filter { it in topicIds },
        difficulty = saved.difficulty.takeIf { it in Config.DIFFICULTY_FILTERS } ?: defaults.difficulty,
        count = saved.count.takeIf { it in Config.QUESTION_COUNT_OPTIONS } ?: defaults.count,
        timerMode = saved.timerMode.takeIf { it in Rulesish.TIMER_MODES } ?: defaults.timerMode,
        secondsPerQuestion = saved.secondsPerQuestion.takeIf { it in Config.SECONDS_PER_QUESTION_OPTIONS }
            ?: defaults.secondsPerQuestion,
        shuffle = saved.shuffle,
        negativeMarking = saved.negativeMarking,
    )
}

private object Rulesish {
    val TIMER_MODES = listOf(quizarena.client.TimerModes.QUESTION, quizarena.client.TimerModes.SESSION, quizarena.client.TimerModes.OFF)
}

fun renderSetupScreen(root: HTMLElement, ctx: AppContext, params: ScreenParams): (() -> Unit) {
    val catalog = ctx.catalog ?: error("The setup screen needs a loaded catalog")
    val topics = catalog.topics
    val rules = catalog.rules
    val config = restoreConfig(ctx.storage.getPrefs().lastConfig, topics)
    var alive = true
    var activeAttempt: AttemptState? = null
    var busy = false

    lateinit var refresh: () -> Unit

    /* ---------- header, resume banner, quick stats ---------- */

    val header = h("header", "screen-header") {
        +t("h1", text = "Ready to test your knowledge?")
        +t(
            "p", "lede",
            "Pick your topics and rules, then see how you score. Every answer comes with an explanation.",
        )
    }
    val resumeSlot = h("div")
    val statsSlot = h("div")

    fun resume(attemptId: String) {
        ctx.run {
            val state = ctx.api.attempt(attemptId)
            if (state.status == "finished") {
                ctx.storage.clearActiveAttemptId()
                ctx.navigate(Screen.RESULTS, ScreenParams.Results(attemptId, fresh = true))
            } else {
                ctx.navigate(Screen.QUIZ, ScreenParams.Quiz(state))
            }
        }
    }

    fun bannerFor(attempt: AttemptState): HTMLElement {
        val topicNames = attempt.config.topics.mapNotNull { ctx.getTopic(it)?.name }
        val summary = "${attempt.live.answered} of ${attempt.total} answered · " +
            (topicNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "All topics")

        if (attempt.status == "finished") {
            // It ran out of time while the player was away; the server already scored it.
            return h("section", "banner") {
                attr("aria-label", "Quiz ended while you were away")
                +h("div", "banner-text") {
                    +t("strong", text = "Your last quiz ran out of time while you were away")
                    +t("span", text = summary)
                }
                +h("div", "banner-actions") {
                    +button("See results") {
                        ctx.storage.clearActiveAttemptId()
                        ctx.navigate(Screen.RESULTS, ScreenParams.Results(attempt.id))
                    }
                }
            }
        }

        return h("section", "banner") {
            attr("aria-label", "Unfinished quiz")
            +h("div", "banner-text") {
                +t("strong", text = "You have an unfinished quiz")
                +t("span", text = summary)
            }
            +h("div", "banner-actions") {
                +h("button", "btn btn-ghost") {
                    attr("type", "button")
                    +"Discard"
                    on("click") {
                        ctx.run {
                            val ok = confirmDialog(
                                title = "Discard unfinished quiz?",
                                message = "Your answers so far will be lost and this attempt " +
                                    "will not be saved to history.",
                                confirmText = "Discard",
                                danger = true,
                            )
                            if (ok) {
                                try {
                                    ctx.api.discard(attempt.id)
                                } catch (error: Throwable) {
                                    // A quiz already gone is the outcome we wanted anyway.
                                    if (!(error is ApiError && error.status == 404)) throw error
                                }
                                ctx.storage.clearActiveAttemptId()
                                activeAttempt = null
                                resumeSlot.replaceChildren()
                                ctx.announce("Unfinished quiz discarded.")
                            }
                        }
                    }
                }
                +h("button", "btn btn-primary") {
                    attr("type", "button")
                    data("action", "resume")
                    +"Resume quiz"
                    on("click") { resume(attempt.id) }
                }
            }
        }
    }

    fun loadActiveAttempt() {
        val id = ctx.storage.getActiveAttemptId() ?: return
        if (ctx.player == null) return
        ctx.scope.launchCatching(
            block = {
                val attempt = ctx.api.attempt(id)
                if (!alive) return@launchCatching
                activeAttempt = attempt.takeIf { it.status == "active" }
                resumeSlot.replaceChildren(bannerFor(attempt))
            },
            onError = { error ->
                if (error is ApiError && (error.status == 404 || error.status == 401)) {
                    ctx.storage.clearActiveAttemptId()
                }
            },
        )
    }

    fun loadStats() {
        if (ctx.player == null) return
        ctx.scope.launchCatching(
            block = {
                val stats = ctx.api.history().stats
                if (!alive || stats.attempts == 0) return@launchCatching
                statsSlot.replaceChildren(
                    h("section", "stats-strip") {
                        attr("aria-label", "Your stats")
                        +h("span") {
                            +t("strong", text = stats.attempts.toString())
                            +if (stats.attempts == 1) " quiz taken" else " quizzes taken"
                        }
                        +h("span") {
                            +t("strong", text = "${stats.averagePercentage}%")
                            +" average"
                        }
                        +h("span") {
                            +t("strong", text = "${stats.bestPercentage}%")
                            +" best"
                        }
                        +h("button", "link-button") {
                            attr("type", "button")
                            +"View history →"
                            on("click") { ctx.navigate(Screen.HISTORY) }
                        }
                    },
                )
            },
            onError = { /* stats are optional decoration */ },
        )
    }

    /* ---------- player ---------- */

    val nameInput = h("input", "text-input") {
        val input = node as HTMLInputElement
        input.id = "player-name"
        input.type = "text"
        input.value = ctx.player?.name ?: ""
        input.maxLength = rules.playerNameMax
        input.autocomplete = "nickname"
        input.required = true
        input.placeholder = "e.g. Alex"
        attr("aria-describedby", "player-name-hint")
        on("input") { refresh() }
    } as HTMLInputElement

    val playerCard = card("Player") {
        +h("label", "field-label") {
            attr("for", "player-name")
            +"Your name"
        }
        +nameInput
        +h("p", "hint") {
            attr("id", "player-name-hint")
            +"Shown on the leaderboard. The first time you use a name, this browser claims it."
        }
    }

    /* ---------- topics ---------- */

    val topicInputs = mutableMapOf<String, HTMLInputElement>()
    val topicCountEls = mutableMapOf<String, HTMLElement>()

    val topicGrid = h("div", "topic-grid") {
        topics.forEach { topic ->
            val input = h("input", "topic-input") {
                val el = node as HTMLInputElement
                el.type = "checkbox"
                el.name = "topics"
                el.value = topic.id
                el.checked = topic.id in config.topics
                on("change") {
                    config.topics = topics.map { it.id }.filter { topicInputs[it]?.checked == true }
                    refresh()
                }
            } as HTMLInputElement
            topicInputs[topic.id] = input

            val count = h("span", "topic-count")
            topicCountEls[topic.id] = count

            +h("label", "topic-card") {
                data("topic", topic.id)
                +input
                +h("span", "topic-icon") {
                    attr("aria-hidden", "true")
                    +(topic.icon ?: "")
                }
                +h("span", "topic-body") {
                    +t("span", "topic-name", topic.name)
                    +h("span", "topic-desc") { rich(topic.description) }
                    +count
                }
                +h("span", "topic-check") {
                    attr("aria-hidden", "true")
                    +"✓"
                }
            }
        }
    }

    fun setAllTopics(checked: Boolean) {
        topicInputs.values.forEach { it.checked = checked }
        config.topics = if (checked) topics.map { it.id } else emptyList()
        refresh()
    }

    val topicsCard = fieldsetCard("Topics") {
        +h("div", "fieldset-tools") {
            +h("button", "link-button") {
                attr("type", "button")
                +"Select all"
                on("click") { setAllTopics(true) }
            }
            +h("button", "link-button") {
                attr("type", "button")
                +"Clear"
                on("click") { setAllTopics(false) }
            }
        }
        +topicGrid
    }

    /* ---------- difficulty & length ---------- */

    val points = rules.difficultyPoints
    val difficultyCard = fieldsetCard("Difficulty") {
        +segmented(
            "difficulty",
            Config.DIFFICULTY_FILTERS.map { it to (Config.DIFFICULTY_LABELS[it] ?: it) },
            config.difficulty,
        ) { value ->
            config.difficulty = value
            refresh()
        }
        +t(
            "p", "hint",
            "Points per correct answer: ${points["easy"]} easy · ${points["medium"]} medium · ${points["hard"]} hard.",
        )
    }

    val availabilityHint = h("p", "hint") { attr("aria-live", "polite") }
    val lengthCard = fieldsetCard("Number of questions") {
        +segmented(
            "count",
            Config.QUESTION_COUNT_OPTIONS.map { it.toString() to it.toString() },
            config.count.toString(),
        ) { value ->
            config.count = value.toInt()
            refresh()
        }
        +availabilityHint
    }

    /* ---------- timer ---------- */

    val timerHint = h("p", "hint")
    val secondsSelect = h("select", "select-input") {
        val select = node as HTMLSelectElement
        select.id = "seconds-per-question"
        Config.SECONDS_PER_QUESTION_OPTIONS.forEach { seconds ->
            +h("option") {
                val option = node.asDynamic()
                option.value = seconds.toString()
                option.selected = seconds == config.secondsPerQuestion
                +"$seconds seconds"
            }
        }
        on("change") { event ->
            config.secondsPerQuestion = (event.target as HTMLSelectElement).value.toInt()
            refresh()
        }
    }
    val secondsField = h("div", "inline-field") {
        +h("label", "field-label") {
            attr("for", "seconds-per-question")
            +"Time per question"
        }
        +secondsSelect
    }

    val timerCard = fieldsetCard("Timer") {
        +segmented(
            "timerMode",
            TIMER_MODE_OPTIONS.map { it.value to it.label },
            config.timerMode,
        ) { value ->
            config.timerMode = value
            refresh()
        }
        +secondsField
        +timerHint
    }

    /* ---------- options ---------- */

    val optionsCard = fieldsetCard("Options") {
        +toggle(
            "Shuffle questions & answers",
            "Off: questions run from easy to hard with answers in their original order.",
            config.shuffle,
        ) { checked -> config.shuffle = checked }
        +toggle(
            "Negative marking",
            "Wrong answers cost ${(rules.negativeMarkRatio * 100).roundToInt()}% of the question's points. " +
                "Your total never drops below zero.",
            config.negativeMarking,
        ) { checked -> config.negativeMarking = checked }
    }

    /* ---------- submit ---------- */

    val summaryText = h("p", "setup-summary")
    val errorText = h("p", "form-error") { attr("role", "alert") }
    val startButton = h("button", "btn btn-primary btn-large") {
        attr("type", "submit")
        data("action", "start")
        +"Start quiz →"
    } as HTMLButtonElement

    fun countFor(topic: TopicSummary): Int = when (config.difficulty) {
        "mixed" -> topic.counts.easy + topic.counts.medium + topic.counts.hard
        "easy" -> topic.counts.easy
        "medium" -> topic.counts.medium
        else -> topic.counts.hard
    }

    fun availableCount(): Int = topics.filter { it.id in config.topics }.sumOf { countFor(it) }

    fun isReady(): Boolean = config.topics.isNotEmpty() && availableCount() > 0

    fun setBusy(value: Boolean) {
        busy = value
        startButton.disabled = value || !isReady()
        startButton.textContent = if (value) "Starting…" else "Start quiz →"
    }

    val form = h("form", "setup-form") {
        node.asDynamic().noValidate = true
        on("submit") { event ->
            event.preventDefault()
            if (!busy) {
                val name = nameInput.value.trim()
                when {
                    name.isEmpty() -> {
                        errorText.textContent = "Enter your name to start."
                        nameInput.focus()
                    }

                    config.topics.isEmpty() || availableCount() == 0 -> {
                        errorText.textContent = if (config.topics.isEmpty()) {
                            "Choose at least one topic."
                        } else {
                            "No questions match these settings."
                        }
                    }

                    else -> ctx.scope.launchCatching(
                        block = {
                            if (activeAttempt != null) {
                                val ok = confirmDialog(
                                    title = "Start a new quiz?",
                                    message = "You have an unfinished quiz. Starting a new one will discard it.",
                                    confirmText = "Start new quiz",
                                    danger = true,
                                )
                                if (!ok) return@launchCatching
                            }
                            setBusy(true)
                            try {
                                ctx.ensurePlayer(name)
                                // The server caps count at what is available.
                                ctx.startQuiz(
                                    CreateAttemptRequest(
                                        topics = config.topics,
                                        difficulty = config.difficulty,
                                        count = config.count,
                                        timerMode = config.timerMode,
                                        secondsPerQuestion = config.secondsPerQuestion,
                                        shuffle = config.shuffle,
                                        negativeMarking = config.negativeMarking,
                                    ),
                                )
                            } finally {
                                if (alive) setBusy(false)
                            }
                        },
                        onError = { error ->
                            if (!alive) return@launchCatching
                            setBusy(false)
                            when {
                                error is ApiError && error.code == "name_taken" -> {
                                    errorText.textContent = "${error.message} (It belongs to another browser.)"
                                    nameInput.focus()
                                }

                                error is ApiError && error.status == 400 ->
                                    errorText.textContent = error.message

                                else -> ctx.handleError(error)
                            }
                        },
                    )
                }
            }
        }

        +playerCard
        +topicsCard
        +h("div", "card-row") {
            +difficultyCard
            +lengthCard
        }
        +timerCard
        +optionsCard
        +h("div", "setup-footer") {
            +h("div") {
                +summaryText
                +errorText
            }
            +startButton
        }
    }

    refresh = {
        for (topic in topics) {
            val n = countFor(topic)
            topicCountEls[topic.id]?.textContent = "$n question${if (n == 1) "" else "s"}"
        }

        val available = availableCount()
        val count = minOf(config.count, available)
        availabilityHint.textContent = when {
            config.topics.isEmpty() -> "Select at least one topic."
            available < config.count ->
                "Only $available question${if (available == 1) "" else "s"} match — " +
                    "your quiz will have $available."

            else -> "$available questions available for this selection."
        }

        val mode = TIMER_MODE_OPTIONS.first { it.value == config.timerMode }
        secondsField.hidden = config.timerMode == TimerModes.OFF
        var timing = mode.hint
        if (config.timerMode == TimerModes.SESSION && count > 0) {
            timing += " Total: ${formatClock(count.toLong() * config.secondsPerQuestion * 1000)}."
        }
        if (config.timerMode == TimerModes.QUESTION) {
            timing += " (Up to +${(rules.speedBonusRatio * 100).roundToInt()}% points.)"
        }
        timerHint.textContent = timing

        startButton.disabled = busy || !isReady()
        errorText.textContent = ""
        val difficultyLabel = if (config.difficulty == "mixed") {
            "mixed difficulty"
        } else {
            (Config.DIFFICULTY_LABELS[config.difficulty] ?: config.difficulty).lowercase()
        }
        val timingLabel = if (config.timerMode == TimerModes.OFF) {
            "untimed"
        } else {
            "${config.secondsPerQuestion}s per question"
        }
        summaryText.textContent = if (isReady()) {
            "$count question${if (count == 1) "" else "s"} · $difficultyLabel · $timingLabel"
        } else {
            "Choose at least one topic to begin."
        }
    }

    refresh()
    root.appendChild(
        h("section", "screen screen-setup") {
            +header
            +resumeSlot
            +statsSlot
            +form
        },
    )
    loadActiveAttempt()
    loadStats()

    return { alive = false }
}

/* ---------- small form builders ---------- */

private fun card(title: String, block: El.() -> Unit): HTMLElement = h("section", "card") {
    +t("h2", "card-title", title)
    block()
}

private fun fieldsetCard(legend: String, block: El.() -> Unit): HTMLElement = h("fieldset", "card") {
    +t("legend", "card-title", legend)
    block()
}

/** Radio group styled as a segmented control (keyboard arrows work natively). */
private fun segmented(
    name: String,
    options: List<Pair<String, String>>,
    selected: String,
    onChange: (String) -> Unit,
): HTMLElement = h("div", "segmented") {
    options.forEach { (value, label) ->
        +h("label", "segment") {
            +h("input") {
                val input = node as HTMLInputElement
                input.type = "radio"
                input.name = name
                input.value = value
                input.checked = value == selected
                on("change") { event ->
                    if ((event.target as HTMLInputElement).checked) onChange(value)
                }
            }
            +t("span", text = label)
        }
    }
}

private fun toggle(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
): HTMLElement = h("label", "toggle") {
    +h("input") {
        val input = node as HTMLInputElement
        input.type = "checkbox"
        input.checked = checked
        attr("role", "switch")
        on("change") { event -> onChange((event.target as HTMLInputElement).checked) }
    }
    +h("span", "toggle-track") { attr("aria-hidden", "true") }
    +h("span", "toggle-text") {
        +t("span", "toggle-label", label)
        +t("span", "hint", description)
    }
}

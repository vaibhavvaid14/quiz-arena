package quizarena.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent
import quizarena.shared.AttemptState
import quizarena.shared.CurrentItem

/**
 * Active quiz screen, driven entirely by server state.
 *
 * The browser never knows the correct answer in advance: it shows the options,
 * sends the choice, and renders whatever the server returns (correct option,
 * explanation, points). Every action (answer / time-up / next / end) is one API
 * call that returns the complete new state.
 *
 * The local [Timer] only drives the on-screen countdown; the server measures
 * time with its own clock and has the final say.
 *  - Per-question mode: at zero we report a time-out; the correct answer is revealed.
 *  - Whole-quiz mode: at zero we ask the server to finish the quiz.
 *  - Timers stop while feedback is shown (the server doesn't charge that time either).
 */

private val OPTION_KEYS = listOf("a", "b", "c", "d", "e", "f")
private const val LOW_TIME_ANNOUNCE_MS = 5000.0

private data class Feedback(val icon: String, val title: String, val tone: String)

private val FEEDBACK = mapOf(
    ItemStatus.CORRECT to Feedback("✓", "Correct!", "good"),
    ItemStatus.WRONG to Feedback("✗", "Not quite", "critical"),
    ItemStatus.TIMEOUT to Feedback("⏱", "Time's up!", "warning"),
)

fun renderQuizScreen(root: HTMLElement, ctx: AppContext, params: ScreenParams): (() -> Unit) {
    val initial = (params as? ScreenParams.Quiz)?.state
        ?: error("The quiz screen needs an attempt state")

    var state: AttemptState = initial
    var mounted = true
    var busy = false // one request at a time
    var lowTimeAnnounced = false
    var renderedPosition: Int? = null

    val timerMode = state.config.timerMode
    var questionTimer: Timer? = null
    var sessionTimer: Timer? = null

    /* ---------- static layout ---------- */

    val progressText = h("span", "quiz-progress-text")
    val progressFill = h("span", "progress-fill")
    val progressBar = h("div", "progress") {
        attr("role", "progressbar")
        attr("aria-label", "Quiz progress")
        attr("aria-valuemin", "0")
        attr("aria-valuemax", state.total.toString())
        +progressFill
    }
    val pointsValue = t("strong", text = "0")
    val streakBadge = h("span", "streak") { node.hidden = true }
    val elapsedText = h("span", "elapsed")

    val ring = if (timerMode == TimerModes.OFF) {
        null
    } else {
        TimerRing(
            if (timerMode == TimerModes.QUESTION) "Time left for this question" else "Time left for the quiz",
        )
    }

    val topicChipSlot = h("span", "chip-slot")
    val questionText = h("h1", "question-text") { attr("tabindex", "-1") }
    val codeBlock = h("pre", "code-block") {
        node.hidden = true
        +h("code")
    }
    val optionList = h("ol", "options") { attr("aria-label", "Answer options") }
    val feedback = h("div", "feedback") { attr("aria-live", "polite") }

    // Forward declarations: the buttons need the handlers, which need the buttons.
    lateinit var endQuizEarly: () -> Unit
    lateinit var next: () -> Unit

    val endButton = h("button", "btn btn-ghost") {
        attr("type", "button")
        +"End quiz"
        on("click") { endQuizEarly() }
    }
    val nextButton = h("button", "btn btn-primary") {
        attr("type", "button")
        node.hidden = true
        data("action", "next")
        on("click") { next() }
    }

    root.appendChild(
        h("section", "screen screen-quiz") {
            +h("div", "quiz-topbar") {
                +h("div", "quiz-meta") {
                    +progressText
                    +h("span", "quiz-score") {
                        +pointsValue
                        +" pts"
                    }
                    +streakBadge
                    +elapsedText
                }
                ring?.let { +it.el }
            }
            +progressBar
            +h("article", "card question-card") {
                +h("div", "question-chips") { +topicChipSlot }
                +questionText
                +codeBlock
                +optionList
                +feedback
            }
            +h("div", "quiz-actions") {
                +endButton
                +h("p", "kbd-hint") {
                    +"Keys: "
                    +t("kbd", text = "1")
                    +"–"
                    +t("kbd", text = "4")
                    +" answer · "
                    +t("kbd", text = "→")
                    +" next"
                }
                +nextButton
            }
        },
    )

    /* ---------- timers (display only; the server keeps the real time) ---------- */

    fun stopTimers() {
        questionTimer?.dispose()
        sessionTimer?.dispose()
        questionTimer = null
        sessionTimer = null
    }

    fun maybeAnnounceLowTime(remainingMs: Double) {
        if (!lowTimeAnnounced && remainingMs <= LOW_TIME_ANNOUNCE_MS && remainingMs > 0) {
            lowTimeAnnounced = true
            ctx.announce("5 seconds left")
        }
    }

    // Declared here so the timers can call them before they are defined below.
    lateinit var reportTimeout: () -> Unit
    lateinit var finishQuiz: () -> Unit

    fun startTimers() {
        stopTimers()
        val clock = state.clock
        when (timerMode) {
            TimerModes.QUESTION -> {
                questionTimer = Timer(
                    limitMs = (clock.questionLimitMs ?: 0L).toDouble(),
                    elapsedMs = clock.questionElapsedMs.toDouble(),
                    onTick = { s ->
                        ring?.update(s)
                        maybeAnnounceLowTime(s.remainingMs)
                    },
                    // Deferred: start() ticks synchronously and may expire during render.
                    onExpire = { window.setTimeout({ reportTimeout() }, 0) },
                ).start()
            }

            TimerModes.SESSION -> {
                sessionTimer = Timer(
                    limitMs = (clock.sessionLimitMs ?: 0L).toDouble(),
                    elapsedMs = clock.sessionElapsedMs.toDouble(),
                    onTick = { s ->
                        ring?.update(s)
                        maybeAnnounceLowTime(s.remainingMs)
                    },
                    onExpire = { window.setTimeout({ finishQuiz() }, 0) },
                ).start()
            }

            else -> {
                sessionTimer = Timer(
                    elapsedMs = clock.sessionElapsedMs.toDouble(),
                    onTick = { s -> elapsedText.textContent = "⏱ ${formatClock(s.elapsedMs)}" },
                ).start()
            }
        }
    }

    /** Shows the frozen clock while feedback is on screen. */
    fun showStoppedClock() {
        val clock = state.clock
        when (timerMode) {
            TimerModes.QUESTION -> {
                val limit = (clock.questionLimitMs ?: 0L).toDouble()
                val remaining = maxOf(0.0, limit - clock.questionElapsedMs)
                ring?.update(TimerState(clock.questionElapsedMs.toDouble(), remaining, if (limit > 0) remaining / limit else 0.0))
            }

            TimerModes.SESSION -> {
                val limit = (clock.sessionLimitMs ?: 0L).toDouble()
                val remaining = maxOf(0.0, limit - clock.sessionElapsedMs)
                ring?.update(TimerState(clock.sessionElapsedMs.toDouble(), remaining, if (limit > 0) remaining / limit else 0.0))
            }

            else -> elapsedText.textContent = "⏱ ${formatClock(clock.sessionElapsedMs)}"
        }
    }

    /* ---------- rendering ---------- */

    fun setOptionsLocked(locked: Boolean) {
        optionList.queryAll(".option").forEach { (it as HTMLButtonElement).disabled = locked }
    }

    lateinit var select: (Long) -> Unit

    fun renderQuestion(current: CurrentItem) {
        val question = current.question
        val topic = ctx.getTopic(question.topic)
        topicChipSlot.replaceChildren(
            chip("${topic?.icon ?: ""} ${topic?.name ?: question.topic}".trim()),
            difficultyChip(question.difficulty),
        )
        questionText.replaceChildren(richText(question.prompt))
        codeBlock.hidden = question.code.isNullOrEmpty()
        codeBlock.firstChild?.textContent = question.code ?: ""

        optionList.replaceChildren(
            question.options.mapIndexed { index, option ->
                h("li") {
                    +h("button", "option") {
                        attr("type", "button")
                        data("option-id", option.id.toString())
                        data("index", index.toString())
                        on("click") { select(option.id) }
                        +h("span", "option-key") {
                            attr("aria-hidden", "true")
                            +OPTION_KEYS[index].uppercase()
                        }
                        +t("span", "option-text", option.text)
                        +h("span", "option-mark")
                    }
                }
            },
        )
        feedback.replaceChildren()
        feedback.className = "feedback"
        nextButton.hidden = true
        nextButton.textContent = if (current.isLast) "See results →" else "Next question →"
        focusElement(questionText)
    }

    fun showFeedback(current: CurrentItem) {
        for (element in optionList.queryAll(".option")) {
            val button = element as HTMLButtonElement
            val id = button.getAttribute("data-option-id")?.toLongOrNull()
            val isCorrect = id != null && id == current.correctOptionId
            val isSelected = id != null && id == current.selectedOptionId
            button.disabled = true
            button.classList.remove("is-pending")
            button.classList.toggle("is-correct", isCorrect)
            button.classList.toggle("is-wrong", isSelected && !isCorrect)
            button.classList.toggle("is-selected", isSelected)
            button.query(".option-mark")?.textContent = when {
                isCorrect -> "✓ Correct answer"
                isSelected -> "✗ Your answer"
                else -> ""
            }
        }

        val message = FEEDBACK[current.status] ?: FEEDBACK.getValue(ItemStatus.TIMEOUT)
        val correctText = current.question.options.firstOrNull { it.id == current.correctOptionId }?.text ?: ""
        val pointsText = if (current.points > 0) "+${current.points} pts" else "${current.points} pts"

        feedback.className = "feedback tone-${message.tone}"
        feedback.replaceChildren(
            listOfNotNull(
                h("p", "feedback-title") {
                    +h("span", "feedback-icon") {
                        attr("aria-hidden", "true")
                        +message.icon
                    }
                    +message.title
                    +t("span", "feedback-points", pointsText)
                },
                // Only shown when the answer was not correct.
                if (current.status == ItemStatus.CORRECT) {
                    null
                } else {
                    h("p", "feedback-answer") {
                        +"Correct answer: "
                        +t("strong", text = correctText)
                    }
                },
                h("p", "feedback-explanation") { rich(current.explanation) },
            ),
        )

        nextButton.hidden = false
        focusElement(nextButton) // the aria-live feedback panel announces the result
    }

    lateinit var finishLocally: () -> Unit
    lateinit var render: () -> Unit

    render = {
        if (state.status == "finished") {
            finishLocally()
        } else {
            val current = state.current
            if (current != null) {
                val live = state.live
                val total = state.total

                progressText.textContent = "Question ${current.position + 1} of $total"
                progressFill.setAttribute(
                    "style",
                    "width: ${if (total > 0) live.answered * 100.0 / total else 0.0}%",
                )
                progressBar.setAttribute("aria-valuenow", live.answered.toString())
                progressBar.setAttribute("aria-valuetext", "${live.answered} of $total answered")
                pointsValue.textContent = live.points.toString()
                streakBadge.hidden = live.streak < 2
                streakBadge.textContent = "🔥 ${live.streak} in a row"

                if (renderedPosition != current.position) {
                    renderedPosition = current.position
                    if (timerMode == TimerModes.QUESTION) lowTimeAnnounced = false
                    renderQuestion(current)
                }

                if (current.status == ItemStatus.PENDING) {
                    setOptionsLocked(false)
                    startTimers()
                } else {
                    stopTimers()
                    showStoppedClock()
                    showFeedback(current)
                }
            }
        }
    }

    /* ---------- server round-trips ---------- */

    /**
     * Runs one API call with the screen locked, then renders the returned state.
     * On failure: a stale-tab conflict reloads the real state; anything else
     * restores the screen so the player can try again.
     */
    fun act(call: suspend () -> AttemptState, onFailure: (() -> Unit)? = null) {
        if (busy || !mounted) return
        busy = true
        // One coroutine for the whole round-trip, recovery included: releasing the
        // lock before the 409 reload would let a keypress fire a second answer for
        // a position the server has already moved past.
        ctx.scope.launch {
            try {
                val nextState = call()
                if (mounted) {
                    state = nextState
                    render()
                }
            } catch (error: Throwable) {
                if (mounted) {
                    if (error is ApiError && error.status == 409) {
                        try {
                            state = ctx.api.attempt(state.id)
                            render()
                        } catch (reloadError: Throwable) {
                            ctx.handleError(reloadError)
                        }
                    } else {
                        ctx.handleError(error)
                        onFailure?.invoke()
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    select = { optionId ->
        if (!busy && state.current?.status == ItemStatus.PENDING) {
            stopTimers()
            setOptionsLocked(true)
            optionList.query("[data-option-id=\"$optionId\"]")?.classList?.add("is-pending")
            val position = state.current!!.position
            act(
                call = { ctx.api.answer(state.id, position, optionId) },
                onFailure = {
                    optionList.query(".is-pending")?.classList?.remove("is-pending")
                    // Unlocks the options and restarts the countdown; the server's clock still decides.
                    render()
                },
            )
        }
    }

    reportTimeout = {
        if (mounted && state.current?.status == ItemStatus.PENDING) {
            setOptionsLocked(true)
            val position = state.current!!.position
            act(
                call = { ctx.api.answer(state.id, position, null) },
                onFailure = { setOptionsLocked(false) },
            )
        }
    }

    next = {
        val current = state.current
        if (current != null && current.status != ItemStatus.PENDING) {
            act(call = { ctx.api.next(state.id, current.position) })
        }
    }

    finishQuiz = {
        stopTimers()
        act(call = { ctx.api.finish(state.id) }, onFailure = { render() })
    }

    endQuizEarly = {
        if (!busy) {
            ctx.run {
                val ok = confirmDialog(
                    title = "End the quiz now?",
                    message = "Unanswered questions will be marked as skipped and the attempt " +
                        "will be saved to your history.",
                    confirmText = "End quiz",
                    danger = true,
                )
                if (ok && mounted) finishQuiz()
            }
        }
    }

    finishLocally = {
        if (mounted) {
            stopTimers()
            if (state.endReason == EndReasons.TIME_UP) {
                ctx.toast("Time's up! Your quiz was submitted automatically.", Tone.WARNING)
            }
            ctx.completeQuiz(state)
        }
    }

    /* ---------- keyboard ---------- */

    val keyListener = object : EventListener {
        override fun handleEvent(event: Event) {
            val key = event as? KeyboardEvent ?: return
            if (busy || key.defaultPrevented || key.ctrlKey || key.metaKey || key.altKey) return
            val target = key.target as? HTMLElement
            if (target?.closest("input, textarea, select, dialog") != null) return
            val current = state.current ?: return
            val pressed = key.key.lowercase()

            if (current.status == ItemStatus.PENDING) {
                val digit = pressed.toIntOrNull()
                val index = if (digit != null) digit - 1 else OPTION_KEYS.indexOf(pressed)
                val option = current.question.options.getOrNull(index)
                if (index >= 0 && option != null) {
                    key.preventDefault()
                    select(option.id)
                }
            } else if (pressed == "arrowright" || pressed == "n") {
                key.preventDefault()
                next()
            }
        }
    }

    document.addEventListener("keydown", keyListener)
    render()

    return {
        mounted = false
        stopTimers()
        document.removeEventListener("keydown", keyListener)
        // Close a confirm dialog this screen may have left open.
        document.queryAll("dialog.dialog [data-action=\"cancel\"]").forEach { (it as HTMLElement).click() }
    }
}

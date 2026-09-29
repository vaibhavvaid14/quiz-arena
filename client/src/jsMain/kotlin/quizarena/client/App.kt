package quizarena.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import quizarena.shared.AttemptState
import quizarena.shared.CatalogResponse
import quizarena.shared.CreateAttemptRequest
import quizarena.shared.TopicSummary

/**
 * Launches a suspending block and routes any failure to [onError]. Screens use
 * this rather than AppContext.run when they render their own failure state.
 */
fun CoroutineScope.launchCatching(block: suspend () -> Unit, onError: (Throwable) -> Unit) {
    launch {
        try {
            block()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}

enum class Screen(val id: String) {
    SETUP("setup"),
    QUIZ("quiz"),
    RESULTS("results"),
    HISTORY("history"),
    LEADERBOARD("leaderboard"),
    ;

    companion object {
        fun from(id: String): Screen? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a screen is handed besides the context. Where the JavaScript passed a
 * loose object, each screen's arguments are now a named type, so navigating to
 * results without an attempt id will not compile.
 */
sealed interface ScreenParams {
    data object None : ScreenParams
    data class Quiz(val state: AttemptState? = null) : ScreenParams
    data class Results(val attemptId: String, val fresh: Boolean = false) : ScreenParams
}

/** A screen renders into `root` and may return a cleanup function. */
typealias ScreenRenderer = (HTMLElement, AppContext, ScreenParams) -> (() -> Unit)?

enum class Tone(val cls: String) { INFO("info"), WARNING("warning"), GOOD("good") }

/**
 * Application controller: owns navigation between screens and the workflows
 * that span screens (identify the player, start a quiz, finish a quiz).
 * The API client and storage are injected, so tests can mount the whole app.
 */
class AppContext internal constructor(
    val api: Api,
    val storage: Storage,
    val persistent: Boolean,
    private val root: HTMLElement,
    private val chrome: HTMLElement?,
) {
    /** Topics and rules from the server; loaded once in [App.start]. */
    var catalog: CatalogResponse? = null
        internal set

    val scope: CoroutineScope = MainScope()

    var currentScreen: Screen? = null
        private set

    private var cleanup: (() -> Unit)? = null

    private val liveRegion = h("div", "visually-hidden") {
        attr("aria-live", "polite")
        attr("aria-atomic", "true")
    }
    private val toastRegion = h("div", "toast-region")

    init {
        root.parentNode?.insertBefore(liveRegion, root.nextSibling)
        root.parentNode?.insertBefore(toastRegion, liveRegion.nextSibling)
    }

    fun getTopic(id: String): TopicSummary? = catalog?.topics?.firstOrNull { it.id == id }

    val player: StoredPlayer? get() = storage.getCurrentPlayer()

    fun navigate(screen: Screen, params: ScreenParams = ScreenParams.None) {
        cleanup?.invoke()
        cleanup = null
        currentScreen = screen
        root.replaceChildren()
        root.setAttribute("data-screen", screen.id)
        cleanup = screens.getValue(screen)(root, this, params)
        updateNav(screen)
        window.scrollTo(0.0, 0.0)
        focusElement(root.query("[data-autofocus]") ?: root.query("h1"))
    }

    /**
     * Makes `name` the current player: reuses a name this device already owns,
     * otherwise claims it on the server. Throws ApiError 409 if someone else has it.
     */
    suspend fun ensurePlayer(name: String): StoredPlayer {
        storage.findKnownPlayer(name)?.let {
            storage.setCurrentPlayer(it.name)
            return it
        }
        val created = api.createPlayer(name)
        val player = StoredPlayer(created.name, created.key)
        storage.rememberPlayer(player)
        return player
    }

    /** Starts a quiz on the server and opens it. */
    suspend fun startQuiz(request: CreateAttemptRequest): AttemptState {
        val state = api.createAttempt(request)
        // A "retry missed" run is a one-off; it must not overwrite the saved setup.
        if (request.questionIds == null) {
            storage.updatePrefs { prefs ->
                prefs.copy(
                    lastConfig = StoredConfig(
                        topics = request.topics.orEmpty(),
                        difficulty = request.difficulty ?: "mixed",
                        count = request.count ?: 10,
                        timerMode = request.timerMode ?: TimerModes.QUESTION,
                        secondsPerQuestion = request.secondsPerQuestion ?: 30,
                        shuffle = request.shuffle,
                        negativeMarking = request.negativeMarking,
                    ),
                )
            }
        }
        storage.setActiveAttemptId(state.id)
        navigate(Screen.QUIZ, ScreenParams.Quiz(state))
        return state
    }

    /** Called by the quiz screen once the server reports the attempt finished. */
    fun completeQuiz(state: AttemptState) {
        storage.clearActiveAttemptId()
        navigate(Screen.RESULTS, ScreenParams.Results(state.id, fresh = true))
    }

    /** Central handling for API failures that reach the UI. */
    fun handleError(error: Throwable) {
        if (error is ApiError && error.status == 401) {
            storage.getCurrentPlayer()?.let { storage.forgetPlayer(it.name) }
            toast("Your player session expired. Enter your name again to continue.", Tone.WARNING)
            navigate(Screen.SETUP)
            return
        }
        toast(error.message ?: "Something went wrong.", Tone.WARNING)
        if (error !is ApiError) console.error(error)
    }

    /** Runs a suspending action, routing any failure through [handleError]. */
    fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                handleError(t)
            }
        }
    }

    fun announce(message: String) {
        liveRegion.textContent = ""
        // Setting the text on the next frame makes screen readers re-announce repeats.
        window.requestAnimationFrame { liveRegion.textContent = message }
    }

    fun toast(message: String, tone: Tone = Tone.INFO, durationMs: Int = 4000) {
        val toast = h("div", "toast toast-${tone.cls}") {
            attr("role", "status")
            +message
        }
        toastRegion.appendChild(toast)
        window.setTimeout({ toast.classList.add("toast-leaving") }, durationMs)
        window.setTimeout({ toast.remove() }, durationMs + 400)
    }

    internal fun disposeScreen() {
        cleanup?.invoke()
        cleanup = null
    }

    // ------------------------------------------------------------ chrome

    private fun updateNav(screen: Screen) {
        val bar = chrome ?: return
        val section = when (screen) {
            Screen.HISTORY -> "history"
            Screen.LEADERBOARD -> "leaderboard"
            else -> "setup"
        }
        for (link in bar.queryAll("[data-nav]")) {
            if (link.getAttribute("data-nav") == section && !link.classList.contains("brand")) {
                link.setAttribute("aria-current", "page")
            } else {
                link.removeAttribute("aria-current")
            }
        }
    }

    internal fun applyTheme(theme: String?) {
        val html = document.documentElement as HTMLElement
        if (theme == "light" || theme == "dark") {
            html.setAttribute("data-theme", theme)
        } else {
            html.removeAttribute("data-theme")
        }
        val toggle = chrome?.query("[data-theme-toggle]") ?: return
        val dark = effectiveTheme() == "dark"
        toggle.setAttribute("aria-pressed", dark.toString())
        toggle.setAttribute("aria-label", if (dark) "Switch to light theme" else "Switch to dark theme")
        toggle.query("[data-theme-icon]")?.textContent = if (dark) "☀️" else "🌙"
    }

    internal fun effectiveTheme(): String {
        val explicit = (document.documentElement as HTMLElement).getAttribute("data-theme")
        if (!explicit.isNullOrEmpty()) return explicit
        return if (window.matchMedia("(prefers-color-scheme: dark)").matches) "dark" else "light"
    }

    internal fun wireChrome() {
        val bar = chrome ?: return
        for (link in bar.queryAll("[data-nav]")) {
            link.addEventListener("click", { event ->
                event.preventDefault()
                if (catalog == null) return@addEventListener // still loading / server unreachable
                val leavingQuiz = currentScreen == Screen.QUIZ
                Screen.from(link.getAttribute("data-nav") ?: "")?.let { navigate(it) }
                if (leavingQuiz) {
                    toast("Quiz saved. Resume it from New quiz — timed quizzes keep counting down while you are away.")
                }
            })
        }
        bar.query("[data-theme-toggle]")?.addEventListener("click", {
            val next = if (effectiveTheme() == "dark") "light" else "dark"
            storage.updatePrefs { it.copy(theme = next) }
            applyTheme(next)
        })
    }

    internal fun renderStatus(title: String, message: String, retry: HTMLElement? = null) {
        disposeScreen()
        currentScreen = null
        root.replaceChildren(
            h("section", "screen screen-status") {
                +h("div", "empty-state") {
                    +t("h1", text = title)
                    +t("p", text = message)
                    retry?.let { +it }
                }
            },
        )
    }

    internal fun closeScope() = scope.cancel()

    private val screens: Map<Screen, ScreenRenderer> = mapOf(
        Screen.SETUP to ::renderSetupScreen,
        Screen.QUIZ to ::renderQuizScreen,
        Screen.RESULTS to ::renderResultsScreen,
        Screen.HISTORY to ::renderHistoryScreen,
        Screen.LEADERBOARD to ::renderLeaderboardScreen,
    )
}

class App(
    root: HTMLElement,
    api: Api,
    storage: Storage,
    persistent: Boolean = true,
    chrome: HTMLElement? = null,
) {
    val ctx = AppContext(api, storage, persistent, root, chrome)

    suspend fun start() {
        ctx.applyTheme(ctx.storage.getPrefs().theme)
        ctx.wireChrome()
        if (!ctx.persistent) {
            ctx.toast(
                "Browser storage is unavailable, so this device will forget your player name when the tab closes.",
                Tone.WARNING,
                durationMs = 7000,
            )
        }
        load()
    }

    private suspend fun load() {
        ctx.renderStatus("Loading…", "Fetching topics from the quiz server.")
        try {
            ctx.catalog = ctx.api.catalog()
        } catch (error: Throwable) {
            ctx.renderStatus(
                "Can't reach the quiz server",
                "${error.message} Start it with \"./gradlew :server:run\" in the project folder.",
                h("button", "btn btn-primary") {
                    attr("type", "button")
                    +"Try again"
                    on("click") { ctx.run { load() } }
                },
            )
            return
        }
        // A stored key can go stale if the database was reset; forget it quietly.
        ctx.storage.getCurrentPlayer()?.let { player ->
            try {
                ctx.api.me()
            } catch (error: Throwable) {
                if (error is ApiError && error.status == 401) ctx.storage.forgetPlayer(player.name)
            }
        }
        ctx.navigate(Screen.SETUP)
    }

    fun destroy() {
        ctx.disposeScreen()
        ctx.closeScope()
    }
}

package quizarena.client

import kotlinx.browser.window

/** What a tick publishes. `fraction` is remaining/limit in 0..1 (always 1 when unlimited). */
data class TimerState(val elapsedMs: Double, val remainingMs: Double, val fraction: Double)

/**
 * Drift-free countdown / stopwatch.
 *
 * Elapsed time is always derived from a monotonic clock, never from counting
 * ticks, so throttled background tabs and slow frames cannot make the timer
 * lie. The interval only drives UI updates and expiry detection.
 *
 * With `limitMs = Double.POSITIVE_INFINITY` it is a plain stopwatch that never
 * expires.
 */
class Timer(
    val limitMs: Double = Double.POSITIVE_INFINITY,
    elapsedMs: Double = 0.0,
    private var onTick: ((TimerState) -> Unit)? = null,
    private var onExpire: (() -> Unit)? = null,
    private val intervalMs: Int = 100,
    private val now: () -> Double = { window.performance.now() },
    private val setIntervalFn: (() -> Unit, Int) -> Int = { fn, ms -> window.setInterval({ fn() }, ms) },
    private val clearIntervalFn: (Int) -> Unit = { window.clearInterval(it) },
) {
    private var baseElapsed: Double = elapsedMs.coerceIn(0.0, limitMs)
    private var startedAt: Double? = null
    private var handle: Int? = null

    /**
     * Not marked expired yet even if resumed at or past the limit: the first
     * tick after [start] fires [onExpire], so a quiz reloaded at 0:00 still gets
     * submitted rather than sitting there.
     */
    var isExpired: Boolean = false
        private set

    val isLimited: Boolean get() = limitMs.isFinite()
    val isRunning: Boolean get() = startedAt != null

    fun elapsed(): Double {
        val live = if (isRunning) now() - startedAt!! else 0.0
        return minOf(limitMs, baseElapsed + live)
    }

    fun remaining(): Double =
        if (isLimited) maxOf(0.0, limitMs - elapsed()) else Double.POSITIVE_INFINITY

    /** Remaining share of the limit, 0..1 (1 when unlimited). */
    fun fraction(): Double = if (isLimited) remaining() / limitMs else 1.0

    fun state() = TimerState(elapsed(), remaining(), fraction())

    fun start(): Timer {
        if (isRunning || isExpired) return this
        startedAt = now()
        handle = setIntervalFn({ tick() }, intervalMs)
        tick()
        return this
    }

    fun pause(): Timer {
        if (!isRunning) return this
        baseElapsed = elapsed()
        startedAt = null
        handle?.let { clearIntervalFn(it) }
        handle = null
        return this
    }

    /** Stops for good; no further callbacks will fire. */
    fun dispose() {
        pause()
        onTick = null
        onExpire = null
    }

    /** Publishes the current state and fires onExpire exactly once. Public so tests can drive it. */
    fun tick() {
        val state = state()
        onTick?.invoke(state)
        if (isLimited && !isExpired && state.remainingMs <= 0) {
            isExpired = true
            pause()
            onExpire?.invoke()
        }
    }
}

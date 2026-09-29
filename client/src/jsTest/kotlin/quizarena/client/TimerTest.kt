package quizarena.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The timer is driven by an injected clock and an injected interval, so these
 * tests never wait on real time: [tick] is called by hand.
 */
class TimerTest {
    /** A clock the test moves, plus a stand-in for setInterval that never fires. */
    private class Harness {
        var now = 0.0
        var ticks = mutableListOf<TimerState>()
        var expired = 0

        fun timer(limitMs: Double = Double.POSITIVE_INFINITY, elapsedMs: Double = 0.0) = Timer(
            limitMs = limitMs,
            elapsedMs = elapsedMs,
            onTick = { ticks.add(it) },
            onExpire = { expired++ },
            now = { now },
            setIntervalFn = { _, _ -> 1 },
            clearIntervalFn = { },
        )
    }

    @Test
    fun countsDownFromTheLimitUsingTheClock() {
        val harness = Harness()
        val timer = harness.timer(limitMs = 10_000.0).start()
        assertEquals(10_000.0, timer.remaining())

        harness.now = 3_000.0
        assertEquals(3_000.0, timer.elapsed())
        assertEquals(7_000.0, timer.remaining())
        assertEquals(0.7, timer.fraction())
    }

    @Test
    fun pauseFreezesElapsedTimeAndResumeContinues() {
        val harness = Harness()
        val timer = harness.timer(limitMs = 10_000.0).start()
        harness.now = 2_000.0
        timer.pause()

        harness.now = 9_000.0 // time passes while paused
        assertEquals(2_000.0, timer.elapsed())
        assertFalse(timer.isRunning)

        timer.start()
        harness.now = 11_000.0 // two more seconds of running time
        assertEquals(4_000.0, timer.elapsed())
    }

    @Test
    fun firesOnExpireExactlyOnceAndClampsAtZero() {
        val harness = Harness()
        val timer = harness.timer(limitMs = 5_000.0).start()

        harness.now = 6_000.0
        timer.tick()
        timer.tick()

        assertEquals(1, harness.expired)
        assertTrue(timer.isExpired)
        assertEquals(0.0, timer.remaining())
        assertEquals(5_000.0, timer.elapsed()) // never past the limit
    }

    @Test
    fun resumesFromPreviouslyElapsedTime() {
        val harness = Harness()
        val timer = harness.timer(limitMs = 10_000.0, elapsedMs = 6_000.0).start()
        assertEquals(4_000.0, timer.remaining())
    }

    @Test
    fun resumingAtOrPastTheLimitExpiresOnTheFirstTickWithNoStuckClock() {
        val harness = Harness()
        val timer = harness.timer(limitMs = 10_000.0, elapsedMs = 10_000.0)
        assertFalse(timer.isExpired) // not expired until it actually starts
        timer.start()
        assertEquals(1, harness.expired)
    }

    @Test
    fun anUnlimitedTimerActsAsAStopwatchAndNeverExpires() {
        val harness = Harness()
        val timer = harness.timer().start()
        harness.now = 120_000.0
        timer.tick()

        assertEquals(120_000.0, timer.elapsed())
        assertFalse(timer.isLimited)
        assertEquals(1.0, timer.fraction())
        assertEquals(0, harness.expired)
    }

    @Test
    fun disposeStopsCallbacks() {
        val harness = Harness()
        val timer = harness.timer(limitMs = 1_000.0).start()
        val before = harness.ticks.size
        timer.dispose()
        harness.now = 5_000.0
        timer.tick()

        assertEquals(before, harness.ticks.size)
        assertEquals(0, harness.expired)
    }
}

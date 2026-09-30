package quizarena.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** Formatting and the backtick-to-code parsing the question bank relies on. */
class DomTest {
    @Test
    fun formatDurationRoundsTiesUpNotToEven() {
        // Kotlin's round() goes to even, which would make these "0s" and "2s".
        assertEquals("1s", formatDuration(500))
        assertEquals("3s", formatDuration(2500))
        assertEquals("0s", formatDuration(0))
        assertEquals("45s", formatDuration(45_000))
        assertEquals("3m 12s", formatDuration(192_000))
        assertEquals("1h 4m", formatDuration(3_840_000))
    }

    @Test
    fun formatClockCountsUpInMinutesAndHours() {
        assertEquals("0:00", formatClock(0.0))
        assertEquals("0:30", formatClock(30_000.0))
        assertEquals("1:00", formatClock(60_000.0))
        assertEquals("1:00:00", formatClock(3_600_000.0))
        // Negative remaining time never renders as a negative clock.
        assertEquals("0:00", formatClock(-5_000.0))
    }

    @Test
    fun richTextTurnsPairedBackticksIntoCodeElements() {
        val nodes = richText("Types, scope and `this`.")
        assertEquals(3, nodes.size)
        assertEquals("CODE", (nodes[1] as org.w3c.dom.Element).tagName)
        assertEquals("this", nodes[1].textContent)
    }

    @Test
    fun anUnpairedBacktickStaysOrdinaryText() {
        val nodes = richText("a `b c")
        assertEquals("a `b c", nodes.joinToString("") { it.textContent ?: "" })
        assertEquals(0, nodes.count { it.nodeName == "CODE" })
    }

    @Test
    fun richTextOfNothingIsNothing() {
        assertEquals(0, richText(null).size)
        assertEquals(0, richText("").size)
    }
}

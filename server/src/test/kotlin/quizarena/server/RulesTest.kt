package quizarena.server

import quizarena.shared.Rules
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesTest {
    @Test
    fun `scoring table`() {
        assertEquals(45, Rules.scoreAnswer("correct", "hard", "question", false, 1.0))
        assertEquals(38, Rules.scoreAnswer("correct", "hard", "question", false, 0.5))
        assertEquals(10, Rules.scoreAnswer("correct", "easy", "off", false, 1.0))
        assertEquals(0, Rules.scoreAnswer("wrong", "medium", "question", false))
        // 2.5 rounds half up, away from zero: Kotlin's Math.round would give -2.
        assertEquals(-3, Rules.scoreAnswer("wrong", "easy", "question", true))
        assertEquals(0, Rules.scoreAnswer("timeout", "hard", "question", true))
        assertEquals(30, Rules.maxPointsFor("medium", "question"))
    }

    @Test
    fun `speed bonus is proportional and clamped`() {
        assertEquals(20, Rules.scoreAnswer("correct", "medium", "question", false, 0.0))
        assertEquals(30, Rules.scoreAnswer("correct", "medium", "question", false, 1.0))
        // Out-of-range fractions cannot inflate or deflate the bonus.
        assertEquals(30, Rules.scoreAnswer("correct", "medium", "question", false, 5.0))
        assertEquals(20, Rules.scoreAnswer("correct", "medium", "question", false, -1.0))
    }

    @Test
    fun `grades`() {
        val grades = listOf(100, 90, 89, 75, 60, 40, 39, 0).map { Rules.gradeFor(it).grade }
        assertEquals(listOf("A", "A", "B", "B", "C", "D", "F", "F"), grades)
    }

    @Test
    fun `round half up goes away from zero on a tie`() {
        assertEquals(3, Rules.roundHalfUp(2.5))
        assertEquals(-3, Rules.roundHalfUp(-2.5))
        assertEquals(2, Rules.roundHalfUp(2.4))
        assertEquals(0, Rules.roundHalfUp(0.0))
    }
}

package quizarena.server

import quizarena.shared.AnswerRequest
import quizarena.shared.CreatePlayerRequest
import quizarena.shared.NextRequest
import quizarena.shared.Rules
import kotlin.test.*

class PlayerTest {
    private val f = Fixture()

    @Test
    fun `create and authenticate`() {
        assertEquals("Sam", f.service.authenticate(f.created.key).name)
        // The raw key is never stored, only its SHA-256.
        val stored = f.db.read { conn -> conn.query("SELECT key_hash FROM players") { it.getString("key_hash") } }
        assertFalse(stored.any { it.contains(f.created.key) })
    }

    @Test
    fun `names are cleaned and unique`() {
        assertEquals("Ada Lovelace", f.service.createPlayer(CreatePlayerRequest("  Ada   Lovelace ")).name)
        assertFailsWith<ConflictException> { f.service.createPlayer(CreatePlayerRequest("sam")) }
        for (bad in listOf("", "   ", "x".repeat(31), "bell\u0007")) {
            assertFailsWith<ValidationException>("expected $bad to be rejected") {
                f.service.createPlayer(CreatePlayerRequest(bad))
            }
        }
    }

    @Test
    fun `names may contain characters outside the basic plane`() {
        // On the JVM an emoji is a surrogate pair, which must not be mistaken for
        // the lone surrogates the control-character check is meant to reject.
        assertEquals("Ada 🎉", f.service.createPlayer(CreatePlayerRequest("Ada 🎉")).name)
        // And it costs one character against the limit, not two.
        val thirty = "🎉".repeat(30)
        assertEquals(thirty, f.service.createPlayer(CreatePlayerRequest(thirty)).name)
        assertFailsWith<ValidationException> { f.service.createPlayer(CreatePlayerRequest("🎉".repeat(31))) }
    }

    @Test
    fun `every kind of unicode space is collapsed`() {
        // U+3000 ideographic space, U+00A0 no-break space.
        assertEquals("Ada Lovelace", f.service.createPlayer(CreatePlayerRequest("Ada　Lovelace")).name)
        assertEquals("Grace Hopper", f.service.createPlayer(CreatePlayerRequest("Grace  Hopper")).name)
        // A name made only of spaces is empty once collapsed.
        assertFailsWith<ValidationException> { f.service.createPlayer(CreatePlayerRequest("　")) }
    }

    @Test
    fun `bad keys are rejected`() {
        for (key in listOf("", "nope")) {
            assertFailsWith<UnauthorizedException> { f.service.authenticate(key) }
        }
        assertFailsWith<UnauthorizedException> { f.service.authenticate(null) }
    }
}

class CreateAttemptTest {
    private val f = Fixture()

    @Test
    fun `state hides answers until resolved`() {
        val state = f.start()
        val current = assertNotNull(state.current)
        assertEquals("active", state.status)
        assertEquals(5, state.total)
        assertNull(current.correctOptionId)
        assertNull(current.explanation)
        assertEquals(4, current.question.options.size)
    }

    @Test
    fun `count is capped by the pool and questions are unique`() {
        val state = f.start(count = 50)
        assertEquals(8, state.total)
        val ids = f.db.read { conn ->
            conn.query("SELECT question_id FROM attempt_questions WHERE attempt_id = ?", state.id) {
                it.getString("question_id")
            }
        }
        assertEquals(8, ids.toSet().size)
    }

    @Test
    fun `filters by topic and difficulty`() {
        val state = f.start(topics = listOf("beta"), difficulty = "hard")
        val ids = f.db.read { conn ->
            conn.query("SELECT question_id FROM attempt_questions WHERE attempt_id = ?", state.id) {
                it.getString("question_id")
            }
        }.toSet()
        assertEquals(setOf("b-h1", "b-h2"), ids)
    }

    @Test
    fun `unshuffled runs easy to hard with original option order`() {
        val state = f.start(count = 8, shuffle = false)
        val rows = f.db.read { conn ->
            conn.query(
                """SELECT q.difficulty, aq.option_order FROM attempt_questions aq
                     JOIN questions q ON q.id = aq.question_id
                    WHERE aq.attempt_id = ? ORDER BY aq.position""",
                state.id,
            ) { it.getString("difficulty") to it.getString("option_order") }
        }
        assertEquals(List(3) { "easy" } + List(2) { "medium" } + List(3) { "hard" }, rows.map { it.first })
        for ((_, order) in rows) {
            val ids = order.trim('[', ']').split(",").map { it.trim().toLong() }
            assertEquals(ids.sorted(), ids)
        }
    }

    @Test
    fun `validation rejects bad configuration`() {
        val bad = listOf(
            config(topics = emptyList()),
            config(topics = listOf("ghost")),
            config(difficulty = "insane"),
            config(count = 0),
            config(timerMode = "sometimes"),
            config(secondsPerQuestion = 2),
        )
        for (c in bad) {
            assertFailsWith<ValidationException>("expected $c to be rejected") {
                f.service.createAttempt(f.player, c)
            }
        }
    }

    @Test
    fun `retry with pinned question ids`() {
        val state = f.start(questionIds = listOf("b-h2", "a-e1", "missing"))
        assertEquals(2, state.total)
        assertTrue(state.config.isRetry)
    }

    @Test
    fun `one active attempt per player`() {
        val first = f.start()
        f.start()
        assertFailsWith<NotFoundException> { f.service.getState(f.player, first.id) }
    }

    @Test
    fun `attempts are private to their player`() {
        val state = f.start()
        val other = f.newPlayer("Eve")
        assertFailsWith<NotFoundException> { f.service.getState(other, state.id) }
    }
}

class AnswerTest {
    private val f = Fixture()

    @Test
    fun `correct answer reveals and scores`() {
        val after = f.answer(f.start(), right = true, afterMs = 2000)
        val current = assertNotNull(after.current)
        assertEquals("correct", current.status)
        assertEquals(current.correctOptionId, current.selectedOptionId)
        assertNotNull(current.explanation)
        assertEquals(1, after.live.correct)
        assertTrue(current.points > 0)
    }

    @Test
    fun `wrong answer and negative marking`() {
        val state = f.start(topics = listOf("alpha"), difficulty = "medium", count = 1, negativeMarking = true)
        val after = f.answer(state, right = false)
        assertEquals("wrong", after.current!!.status)
        assertEquals(-5, after.current!!.points) // 25% of 20
        assertEquals(0, after.live.points) // total never below zero
    }

    @Test
    fun `speed bonus uses server time`() {
        val state = f.start(topics = listOf("beta"), difficulty = "hard", count = 1, timerMode = "question", secondsPerQuestion = 10)
        // 5.3 s on the server clock, minus the 0.3 s latency allowance = 5 s of 10 s used.
        val after = f.answer(state, right = true, afterMs = 5300)
        assertEquals(30 + 8, after.current!!.points) // 30 base + roundHalfUp(15 * 0.5)
        val spent = f.db.read { conn ->
            conn.queryOne("SELECT time_spent_ms FROM attempt_questions WHERE attempt_id = ?", state.id) {
                it.getLong("time_spent_ms")
            }
        }
        assertEquals(5000L, spent)
    }

    @Test
    fun `late answer past the grace is a timeout`() {
        val state = f.start(timerMode = "question", secondsPerQuestion = 10)
        val after = f.answer(state, right = true, afterMs = 10_000 + Rules.DEADLINE_GRACE_MS + 1)
        assertEquals("timeout", after.current!!.status)
        assertEquals(0, after.current!!.points)
    }

    @Test
    fun `null option records a timeout`() {
        val state = f.start(timerMode = "question", secondsPerQuestion = 10)
        f.clock.advance(10_000)
        val after = f.service.answer(f.player, state.id, AnswerRequest(0, null))
        assertEquals("timeout", after.current!!.status)
        assertNotNull(after.current!!.correctOptionId)
    }

    @Test
    fun `double submit is idempotent and stale position conflicts`() {
        val state = f.start()
        val first = f.answer(state, right = false)
        val second = f.service.answer(f.player, state.id, AnswerRequest(0, f.correctOption(state)))
        assertEquals("wrong", second.current!!.status) // the first answer stands
        assertEquals(first.live, second.live)
        assertFailsWith<ConflictException> { f.service.answer(f.player, state.id, AnswerRequest(3, 1L)) }
    }

    @Test
    fun `option must belong to the question`() {
        val state = f.start()
        val foreign = f.db.read { conn ->
            conn.queryOne(
                "SELECT id FROM question_options WHERE question_id <> ? LIMIT 1",
                state.current!!.question.id,
            ) { it.getLong("id") }!!
        }
        assertFailsWith<ValidationException> { f.service.answer(f.player, state.id, AnswerRequest(0, foreign)) }
    }
}

class FlowTest {
    private val f = Fixture()

    @Test
    fun `next requires an answer and is double click safe`() {
        val state = f.start()
        assertFailsWith<ConflictException> { f.next(state) }
        val moved = f.next(f.answer(state))
        assertEquals(1, moved.currentIndex)
        val again = f.service.next(f.player, state.id, NextRequest(0))
        assertEquals(1, again.currentIndex)
    }

    @Test
    fun `full quiz completes with summary`() {
        var state = f.start(count = 4)
        repeat(4) { i ->
            state = f.answer(state, right = i < 3, afterMs = 1000)
            state = f.next(state)
        }
        assertEquals("finished", state.status)
        assertEquals("completed", state.endReason)

        val results = f.service.results(f.player, state.id)
        assertEquals(Triple(3, 1, 75), Triple(results.correct, results.wrong, results.percentage))
        assertEquals("B", results.grade.grade)
        assertEquals(3, results.bestStreak)
        assertEquals(4, results.review!!.size)
        assertEquals(1, results.missedQuestionIds.size)
        results.review!!.forEach { entry -> assertEquals(1, entry.options.count { it.isCorrect }) }
        assertEquals(4, results.byTopic.sumOf { it.total })
    }

    @Test
    fun `finish early quits and skips the rest`() {
        val state = f.answer(f.start())
        val done = f.service.finish(f.player, state.id)
        assertEquals("quit", done.endReason)
        val results = f.service.results(f.player, state.id)
        assertEquals(1, results.correct)
        assertEquals(4, results.skipped)
    }

    @Test
    fun `results before finishing conflict`() {
        assertFailsWith<ConflictException> { f.service.results(f.player, f.start().id) }
    }

    @Test
    fun `discard only unfinished`() {
        val state = f.start()
        f.service.discard(f.player, state.id)
        assertFailsWith<NotFoundException> { f.service.getState(f.player, state.id) }

        val finished = f.service.finish(f.player, f.start().id)
        assertFailsWith<ConflictException> { f.service.discard(f.player, finished.id) }
    }
}

class ExpiryTest {
    private val f = Fixture()

    @Test
    fun `question clock keeps running while away`() {
        val state = f.start(timerMode = "question", secondsPerQuestion = 10)
        f.clock.advance(60_000) // tab closed for a minute
        val resumed = f.service.getState(f.player, state.id)
        assertEquals("timeout", resumed.current!!.status)
        assertEquals(10_000L, resumed.clock.questionElapsedMs)
    }

    @Test
    fun `whole quiz clock auto submits`() {
        var state = f.start(timerMode = "session", secondsPerQuestion = 10, count = 3) // 30 s budget
        state = f.answer(state, afterMs = 8000)
        f.clock.advance(120_000) // reading feedback is free...
        state = f.next(state)
        assertEquals("active", state.status)
        assertEquals(8000 - Rules.LATENCY_ALLOWANCE_MS, state.clock.sessionElapsedMs)

        f.clock.advance(60_000) // ...but the live question is not
        val resumed = f.service.getState(f.player, state.id)
        assertEquals("finished", resumed.status)
        assertEquals("time-up", resumed.endReason)

        val results = f.service.results(f.player, state.id)
        assertEquals(1, results.correct)
        assertEquals(1, results.timedOut)
        assertEquals(1, results.skipped)
        assertEquals(30_000L, results.durationMs)
    }

    @Test
    fun `client finish at zero is time-up not quit`() {
        val state = f.start(timerMode = "session", secondsPerQuestion = 10, count = 2)
        f.clock.advance(19_500) // client clock hit 0:00 a moment early
        assertEquals("time-up", f.service.finish(f.player, state.id).endReason)
    }
}

class HistoryAndLeaderboardTest {
    private val f = Fixture()

    private fun play(
        player: quizarena.shared.PlayerIdentity,
        correct: Int,
        count: Int = 5,
        questionIds: List<String>? = null,
    ): quizarena.shared.AttemptState {
        var state = f.service.createAttempt(player, config(count = count, questionIds = questionIds))
        repeat(count) { i ->
            val option = if (i < correct) f.correctOption(state) else f.wrongOption(state)
            state = f.service.answer(player, state.id, AnswerRequest(i, option))
            state = f.service.next(player, state.id, NextRequest(i))
        }
        return state
    }

    @Test
    fun `history stats and clear`() {
        play(f.player, 5)
        play(f.player, 2)
        val history = f.service.history(f.player)
        assertEquals(2, history.stats.attempts)
        assertEquals(70, history.stats.averagePercentage)
        assertEquals(100, history.stats.bestPercentage)
        assertEquals(70, history.stats.overallAccuracy)
        assertEquals(10, history.byTopic.sumOf { it.total })
        assertEquals(2, history.attempts.size)

        assertEquals(2, f.service.clearHistory(f.player).deleted)
        assertEquals(0, f.service.history(f.player).stats.attempts)
    }

    @Test
    fun `leaderboard takes each player's best eligible attempt`() {
        // 1 correct scores at most 30 points; 5 correct at least 50, whatever the difficulties.
        val ada = f.newPlayer("Ada")
        play(f.player, 1)
        play(f.player, 5)
        play(ada, 1)
        play(ada, 5, count = 3) // too short to count

        val quinn = f.newPlayer("Quinn")
        f.service.finish(quinn, f.service.createAttempt(quinn, config()).id) // quit: excluded

        val board = f.service.leaderboard().entries
        assertEquals(listOf("Sam", "Ada"), board.map { it.name })
        assertEquals(listOf(1, 2), board.map { it.rank })
        assertEquals(100, board[0].percentage)
        assertEquals(2, board[0].attempts)
    }

    @Test
    fun `leaderboard reports your rank outside the top`() {
        repeat(3) { i -> play(f.newPlayer("Rival $i"), 5) } // at least 50 points each
        play(f.player, 1) // at most 30 points: last place

        val board = f.service.leaderboard(limit = 2, player = f.player)
        assertEquals(2, board.entries.size)
        assertFalse(board.entries.any { it.name == "Sam" })
        assertEquals("Sam", board.you!!.name)
        assertEquals(4, board.you!!.rank)
        assertEquals(4, board.players)
        assertNull(f.service.leaderboard(limit = 2).you)
    }

    @Test
    fun `retry runs do not count for the leaderboard`() {
        play(f.player, 5, questionIds = BANK_QUESTION_IDS.take(5))
        assertTrue(f.service.leaderboard().entries.isEmpty())
    }
}

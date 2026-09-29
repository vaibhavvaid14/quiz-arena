package quizarena.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import quizarena.shared.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.text.Normalizer
import java.util.Base64
import kotlin.random.Random

/**
 * Quiz business logic. The server is authoritative:
 *
 * - The browser never receives a correct answer or explanation until that
 *   question is resolved, so answers cannot be read out of devtools.
 * - Time is measured with the server's clock (minus a small latency allowance),
 *   so the client cannot claim it answered faster than it did.
 * - Clocks keep running while a tab is closed. Every call first "resolves
 *   expiry": a question or quiz whose time ran out is settled before anything else.
 * - Answer / next / finish are idempotent where it matters: a double click or a
 *   retried request returns the current state instead of failing.
 */
class QuizService(
    private val db: Db,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rng: Random = Random.Default,
) {
    private val json = Json { encodeDefaults = true }

    // ------------------------------------------------------------ catalog

    fun catalog(): CatalogResponse = db.read { conn ->
        val topics = conn.query(
            """SELECT t.id, t.name, t.icon, t.description,
                      COUNT(q.id) FILTER (WHERE q.difficulty = 'easy')   AS easy,
                      COUNT(q.id) FILTER (WHERE q.difficulty = 'medium') AS medium,
                      COUNT(q.id) FILTER (WHERE q.difficulty = 'hard')   AS hard
                 FROM topics t
                 LEFT JOIN questions q ON q.topic_id = t.id AND q.is_active = 1
                GROUP BY t.id
                ORDER BY t.position""",
        ) { rs ->
            TopicSummary(
                id = rs.getString("id"),
                name = rs.getString("name"),
                icon = rs.getString("icon"),
                description = rs.getString("description"),
                counts = DifficultyCounts(rs.getInt("easy"), rs.getInt("medium"), rs.getInt("hard")),
            )
        }
        CatalogResponse(topics, Rules.publicRules())
    }

    // ------------------------------------------------------------ players

    fun createPlayer(request: CreatePlayerRequest): PlayerCreated {
        val name = cleanName(request.name ?: throw ValidationException("'name' must be a string."))
        val key = token(24)
        return db.transaction { conn ->
            val taken = conn.queryOne("SELECT 1 FROM players WHERE name = ? COLLATE NOCASE", name) { true } ?: false
            if (taken) {
                throw ConflictException("The name \"$name\" is already taken. Please choose another.", "name_taken")
            }
            conn.update(
                "INSERT INTO players (name, key_hash, created_at) VALUES (?, ?, ?)",
                name, hashKey(key), clock(),
            )
            val id = conn.queryOne("SELECT last_insert_rowid() AS id") { it.getLong("id") }!!
            PlayerCreated(id, name, key)
        }
    }

    fun authenticate(key: String?): PlayerIdentity {
        if (key.isNullOrBlank()) {
            throw UnauthorizedException("Missing player key. Set your name on the setup screen first.")
        }
        return db.read { conn ->
            conn.queryOne("SELECT id, name FROM players WHERE key_hash = ?", hashKey(key)) {
                PlayerIdentity(it.getLong("id"), it.getString("name"))
            }
        } ?: throw UnauthorizedException("Unknown player key. Set your name on the setup screen again.")
    }

    // ------------------------------------------------------------ attempts

    fun createAttempt(player: PlayerIdentity, request: CreateAttemptRequest): AttemptState {
        val config = validateConfig(request)
        val questionIds = request.questionIds
        if (questionIds != null && questionIds.size > Rules.MAX_QUESTIONS) {
            throw ValidationException("At most ${Rules.MAX_QUESTIONS} questions per quiz.")
        }

        val attemptId = token(12)
        db.transaction { conn ->
            val known = conn.query("SELECT id FROM topics") { it.getString("id") }.toSet()
            val unknown = config.topics.filterNot { it in known }
            if (unknown.isNotEmpty()) {
                throw ValidationException("Unknown topic(s): ${unknown.joinToString(", ")}.")
            }

            val pool = if (questionIds != null) {
                if (questionIds.isEmpty()) emptyList() else conn.query(
                    "SELECT id, difficulty, position FROM questions WHERE is_active = 1 AND id IN (${placeholders(questionIds.size)})",
                    *questionIds.toTypedArray(),
                ) { it.toPick() }
            } else {
                val params = mutableListOf<Any?>().apply { addAll(config.topics) }
                var sql = "SELECT id, difficulty, position FROM questions WHERE is_active = 1 " +
                    "AND topic_id IN (${placeholders(config.topics.size)})"
                if (config.difficulty != "mixed") {
                    sql += " AND difficulty = ?"
                    params.add(config.difficulty)
                }
                conn.query(sql, *params.toTypedArray()) { it.toPick() }
            }
            if (pool.isEmpty()) {
                throw ValidationException("No questions match the selected topics and difficulty.")
            }

            var picked = pool.shuffled(rng).take(minOf(config.count, pool.size))
            if (!config.shuffle) {
                picked = picked.sortedWith(
                    compareBy({ Rules.DIFFICULTIES.indexOf(it.difficulty) }, { it.position }),
                )
            }

            val options = optionsFor(conn, picked.map { it.id })
            val now = clock()

            // One unfinished quiz per player: starting a new one discards the old.
            conn.update("DELETE FROM attempts WHERE player_id = ? AND status = 'active'", player.id)
            conn.update(
                """INSERT INTO attempts (id, player_id, topics, difficulty, timer_mode, seconds_per_question,
                     shuffle, negative_marking, is_retry, question_count, started_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                attemptId,
                player.id,
                json.encodeToString(config.topics),
                config.difficulty,
                config.timerMode,
                config.secondsPerQuestion,
                if (config.shuffle) 1 else 0,
                if (config.negativeMarking) 1 else 0,
                if (questionIds != null) 1 else 0,
                picked.size,
                now,
            )
            picked.forEachIndexed { position, question ->
                var optionIds = options.getValue(question.id).map { it.id }
                if (config.shuffle) optionIds = optionIds.shuffled(rng)
                conn.update(
                    "INSERT INTO attempt_questions (attempt_id, position, question_id, option_order, served_at) VALUES (?, ?, ?, ?, ?)",
                    attemptId, position, question.id, json.encodeToString(optionIds),
                    if (position == 0) now else null,
                )
            }
        }
        return getState(player, attemptId)
    }

    fun getState(player: PlayerIdentity, attemptId: String): AttemptState = db.transaction { conn ->
        var (attempt, items) = load(conn, player.id, attemptId)
        resolveExpiry(conn, attempt, items).let { attempt = it.first; items = it.second }
        state(conn, attempt, items)
    }

    fun answer(player: PlayerIdentity, attemptId: String, request: AnswerRequest): AttemptState = db.transaction { conn ->
        val position = request.position ?: throw ValidationException("'position' must be an integer.")
        val optionId = request.optionId

        var (attempt, items) = load(conn, player.id, attemptId)
        resolveExpiry(conn, attempt, items).let { attempt = it.first; items = it.second }
        if (attempt.status != "active") return@transaction state(conn, attempt, items)
        if (position != attempt.currentIndex) {
            throw ConflictException("That question is no longer current. Reload to continue.", "stale_position")
        }

        val item = items[position]
        // Double submit, or the question timed out while the request was in flight.
        if (item.status != "pending") return@transaction state(conn, attempt, items)

        val now = clock()
        val elapsed = maxOf(0L, now - (item.servedAt ?: now) - Rules.LATENCY_ALLOWANCE_MS)
        val limit = questionLimit(attempt)

        val status: String
        val selected: Long?
        val points: Int
        val spent: Long
        if (optionId == null) {
            status = "timeout"
            selected = null
            points = 0
            spent = if (limit != null) minOf(elapsed, limit) else elapsed
        } else {
            val order: List<Long> = json.decodeFromString(item.optionOrder)
            if (optionId !in order) {
                throw ValidationException("That option does not belong to this question.")
            }
            val correct = conn.queryOne("SELECT is_correct FROM question_options WHERE id = ?", optionId) {
                it.getInt("is_correct") != 0
            } ?: false
            status = if (correct) "correct" else "wrong"
            selected = optionId
            spent = if (limit != null) minOf(elapsed, limit) else elapsed
            val remainingFraction = if (limit != null && limit > 0) 1 - spent.toDouble() / limit else 0.0
            points = Rules.scoreAnswer(status, item.difficulty, attempt.timerMode, attempt.negativeMarking, remainingFraction)
        }

        conn.update(
            """UPDATE attempt_questions SET status = ?, selected_option_id = ?, points = ?,
                 time_spent_ms = ?, answered_at = ? WHERE attempt_id = ? AND position = ?""",
            status, selected, points, spent, now, attemptId, position,
        )
        val reloaded = load(conn, player.id, attemptId)
        state(conn, reloaded.first, reloaded.second)
    }

    fun next(player: PlayerIdentity, attemptId: String, request: NextRequest): AttemptState = db.transaction { conn ->
        val position = request.position ?: throw ValidationException("'position' must be an integer.")
        var (attempt, items) = load(conn, player.id, attemptId)
        resolveExpiry(conn, attempt, items).let { attempt = it.first; items = it.second }

        // already finished / already advanced (double click)
        if (attempt.status != "active" || position == attempt.currentIndex - 1) {
            return@transaction state(conn, attempt, items)
        }
        if (position != attempt.currentIndex) {
            throw ConflictException("That question is no longer current. Reload to continue.", "stale_position")
        }
        if (items[position].status == "pending") {
            throw ConflictException("Answer the current question before moving on.", "unanswered")
        }

        if (position == items.size - 1) {
            finishAttempt(conn, attempt, "completed").let { attempt = it.first; items = it.second }
        } else {
            conn.update("UPDATE attempts SET current_index = ? WHERE id = ?", position + 1, attemptId)
            conn.update(
                "UPDATE attempt_questions SET served_at = ? WHERE attempt_id = ? AND position = ?",
                clock(), attemptId, position + 1,
            )
            load(conn, player.id, attemptId).let { attempt = it.first; items = it.second }
        }
        state(conn, attempt, items)
    }

    /** Ends the quiz now: time-up if the whole-quiz clock has (about) run out, else quit. */
    fun finish(player: PlayerIdentity, attemptId: String): AttemptState = db.transaction { conn ->
        var (attempt, items) = load(conn, player.id, attemptId)
        resolveExpiry(conn, attempt, items).let { attempt = it.first; items = it.second }
        if (attempt.status == "active") {
            val sessionLimit = sessionLimit(attempt)
            val now = clock()
            if (sessionLimit != null && sessionElapsed(attempt, items, now) + Rules.DEADLINE_GRACE_MS >= sessionLimit) {
                timeUp(conn, attempt, items, now).let { attempt = it.first; items = it.second }
            } else {
                val reason = if (items.all { it.status in RESOLVED }) "completed" else "quit"
                finishAttempt(conn, attempt, reason).let { attempt = it.first; items = it.second }
            }
        }
        state(conn, attempt, items)
    }

    fun discard(player: PlayerIdentity, attemptId: String) = db.transaction { conn ->
        val (attempt, _) = load(conn, player.id, attemptId)
        if (attempt.status != "active") {
            throw ConflictException("Only an unfinished quiz can be discarded.")
        }
        conn.update("DELETE FROM attempts WHERE id = ?", attemptId)
        Unit
    }

    fun results(player: PlayerIdentity, attemptId: String): ResultsSummary = db.transaction { conn ->
        var (attempt, items) = load(conn, player.id, attemptId)
        resolveExpiry(conn, attempt, items).let { attempt = it.first; items = it.second }
        if (attempt.status != "finished") {
            throw ConflictException("This quiz has not finished yet.", "not_finished")
        }
        summary(conn, attempt, items, withReview = true)
    }

    // ------------------------------------------------ history & leaderboard

    fun history(player: PlayerIdentity, limit: Int = 50): HistoryResponse = db.read { conn ->
        val attempts = conn.query(
            """SELECT * FROM attempts WHERE player_id = ? AND status = 'finished'
                ORDER BY finished_at DESC LIMIT ?""",
            player.id, limit,
        ) { it.toAttempt() }

        val stats = conn.queryOne(
            """SELECT COUNT(*) AS attempts, COALESCE(SUM(question_count), 0) AS questions,
                      COALESCE(SUM(correct_count), 0) AS correct,
                      COALESCE(ROUND(AVG(percentage)), 0) AS average, COALESCE(MAX(percentage), 0) AS best
                 FROM attempts WHERE player_id = ? AND status = 'finished'""",
            player.id,
        ) { rs ->
            val questions = rs.getInt("questions")
            val correct = rs.getInt("correct")
            HistoryStats(
                attempts = rs.getInt("attempts"),
                questions = questions,
                correct = correct,
                averagePercentage = rs.getInt("average"),
                bestPercentage = rs.getInt("best"),
                overallAccuracy = percent(correct, questions),
            )
        }!!

        val mastery = conn.query(
            """SELECT t.id AS key, t.name AS label, t.icon AS icon,
                      COUNT(*) AS total, SUM(aq.status = 'correct') AS correct, COUNT(DISTINCT a.id) AS attempts
                 FROM attempts a
                 JOIN attempt_questions aq ON aq.attempt_id = a.id
                 JOIN questions q ON q.id = aq.question_id
                 JOIN topics t ON t.id = q.topic_id
                WHERE a.player_id = ? AND a.status = 'finished'
                GROUP BY t.id
                ORDER BY t.position""",
            player.id,
        ) { rs ->
            val total = rs.getInt("total")
            val correct = rs.getInt("correct")
            TopicMastery(
                key = rs.getString("key"),
                label = rs.getString("label"),
                icon = rs.getString("icon"),
                correct = correct,
                total = total,
                attempts = rs.getInt("attempts"),
                percentage = percent(correct, total),
            )
        }

        HistoryResponse(
            player = HistoryPlayer(player.name),
            stats = stats,
            byTopic = mastery,
            attempts = attempts.map { attemptRow(it) },
        )
    }

    fun clearHistory(player: PlayerIdentity): DeletedCount = db.transaction { conn ->
        DeletedCount(conn.update("DELETE FROM attempts WHERE player_id = ? AND status = 'finished'", player.id))
    }

    /**
     * Each player's single best eligible attempt, ranked by points.
     *
     * With a player, also reports that player's own rank even when it falls
     * outside the top [limit] ("you are #34 of 57").
     */
    fun leaderboard(limit: Int = 20, player: PlayerIdentity? = null): LeaderboardResponse = db.read { conn ->
        val capped = limit.coerceIn(1, 100)
        val rows = conn.query(
            """WITH best AS (
                 SELECT a.player_id, p.name, a.total_points, a.percentage, a.question_count, a.difficulty,
                        a.timer_mode, a.finished_at,
                        COUNT(*) OVER (PARTITION BY a.player_id) AS attempts,
                        ROW_NUMBER() OVER (PARTITION BY a.player_id
                                           ORDER BY a.total_points DESC, a.percentage DESC, a.finished_at ASC) AS rn
                   FROM attempts a JOIN players p ON p.id = a.player_id
                  WHERE a.status = 'finished' AND a.end_reason <> 'quit' AND a.is_retry = 0
                    AND a.question_count >= ?),
               ranked AS (
                 SELECT *, ROW_NUMBER() OVER (ORDER BY total_points DESC, percentage DESC, finished_at ASC) AS rank,
                        COUNT(*) OVER () AS players
                   FROM best WHERE rn = 1)
               SELECT * FROM ranked WHERE rank <= ? OR player_id = ? ORDER BY rank""",
            Rules.LEADERBOARD_MIN_QUESTIONS, capped, player?.id,
        ) { rs ->
            rs.getLong("player_id") to LeaderboardEntry(
                rank = rs.getInt("rank"),
                name = rs.getString("name"),
                points = rs.getInt("total_points"),
                percentage = rs.getInt("percentage"),
                questionCount = rs.getInt("question_count"),
                difficulty = rs.getString("difficulty"),
                timerMode = rs.getString("timer_mode"),
                finishedAt = rs.longOrNull("finished_at"),
                attempts = rs.getInt("attempts"),
            )
        }

        LeaderboardResponse(
            entries = rows.filter { it.second.rank <= capped }.map { it.second },
            you = player?.let { p -> rows.firstOrNull { it.first == p.id }?.second },
            players = rows.firstOrNull()?.let { firstPlayersCount(conn, capped, player) } ?: 0,
            minQuestions = Rules.LEADERBOARD_MIN_QUESTIONS,
        )
    }

    // The `players` window value is identical on every row; re-reading it keeps
    // the row mapper above returning a plain entry.
    private fun firstPlayersCount(conn: java.sql.Connection, limit: Int, player: PlayerIdentity?): Int =
        conn.queryOne(
            """WITH best AS (
                 SELECT a.player_id,
                        ROW_NUMBER() OVER (PARTITION BY a.player_id
                                           ORDER BY a.total_points DESC, a.percentage DESC, a.finished_at ASC) AS rn
                   FROM attempts a
                  WHERE a.status = 'finished' AND a.end_reason <> 'quit' AND a.is_retry = 0
                    AND a.question_count >= ?)
               SELECT COUNT(*) AS players FROM best WHERE rn = 1""",
            Rules.LEADERBOARD_MIN_QUESTIONS,
        ) { it.getInt("players") } ?: 0

    // ------------------------------------------------------------ internals

    private fun load(conn: java.sql.Connection, playerId: Long, attemptId: String): Pair<AttemptRow, List<ItemRow>> {
        // Same answer whether it doesn't exist or belongs to someone else.
        val attempt = conn.queryOne(
            "SELECT * FROM attempts WHERE id = ? AND player_id = ?", attemptId, playerId,
        ) { it.toAttempt() } ?: throw NotFoundException("Quiz not found.")
        val items = conn.query(
            """SELECT aq.*, q.topic_id, q.difficulty, q.prompt, q.code, q.explanation
                 FROM attempt_questions aq JOIN questions q ON q.id = aq.question_id
                WHERE aq.attempt_id = ? ORDER BY aq.position""",
            attemptId,
        ) { it.toItem() }
        return attempt to items
    }

    private fun optionsFor(conn: java.sql.Connection, questionIds: List<String>): Map<String, List<OptionRow>> {
        if (questionIds.isEmpty()) return emptyMap()
        val rows = conn.query(
            "SELECT id, question_id, text, is_correct FROM question_options " +
                "WHERE question_id IN (${placeholders(questionIds.size)}) ORDER BY position",
            *questionIds.toTypedArray(),
        ) { rs ->
            OptionRow(rs.getLong("id"), rs.getString("question_id"), rs.getString("text"), rs.bool("is_correct"))
        }
        return questionIds.associateWith { qid -> rows.filter { it.questionId == qid } }
    }

    /** Settles clocks that ran out while nobody was looking. Call inside a transaction. */
    private fun resolveExpiry(
        conn: java.sql.Connection,
        attempt: AttemptRow,
        items: List<ItemRow>,
    ): Pair<AttemptRow, List<ItemRow>> {
        if (attempt.status != "active") return attempt to items
        val now = clock()
        val current = items[attempt.currentIndex]

        val session = sessionLimit(attempt)
        if (session != null && sessionElapsed(attempt, items, now) >= session + Rules.DEADLINE_GRACE_MS) {
            return timeUp(conn, attempt, items, now)
        }

        val limit = questionLimit(attempt)
        if (limit != null && current.status == "pending" && current.servedAt != null) {
            if (now - current.servedAt >= limit + Rules.DEADLINE_GRACE_MS) {
                conn.update(
                    """UPDATE attempt_questions SET status = 'timeout', points = 0, time_spent_ms = ?, answered_at = ?
                        WHERE attempt_id = ? AND position = ?""",
                    limit, current.servedAt + limit, attempt.id, current.position,
                )
                return load(conn, attempt.playerId, attempt.id)
            }
        }
        return attempt to items
    }

    private fun timeUp(
        conn: java.sql.Connection,
        attempt: AttemptRow,
        items: List<ItemRow>,
        now: Long,
    ): Pair<AttemptRow, List<ItemRow>> {
        var current = items[attempt.currentIndex]
        var a = attempt
        if (current.status == "pending") {
            val usedBefore = items.filter { it.status in RESOLVED }.sumOf { it.timeSpentMs }
            conn.update(
                """UPDATE attempt_questions SET status = 'timeout', points = 0, time_spent_ms = ?, answered_at = ?
                    WHERE attempt_id = ? AND position = ?""",
                maxOf(0L, (sessionLimit(attempt) ?: 0L) - usedBefore), now, attempt.id, current.position,
            )
            a = load(conn, attempt.playerId, attempt.id).first
        }
        return finishAttempt(conn, a, "time-up")
    }

    private fun finishAttempt(
        conn: java.sql.Connection,
        attempt: AttemptRow,
        reason: String,
    ): Pair<AttemptRow, List<ItemRow>> {
        val now = clock()
        conn.update(
            "UPDATE attempt_questions SET status = 'skipped', points = 0 WHERE attempt_id = ? AND status = 'pending'",
            attempt.id,
        )
        val (reloaded, items) = load(conn, attempt.playerId, attempt.id)
        val s = summary(conn, reloaded.copy(endReason = reason), items, withReview = false)
        conn.update(
            """UPDATE attempts SET status = 'finished', end_reason = ?, finished_at = ?, correct_count = ?,
                 total_points = ?, max_points = ?, percentage = ?, duration_ms = ? WHERE id = ?""",
            reason, now, s.correct, s.points, s.maxPoints, s.percentage, s.durationMs, attempt.id,
        )
        return load(conn, attempt.playerId, attempt.id)
    }

    private fun configOf(attempt: AttemptRow) = QuizConfig(
        topics = json.decodeFromString(attempt.topics),
        difficulty = attempt.difficulty,
        count = attempt.questionCount,
        timerMode = attempt.timerMode,
        secondsPerQuestion = attempt.secondsPerQuestion,
        shuffle = attempt.shuffle,
        negativeMarking = attempt.negativeMarking,
        isRetry = attempt.isRetry,
    )

    private fun state(conn: java.sql.Connection, attempt: AttemptRow, items: List<ItemRow>): AttemptState {
        val now = clock()
        val answered = items.count { it.status in ANSWERED }
        val base = AttemptState(
            id = attempt.id,
            status = attempt.status,
            endReason = attempt.endReason,
            config = configOf(attempt),
            currentIndex = attempt.currentIndex,
            total = items.size,
            live = LiveStats(
                points = maxOf(0, items.sumOf { it.points }),
                correct = items.count { it.status == "correct" },
                answered = answered,
                streak = currentStreak(items),
            ),
            clock = ClockState(
                questionLimitMs = questionLimit(attempt),
                sessionLimitMs = sessionLimit(attempt),
                sessionElapsedMs = sessionElapsed(attempt, items, now),
                questionElapsedMs = 0,
            ),
            current = null,
        )
        if (attempt.status != "active") return base

        val item = items[attempt.currentIndex]
        val options = optionsFor(conn, listOf(item.questionId)).getValue(item.questionId).associateBy { it.id }
        val resolved = item.status != "pending"
        val order: List<Long> = json.decodeFromString(item.optionOrder)
        return base.copy(
            clock = base.clock.copy(
                questionElapsedMs = if (resolved) item.timeSpentMs else liveElapsed(item, now),
            ),
            current = CurrentItem(
                position = item.position,
                status = item.status,
                points = item.points,
                isLast = item.position == items.size - 1,
                question = QuestionView(
                    id = item.questionId,
                    topic = item.topicId,
                    difficulty = item.difficulty,
                    prompt = item.prompt,
                    code = item.code,
                    options = order.mapNotNull { id -> options[id]?.let { OptionView(id, it.text) } },
                ),
                selectedOptionId = item.selectedOptionId,
                // Revealed only once the question is resolved.
                correctOptionId = if (resolved) options.values.firstOrNull { it.isCorrect }?.id else null,
                explanation = if (resolved) item.explanation else null,
            ),
        )
    }

    private fun summary(
        conn: java.sql.Connection,
        attempt: AttemptRow,
        items: List<ItemRow>,
        withReview: Boolean,
    ): ResultsSummary {
        val counts = mutableMapOf("correct" to 0, "wrong" to 0, "timeout" to 0, "skipped" to 0, "pending" to 0)
        val byTopic = linkedMapOf<String, IntArray>() // [correct, total]
        val byDifficulty = Rules.DIFFICULTIES.associateWith { IntArray(2) }
        var points = 0
        var maxPoints = 0
        var duration = 0L

        for (item in items) {
            counts[item.status] = (counts[item.status] ?: 0) + 1
            points += item.points
            maxPoints += Rules.maxPointsFor(item.difficulty, attempt.timerMode)
            duration += item.timeSpentMs
            val hit = if (item.status == "correct") 1 else 0
            byTopic.getOrPut(item.topicId) { IntArray(2) }.let { it[0] += hit; it[1] += 1 }
            byDifficulty.getValue(item.difficulty).let { it[0] += hit; it[1] += 1 }
        }

        val total = items.size
        val answered = counts.getValue("correct") + counts.getValue("wrong")
        val percentage = percent(counts.getValue("correct"), total)
        val topics = conn.query("SELECT id, name, icon, position FROM topics") { rs ->
            rs.getString("id") to Triple(rs.getString("name"), rs.getString("icon"), rs.getInt("position"))
        }.toMap()

        val review = if (!withReview) null else {
            val options = optionsFor(conn, items.map { it.questionId })
            items.map { item ->
                val order: List<Long> = json.decodeFromString(item.optionOrder)
                val byId = options.getValue(item.questionId).associateBy { it.id }
                ReviewItem(
                    number = item.position + 1,
                    questionId = item.questionId,
                    topic = item.topicId,
                    difficulty = item.difficulty,
                    question = item.prompt,
                    code = item.code,
                    explanation = item.explanation,
                    status = item.status,
                    points = item.points,
                    timeSpentMs = item.timeSpentMs,
                    options = order.mapNotNull { id ->
                        byId[id]?.let { ReviewOption(it.text, it.isCorrect, id == item.selectedOptionId) }
                    },
                )
            }
        }

        return ResultsSummary(
            id = attempt.id,
            config = configOf(attempt),
            endReason = attempt.endReason,
            finishedAt = attempt.finishedAt,
            total = total,
            answered = answered,
            correct = counts.getValue("correct"),
            wrong = counts.getValue("wrong"),
            timedOut = counts.getValue("timeout"),
            skipped = counts.getValue("skipped") + counts.getValue("pending"),
            percentage = percentage,
            accuracy = percent(counts.getValue("correct"), answered),
            points = maxOf(0, points),
            maxPoints = maxPoints,
            grade = Rules.gradeFor(percentage),
            bestStreak = bestStreak(items),
            durationMs = duration,
            averageTimeMs = if (total > 0) Math.round(duration.toDouble() / total) else 0,
            byTopic = byTopic.entries
                .sortedBy { topics[it.key]?.third ?: 0 }
                .map { (key, b) ->
                    TopicBreakdown(key, topics[key]?.first ?: key, topics[key]?.second, b[0], b[1], percent(b[0], b[1]))
                },
            byDifficulty = byDifficulty.entries
                .filter { it.value[1] > 0 }
                .map { (d, b) ->
                    DifficultyBreakdown(d, d.replaceFirstChar { it.uppercase() }, b[0], b[1], percent(b[0], b[1]))
                },
            missedQuestionIds = items.filter { it.status != "correct" }.map { it.questionId },
            review = review,
        )
    }

    private fun attemptRow(a: AttemptRow) = HistoryAttempt(
        id = a.id,
        finishedAt = a.finishedAt,
        endReason = a.endReason,
        config = configOf(a),
        total = a.questionCount,
        correct = a.correctCount ?: 0,
        percentage = a.percentage ?: 0,
        points = a.totalPoints ?: 0,
        maxPoints = a.maxPoints ?: 0,
        grade = Rules.gradeFor(a.percentage ?: 0).grade,
        durationMs = a.durationMs ?: 0,
    )

    // ------------------------------------------------------------- helpers

    private fun validateConfig(r: CreateAttemptRequest): QuizConfig {
        val topics = r.topics
        if (topics.isNullOrEmpty()) throw ValidationException("Choose at least one topic.")
        if (r.difficulty !in Rules.DIFFICULTY_FILTERS) {
            throw ValidationException("'difficulty' must be one of ${Rules.DIFFICULTY_FILTERS.joinToString(", ")}.")
        }
        if (r.timerMode !in Rules.TIMER_MODES) {
            throw ValidationException("'timerMode' must be one of ${Rules.TIMER_MODES.joinToString(", ")}.")
        }
        val count = r.count
        if (count == null || count !in Rules.MIN_QUESTIONS..Rules.MAX_QUESTIONS) {
            throw ValidationException("'count' must be an integer between ${Rules.MIN_QUESTIONS} and ${Rules.MAX_QUESTIONS}.")
        }
        val seconds = r.secondsPerQuestion
        if (seconds == null || seconds !in Rules.MIN_SECONDS..Rules.MAX_SECONDS) {
            throw ValidationException("'secondsPerQuestion' must be an integer between ${Rules.MIN_SECONDS} and ${Rules.MAX_SECONDS}.")
        }
        return QuizConfig(
            topics = topics.distinct(),
            difficulty = r.difficulty!!,
            count = count,
            timerMode = r.timerMode!!,
            secondsPerQuestion = seconds,
            shuffle = r.shuffle,
            negativeMarking = r.negativeMarking,
        )
    }

    private fun cleanName(raw: String): String {
        val name = Normalizer.normalize(raw, Normalizer.Form.NFC).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (name.isEmpty() || name.length > Rules.PLAYER_NAME_MAX) {
            throw ValidationException("Name must be 1-${Rules.PLAYER_NAME_MAX} characters.")
        }
        // Rejects Unicode category C* (control, format, surrogate, private use, unassigned).
        if (name.any { Character.getType(it) in CONTROL_TYPES }) {
            throw ValidationException("Name contains invalid characters.")
        }
        return name
    }

    private fun questionLimit(a: AttemptRow): Long? =
        if (a.timerMode == "question") a.secondsPerQuestion * 1000L else null

    private fun sessionLimit(a: AttemptRow): Long? =
        if (a.timerMode == "session") a.secondsPerQuestion * 1000L * a.questionCount else null

    private fun liveElapsed(item: ItemRow, now: Long): Long =
        item.servedAt?.let { maxOf(0L, now - it) } ?: 0L

    /**
     * Time charged to the whole quiz: resolved questions plus the live one.
     * Time spent reading feedback (between answering and the next question) is free.
     */
    private fun sessionElapsed(attempt: AttemptRow, items: List<ItemRow>, now: Long): Long {
        var spent = items.filter { it.status in RESOLVED }.sumOf { it.timeSpentMs }
        if (attempt.status == "active") {
            val current = items[attempt.currentIndex]
            if (current.status == "pending") spent += liveElapsed(current, now)
        }
        return spent
    }

    private fun currentStreak(items: List<ItemRow>): Int {
        var streak = 0
        for (item in items) {
            when {
                item.status == "correct" -> streak++
                item.status != "pending" -> streak = 0
            }
        }
        return streak
    }

    private fun bestStreak(items: List<ItemRow>): Int {
        var best = 0
        var run = 0
        for (item in items) {
            run = if (item.status == "correct") run + 1 else 0
            best = maxOf(best, run)
        }
        return best
    }

    private fun percent(part: Int, whole: Int): Int =
        if (whole > 0) Rules.roundHalfUp(part * 100.0 / whole) else 0

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

    private fun token(bytes: Int): String {
        val buf = ByteArray(bytes).also { SECURE_RANDOM.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun hashKey(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val RESOLVED = setOf("correct", "wrong", "timeout", "skipped")
        val ANSWERED = setOf("correct", "wrong", "timeout")
        val SECURE_RANDOM = SecureRandom()
        val CONTROL_TYPES = setOf(
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
            Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(),
        )
    }
}

// ----------------------------------------------------------- row mapping

internal data class Pick(val id: String, val difficulty: String, val position: Int)

internal data class OptionRow(val id: Long, val questionId: String, val text: String, val isCorrect: Boolean)

internal data class AttemptRow(
    val id: String,
    val playerId: Long,
    val status: String,
    val endReason: String?,
    val topics: String,
    val difficulty: String,
    val timerMode: String,
    val secondsPerQuestion: Int,
    val shuffle: Boolean,
    val negativeMarking: Boolean,
    val isRetry: Boolean,
    val questionCount: Int,
    val currentIndex: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val correctCount: Int?,
    val totalPoints: Int?,
    val maxPoints: Int?,
    val percentage: Int?,
    val durationMs: Long?,
)

internal data class ItemRow(
    val position: Int,
    val questionId: String,
    val optionOrder: String,
    val servedAt: Long?,
    val answeredAt: Long?,
    val selectedOptionId: Long?,
    val status: String,
    val timeSpentMs: Long,
    val points: Int,
    val topicId: String,
    val difficulty: String,
    val prompt: String,
    val code: String?,
    val explanation: String,
)

internal fun ResultSet.toPick() = Pick(getString("id"), getString("difficulty"), getInt("position"))

internal fun ResultSet.toAttempt() = AttemptRow(
    id = getString("id"),
    playerId = getLong("player_id"),
    status = getString("status"),
    endReason = getString("end_reason"),
    topics = getString("topics"),
    difficulty = getString("difficulty"),
    timerMode = getString("timer_mode"),
    secondsPerQuestion = getInt("seconds_per_question"),
    shuffle = bool("shuffle"),
    negativeMarking = bool("negative_marking"),
    isRetry = bool("is_retry"),
    questionCount = getInt("question_count"),
    currentIndex = getInt("current_index"),
    startedAt = getLong("started_at"),
    finishedAt = longOrNull("finished_at"),
    correctCount = intOrNull("correct_count"),
    totalPoints = intOrNull("total_points"),
    maxPoints = intOrNull("max_points"),
    percentage = intOrNull("percentage"),
    durationMs = longOrNull("duration_ms"),
)

internal fun ResultSet.toItem() = ItemRow(
    position = getInt("position"),
    questionId = getString("question_id"),
    optionOrder = getString("option_order"),
    servedAt = longOrNull("served_at"),
    answeredAt = longOrNull("answered_at"),
    selectedOptionId = longOrNull("selected_option_id"),
    status = getString("status"),
    timeSpentMs = getLong("time_spent_ms"),
    points = getInt("points"),
    topicId = getString("topic_id"),
    difficulty = getString("difficulty"),
    prompt = getString("prompt"),
    code = getString("code"),
    explanation = getString("explanation"),
)

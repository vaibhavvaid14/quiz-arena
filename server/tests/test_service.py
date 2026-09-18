import unittest

from server import rules
from server.errors import Conflict, NotFound, Unauthorized, ValidationError
from server.tests import helpers
from server.tests.helpers import config, correct_option, wrong_option


class ServiceTestCase(unittest.TestCase):
    def setUp(self):
        self.service, self.conn, self.clock = helpers.make_service()
        self.player = self.service.create_player({"name": "Sam"})

    def start(self, **overrides):
        return self.service.create_attempt(self.player, config(**overrides))

    def answer(self, state, right=True, after_ms=0):
        self.clock.advance(after_ms)
        option = correct_option(self.conn, state) if right else wrong_option(self.conn, state)
        return self.service.answer(self.player, state["id"], {"position": state["currentIndex"], "optionId": option})

    def next(self, state):
        return self.service.next(self.player, state["id"], {"position": state["currentIndex"]})


class PlayerTests(ServiceTestCase):
    def test_create_and_authenticate(self):
        self.assertEqual(self.service.authenticate(self.player["key"])["name"], "Sam")
        self.assertNotIn(self.player["key"], str(self.conn.execute("SELECT * FROM players").fetchall()[0][:]))

    def test_names_are_cleaned_and_unique(self):
        p = self.service.create_player({"name": "  Ada   Lovelace "})
        self.assertEqual(p["name"], "Ada Lovelace")
        with self.assertRaises(Conflict):
            self.service.create_player({"name": "sam"})
        for bad in ("", "   ", "x" * 31, "bell\x07"):
            with self.assertRaises(ValidationError):
                self.service.create_player({"name": bad})

    def test_bad_keys_are_rejected(self):
        for key in ("", "nope"):
            with self.assertRaises(Unauthorized):
                self.service.authenticate(key)


class CreateAttemptTests(ServiceTestCase):
    def test_state_hides_answers_until_resolved(self):
        state = self.start()
        current = state["current"]
        self.assertEqual(state["status"], "active")
        self.assertEqual(state["total"], 5)
        self.assertIsNone(current["correctOptionId"])
        self.assertIsNone(current["explanation"])
        self.assertEqual(len(current["question"]["options"]), 4)
        self.assertNotIn("isCorrect", str(current["question"]["options"]))

    def test_count_is_capped_by_the_pool_and_questions_are_unique(self):
        state = self.start(count=50)
        self.assertEqual(state["total"], 8)
        ids = [r[0] for r in self.conn.execute("SELECT question_id FROM attempt_questions WHERE attempt_id = ?", (state["id"],))]
        self.assertEqual(len(set(ids)), 8)

    def test_filters_by_topic_and_difficulty(self):
        state = self.start(topics=["beta"], difficulty="hard")
        ids = {r[0] for r in self.conn.execute("SELECT question_id FROM attempt_questions WHERE attempt_id = ?", (state["id"],))}
        self.assertEqual(ids, {"b-h1", "b-h2"})

    def test_unshuffled_runs_easy_to_hard_with_original_option_order(self):
        state = self.start(count=8, shuffle=False)
        rows = self.conn.execute(
            """SELECT q.difficulty, aq.option_order FROM attempt_questions aq JOIN questions q ON q.id = aq.question_id
                WHERE aq.attempt_id = ? ORDER BY aq.position""",
            (state["id"],),
        ).fetchall()
        self.assertEqual([r[0] for r in rows], ["easy"] * 3 + ["medium"] * 2 + ["hard"] * 3)
        for _, order in rows:
            ids = [int(x) for x in order.strip("[]").split(",")]
            self.assertEqual(ids, sorted(ids))

    def test_validation(self):
        bad_configs = [
            config(topics=[]),
            config(topics=["ghost"]),
            config(difficulty="insane"),
            config(count=0),
            config(count=True),
            config(timerMode="sometimes"),
            config(secondsPerQuestion=2),
            config(shuffle="yes"),
        ]
        for bad in bad_configs:
            with self.assertRaises(ValidationError, msg=bad):
                self.service.create_attempt(self.player, bad)

    def test_retry_with_pinned_question_ids(self):
        state = self.service.create_attempt(self.player, {**config(), "questionIds": ["b-h2", "a-e1", "missing"]})
        self.assertEqual(state["total"], 2)
        self.assertTrue(state["config"]["isRetry"])

    def test_one_active_attempt_per_player(self):
        first = self.start()
        self.start()
        with self.assertRaises(NotFound):
            self.service.get_state(self.player, first["id"])

    def test_attempts_are_private_to_their_player(self):
        state = self.start()
        other = self.service.create_player({"name": "Eve"})
        with self.assertRaises(NotFound):
            self.service.get_state(other, state["id"])


class AnswerTests(ServiceTestCase):
    def test_correct_answer_reveals_and_scores(self):
        state = self.start()
        after = self.answer(state, right=True, after_ms=2000)
        current = after["current"]
        self.assertEqual(current["status"], "correct")
        self.assertEqual(current["selectedOptionId"], current["correctOptionId"])
        self.assertIsNotNone(current["explanation"])
        self.assertEqual(after["live"]["correct"], 1)
        self.assertGreater(current["points"], 0)

    def test_wrong_answer_and_negative_marking(self):
        state = self.start(negativeMarking=True, topics=["alpha"], difficulty="medium", count=1)
        after = self.answer(state, right=False)
        self.assertEqual(after["current"]["status"], "wrong")
        self.assertEqual(after["current"]["points"], -5)  # 25% of 20
        self.assertEqual(after["live"]["points"], 0)  # total never below zero

    def test_speed_bonus_uses_server_time(self):
        state = self.start(timerMode="question", secondsPerQuestion=10, topics=["beta"], difficulty="hard", count=1)
        # 5.3 s on the server clock, minus the 0.3 s latency allowance = 5 s of 10 s used.
        after = self.answer(state, right=True, after_ms=5300)
        self.assertEqual(after["current"]["points"], 30 + 8)  # 30 base + round_half_up(15 * 0.5)
        self.assertEqual(
            self.conn.execute("SELECT time_spent_ms FROM attempt_questions WHERE attempt_id = ?", (state["id"],)).fetchone()[0],
            5000,
        )

    def test_late_answer_past_the_grace_is_a_timeout(self):
        state = self.start(timerMode="question", secondsPerQuestion=10)
        after = self.answer(state, right=True, after_ms=10_000 + rules.DEADLINE_GRACE_MS + 1)
        self.assertEqual(after["current"]["status"], "timeout")
        self.assertEqual(after["current"]["points"], 0)

    def test_null_option_records_a_timeout(self):
        state = self.start(timerMode="question", secondsPerQuestion=10)
        self.clock.advance(10_000)
        after = self.service.answer(self.player, state["id"], {"position": 0, "optionId": None})
        self.assertEqual(after["current"]["status"], "timeout")
        self.assertIsNotNone(after["current"]["correctOptionId"])

    def test_double_submit_is_idempotent_and_stale_position_conflicts(self):
        state = self.start()
        first = self.answer(state, right=False)
        second = self.service.answer(self.player, state["id"], {"position": 0, "optionId": correct_option(self.conn, state)})
        self.assertEqual(second["current"]["status"], "wrong")  # first answer stands
        self.assertEqual(first["live"], second["live"])
        with self.assertRaises(Conflict):
            self.service.answer(self.player, state["id"], {"position": 3, "optionId": 1})

    def test_option_must_belong_to_the_question(self):
        state = self.start()
        foreign = self.conn.execute(
            "SELECT id FROM question_options WHERE question_id <> ? LIMIT 1", (state["current"]["question"]["id"],)
        ).fetchone()[0]
        with self.assertRaises(ValidationError):
            self.service.answer(self.player, state["id"], {"position": 0, "optionId": foreign})


class FlowTests(ServiceTestCase):
    def test_next_requires_an_answer_and_is_double_click_safe(self):
        state = self.start()
        with self.assertRaises(Conflict):
            self.next(state)
        answered = self.answer(state)
        moved = self.next(answered)
        self.assertEqual(moved["currentIndex"], 1)
        again = self.service.next(self.player, state["id"], {"position": 0})
        self.assertEqual(again["currentIndex"], 1)

    def test_full_quiz_completes_with_summary(self):
        state = self.start(count=4)
        for i in range(4):
            state = self.answer(state, right=i < 3, after_ms=1000)
            state = self.next(state)
        self.assertEqual(state["status"], "finished")
        self.assertEqual(state["endReason"], "completed")
        results = self.service.results(self.player, state["id"])
        self.assertEqual((results["correct"], results["wrong"], results["percentage"]), (3, 1, 75))
        self.assertEqual(results["grade"]["grade"], "B")
        self.assertEqual(results["bestStreak"], 3)
        self.assertEqual(len(results["review"]), 4)
        self.assertEqual(len(results["missedQuestionIds"]), 1)
        for entry in results["review"]:
            self.assertEqual(sum(o["isCorrect"] for o in entry["options"]), 1)
        self.assertEqual(sum(t["total"] for t in results["byTopic"]), 4)

    def test_finish_early_quits_and_skips_the_rest(self):
        state = self.answer(self.start())
        done = self.service.finish(self.player, state["id"])
        self.assertEqual(done["endReason"], "quit")
        results = self.service.results(self.player, state["id"])
        self.assertEqual((results["correct"], results["skipped"]), (1, 4))

    def test_results_before_finishing_conflict(self):
        with self.assertRaises(Conflict):
            self.service.results(self.player, self.start()["id"])

    def test_discard_only_unfinished(self):
        state = self.start()
        self.service.discard(self.player, state["id"])
        with self.assertRaises(NotFound):
            self.service.get_state(self.player, state["id"])
        finished = self.service.finish(self.player, self.start()["id"])
        with self.assertRaises(Conflict):
            self.service.discard(self.player, finished["id"])


class ExpiryTests(ServiceTestCase):
    def test_question_clock_keeps_running_while_away(self):
        state = self.start(timerMode="question", secondsPerQuestion=10)
        self.clock.advance(60_000)  # tab closed for a minute
        resumed = self.service.get_state(self.player, state["id"])
        self.assertEqual(resumed["current"]["status"], "timeout")
        self.assertEqual(resumed["clock"]["questionElapsedMs"], 10_000)

    def test_whole_quiz_clock_auto_submits(self):
        state = self.start(timerMode="session", secondsPerQuestion=10, count=3)  # 30 s budget
        state = self.answer(state, after_ms=8000)
        self.clock.advance(120_000)  # reading feedback is free...
        state = self.next(state)
        self.assertEqual(state["status"], "active")
        self.assertEqual(state["clock"]["sessionElapsedMs"], 8000 - rules.LATENCY_ALLOWANCE_MS)
        self.clock.advance(60_000)  # ...but the live question is not
        resumed = self.service.get_state(self.player, state["id"])
        self.assertEqual((resumed["status"], resumed["endReason"]), ("finished", "time-up"))
        results = self.service.results(self.player, state["id"])
        self.assertEqual((results["correct"], results["timedOut"], results["skipped"]), (1, 1, 1))
        self.assertEqual(results["durationMs"], 30_000)

    def test_client_finish_at_zero_is_time_up_not_quit(self):
        state = self.start(timerMode="session", secondsPerQuestion=10, count=2)
        self.clock.advance(19_500)  # client clock hit 0:00 a moment early
        done = self.service.finish(self.player, state["id"])
        self.assertEqual(done["endReason"], "time-up")


class HistoryAndLeaderboardTests(ServiceTestCase):
    def play(self, player, correct, count=5, **overrides):
        state = self.service.create_attempt(player, config(count=count, **overrides))
        for i in range(count):
            option = correct_option(self.conn, state) if i < correct else wrong_option(self.conn, state)
            state = self.service.answer(player, state["id"], {"position": i, "optionId": option})
            state = self.service.next(player, state["id"], {"position": i})
        return state

    def test_history_stats_and_clear(self):
        self.play(self.player, 5)
        self.play(self.player, 2)
        history = self.service.history(self.player)
        self.assertEqual(history["stats"]["attempts"], 2)
        self.assertEqual(history["stats"]["averagePercentage"], 70)
        self.assertEqual(history["stats"]["bestPercentage"], 100)
        self.assertEqual(history["stats"]["overallAccuracy"], 70)
        self.assertEqual(sum(t["total"] for t in history["byTopic"]), 10)
        self.assertEqual(len(history["attempts"]), 2)
        self.assertEqual(self.service.clear_history(self.player), {"deleted": 2})
        self.assertEqual(self.service.history(self.player)["stats"]["attempts"], 0)

    def test_leaderboard_takes_each_players_best_eligible_attempt(self):
        # 1 correct scores at most 30 points; 5 correct at least 50, whatever the difficulties.
        ada = self.service.create_player({"name": "Ada"})
        self.play(self.player, 1)
        self.play(self.player, 5)
        self.play(ada, 1)
        self.play(ada, 5, count=3)  # too short to count
        quitter = self.service.create_player({"name": "Quinn"})
        self.service.finish(quitter, self.service.create_attempt(quitter, config())["id"])  # quit: excluded

        board = self.service.leaderboard()["entries"]
        self.assertEqual([e["name"] for e in board], ["Sam", "Ada"])
        self.assertEqual([e["rank"] for e in board], [1, 2])
        self.assertEqual(board[0]["percentage"], 100)
        self.assertEqual(board[0]["attempts"], 2)

    def test_leaderboard_reports_your_rank_outside_the_top(self):
        rivals = [self.service.create_player({"name": f"Rival {i}"}) for i in range(3)]
        for rival in rivals:
            self.play(rival, 5)  # at least 50 points each
        self.play(self.player, 1)  # at most 30 points: last place
        board = self.service.leaderboard(limit=2, player=self.player)
        self.assertEqual(len(board["entries"]), 2)
        self.assertNotIn("Sam", [e["name"] for e in board["entries"]])
        self.assertEqual((board["you"]["name"], board["you"]["rank"], board["players"]), ("Sam", 4, 4))
        self.assertIsNone(self.service.leaderboard(limit=2)["you"])

    def test_retry_runs_do_not_count_for_the_leaderboard(self):
        state = self.service.create_attempt(self.player, {**config(), "questionIds": [q["id"] for q in helpers.QUESTIONS[:5]]})
        for i in range(5):
            state = self.service.answer(self.player, state["id"], {"position": i, "optionId": correct_option(self.conn, state)})
            state = self.service.next(self.player, state["id"], {"position": i})
        self.assertEqual(self.service.leaderboard()["entries"], [])


class RulesTests(unittest.TestCase):
    def test_scoring_table(self):
        self.assertEqual(rules.score_answer("correct", "hard", "question", False, 1), 45)
        self.assertEqual(rules.score_answer("correct", "hard", "question", False, 0.5), 38)
        self.assertEqual(rules.score_answer("correct", "easy", "off", False, 1), 10)
        self.assertEqual(rules.score_answer("wrong", "medium", "question", False), 0)
        self.assertEqual(rules.score_answer("wrong", "easy", "question", True), -3)  # 2.5 rounds half up
        self.assertEqual(rules.score_answer("timeout", "hard", "question", True), 0)
        self.assertEqual(rules.max_points_for("medium", "question"), 30)

    def test_grades(self):
        self.assertEqual([rules.grade_for(p)["grade"] for p in (100, 90, 89, 75, 60, 40, 39, 0)], list("AABBCDFF"))


if __name__ == "__main__":
    unittest.main()

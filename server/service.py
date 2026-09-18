"""Quiz business logic. The server is authoritative:

- The browser never receives a correct answer or explanation until that
  question is resolved, so answers cannot be read out of devtools.
- Time is measured with the server's clock (minus a small latency allowance),
  so the client cannot claim it answered faster than it did.
- Clocks keep running while a tab is closed. Every call first "resolves
  expiry": a question or quiz whose time ran out is settled before anything else.
- Answer / next / finish are idempotent where it matters: a double click or a
  retried request returns the current state instead of failing.
"""

import hashlib
import json
import random
import secrets
import sqlite3
import time
import unicodedata

from . import db, rules
from .errors import Conflict, NotFound, Unauthorized, ValidationError

RESOLVED = ("correct", "wrong", "timeout", "skipped")


def now_ms():
    return int(time.time() * 1000)


def hash_key(key):
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


class QuizService:
    def __init__(self, conn, *, clock=now_ms, rng=None):
        self.conn = conn
        self.clock = clock
        self.rng = rng or random.Random()

    # ------------------------------------------------------------ catalog

    def catalog(self):
        rows = self.conn.execute(
            """SELECT t.id, t.name, t.icon, t.description,
                      COUNT(q.id) FILTER (WHERE q.difficulty = 'easy')   AS easy,
                      COUNT(q.id) FILTER (WHERE q.difficulty = 'medium') AS medium,
                      COUNT(q.id) FILTER (WHERE q.difficulty = 'hard')   AS hard
                 FROM topics t
                 LEFT JOIN questions q ON q.topic_id = t.id AND q.is_active = 1
                GROUP BY t.id
                ORDER BY t.position"""
        ).fetchall()
        topics = [
            {
                "id": r["id"],
                "name": r["name"],
                "icon": r["icon"],
                "description": r["description"],
                "counts": {"easy": r["easy"], "medium": r["medium"], "hard": r["hard"]},
            }
            for r in rows
        ]
        return {"topics": topics, "rules": rules.public_rules()}

    # ------------------------------------------------------------ players

    def create_player(self, payload):
        name = _clean_name(_require(payload, "name", str))
        key = secrets.token_urlsafe(24)
        try:
            with db.transaction(self.conn):
                cursor = self.conn.execute(
                    "INSERT INTO players (name, key_hash, created_at) VALUES (?, ?, ?)",
                    (name, hash_key(key), self.clock()),
                )
        except sqlite3.IntegrityError:
            raise Conflict(f'The name "{name}" is already taken. Please choose another.', code="name_taken") from None
        return {"id": cursor.lastrowid, "name": name, "key": key}

    def authenticate(self, key):
        if not key:
            raise Unauthorized("Missing player key. Set your name on the setup screen first.")
        row = self.conn.execute("SELECT id, name FROM players WHERE key_hash = ?", (hash_key(key),)).fetchone()
        if row is None:
            raise Unauthorized("Unknown player key. Set your name on the setup screen again.")
        return {"id": row["id"], "name": row["name"]}

    # ------------------------------------------------------------ attempts

    def create_attempt(self, player, payload):
        config = _validate_config(payload)
        question_ids = payload.get("questionIds")
        if question_ids is not None:
            if not isinstance(question_ids, list) or not all(isinstance(i, str) for i in question_ids):
                raise ValidationError("questionIds must be a list of question ids.")
            if len(question_ids) > rules.MAX_QUESTIONS:
                raise ValidationError(f"At most {rules.MAX_QUESTIONS} questions per quiz.")

        known = {r["id"] for r in self.conn.execute("SELECT id FROM topics")}
        unknown = [t for t in config["topics"] if t not in known]
        if unknown:
            raise ValidationError(f"Unknown topic(s): {', '.join(unknown)}.")

        if question_ids is not None:
            placeholders = ",".join("?" * len(question_ids)) or "NULL"
            pool = self.conn.execute(
                f"SELECT id, difficulty, position FROM questions WHERE is_active = 1 AND id IN ({placeholders})",
                question_ids,
            ).fetchall()
        else:
            params = list(config["topics"])
            sql = f"SELECT id, difficulty, position FROM questions WHERE is_active = 1 AND topic_id IN ({','.join('?' * len(params))})"
            if config["difficulty"] != "mixed":
                sql += " AND difficulty = ?"
                params.append(config["difficulty"])
            pool = self.conn.execute(sql, params).fetchall()

        if not pool:
            raise ValidationError("No questions match the selected topics and difficulty.")

        picked = self.rng.sample(pool, min(config["count"], len(pool)))
        if not config["shuffle"]:
            picked.sort(key=lambda r: (rules.DIFFICULTIES.index(r["difficulty"]), r["position"]))

        options = self._options_for([r["id"] for r in picked])
        now = self.clock()
        attempt_id = secrets.token_urlsafe(12)

        with db.transaction(self.conn):
            # One unfinished quiz per player: starting a new one discards the old.
            self.conn.execute("DELETE FROM attempts WHERE player_id = ? AND status = 'active'", (player["id"],))
            self.conn.execute(
                """INSERT INTO attempts (id, player_id, topics, difficulty, timer_mode, seconds_per_question,
                     shuffle, negative_marking, is_retry, question_count, started_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    attempt_id,
                    player["id"],
                    json.dumps(config["topics"]),
                    config["difficulty"],
                    config["timerMode"],
                    config["secondsPerQuestion"],
                    int(config["shuffle"]),
                    int(config["negativeMarking"]),
                    int(question_ids is not None),
                    len(picked),
                    now,
                ),
            )
            rows = []
            for position, question in enumerate(picked):
                option_ids = [o["id"] for o in options[question["id"]]]
                if config["shuffle"]:
                    self.rng.shuffle(option_ids)
                rows.append((attempt_id, position, question["id"], json.dumps(option_ids), now if position == 0 else None))
            self.conn.executemany(
                "INSERT INTO attempt_questions (attempt_id, position, question_id, option_order, served_at) VALUES (?, ?, ?, ?, ?)",
                rows,
            )
        return self.get_state(player, attempt_id)

    def get_state(self, player, attempt_id):
        with db.transaction(self.conn):
            attempt, items = self._load(player, attempt_id)
            attempt, items = self._resolve_expiry(attempt, items)
        return self._state(attempt, items)

    def answer(self, player, attempt_id, payload):
        position = _require(payload, "position", int)
        option_id = payload.get("optionId")
        if option_id is not None and (not isinstance(option_id, int) or isinstance(option_id, bool)):
            raise ValidationError("optionId must be an integer or null.")

        with db.transaction(self.conn):
            attempt, items = self._load(player, attempt_id)
            attempt, items = self._resolve_expiry(attempt, items)
            if attempt["status"] != "active":
                return self._state(attempt, items)
            if position != attempt["current_index"]:
                raise Conflict("That question is no longer current. Reload to continue.", code="stale_position")

            item = items[position]
            if item["status"] != "pending":
                # Double submit, or the question timed out while the request was in flight.
                return self._state(attempt, items)

            now = self.clock()
            elapsed = max(0, now - item["served_at"] - rules.LATENCY_ALLOWANCE_MS)
            limit = _question_limit(attempt)

            if option_id is None:
                status, selected, points = "timeout", None, 0
                spent = min(elapsed, limit) if limit else elapsed
            else:
                if option_id not in json.loads(item["option_order"]):
                    raise ValidationError("That option does not belong to this question.")
                correct = self.conn.execute("SELECT is_correct FROM question_options WHERE id = ?", (option_id,)).fetchone()[0]
                status = "correct" if correct else "wrong"
                selected = option_id
                spent = min(elapsed, limit) if limit else elapsed
                remaining_fraction = 1 - spent / limit if limit else 0
                points = rules.score_answer(
                    status, item["difficulty"], attempt["timer_mode"], attempt["negative_marking"], remaining_fraction
                )

            self.conn.execute(
                """UPDATE attempt_questions SET status = ?, selected_option_id = ?, points = ?,
                     time_spent_ms = ?, answered_at = ? WHERE attempt_id = ? AND position = ?""",
                (status, selected, points, spent, now, attempt_id, position),
            )
            attempt, items = self._load(player, attempt_id)
        return self._state(attempt, items)

    def next(self, player, attempt_id, payload):
        position = _require(payload, "position", int)
        with db.transaction(self.conn):
            attempt, items = self._load(player, attempt_id)
            attempt, items = self._resolve_expiry(attempt, items)
            if attempt["status"] != "active" or position == attempt["current_index"] - 1:
                return self._state(attempt, items)  # already finished / already advanced (double click)
            if position != attempt["current_index"]:
                raise Conflict("That question is no longer current. Reload to continue.", code="stale_position")
            if items[position]["status"] == "pending":
                raise Conflict("Answer the current question before moving on.", code="unanswered")

            if position == len(items) - 1:
                attempt, items = self._finish(attempt, items, "completed")
            else:
                self.conn.execute("UPDATE attempts SET current_index = ? WHERE id = ?", (position + 1, attempt_id))
                self.conn.execute(
                    "UPDATE attempt_questions SET served_at = ? WHERE attempt_id = ? AND position = ?",
                    (self.clock(), attempt_id, position + 1),
                )
                attempt, items = self._load(player, attempt_id)
        return self._state(attempt, items)

    def finish(self, player, attempt_id):
        """Ends the quiz now: time-up if the whole-quiz clock has (about) run out, else quit."""
        with db.transaction(self.conn):
            attempt, items = self._load(player, attempt_id)
            attempt, items = self._resolve_expiry(attempt, items)
            if attempt["status"] == "active":
                session_limit = _session_limit(attempt)
                now = self.clock()
                if session_limit and _session_elapsed(attempt, items, now) + rules.DEADLINE_GRACE_MS >= session_limit:
                    attempt, items = self._time_up(attempt, items, now)
                else:
                    reason = "completed" if all(i["status"] in RESOLVED for i in items) else "quit"
                    attempt, items = self._finish(attempt, items, reason)
        return self._state(attempt, items)

    def discard(self, player, attempt_id):
        with db.transaction(self.conn):
            attempt, _ = self._load(player, attempt_id)
            if attempt["status"] != "active":
                raise Conflict("Only an unfinished quiz can be discarded.")
            self.conn.execute("DELETE FROM attempts WHERE id = ?", (attempt_id,))

    def results(self, player, attempt_id):
        with db.transaction(self.conn):
            attempt, items = self._load(player, attempt_id)
            attempt, items = self._resolve_expiry(attempt, items)
        if attempt["status"] != "finished":
            raise Conflict("This quiz has not finished yet.", code="not_finished")
        return self._summary(attempt, items, with_review=True)

    # ------------------------------------------------------------ history & leaderboard

    def history(self, player, limit=50):
        attempts = self.conn.execute(
            """SELECT * FROM attempts WHERE player_id = ? AND status = 'finished'
                ORDER BY finished_at DESC LIMIT ?""",
            (player["id"], limit),
        ).fetchall()
        totals = self.conn.execute(
            """SELECT COUNT(*) AS attempts, COALESCE(SUM(question_count), 0) AS questions,
                      COALESCE(SUM(correct_count), 0) AS correct,
                      COALESCE(ROUND(AVG(percentage)), 0) AS average, COALESCE(MAX(percentage), 0) AS best
                 FROM attempts WHERE player_id = ? AND status = 'finished'""",
            (player["id"],),
        ).fetchone()
        mastery = self.conn.execute(
            """SELECT t.id AS key, t.name AS label, t.icon AS icon,
                      COUNT(*) AS total, SUM(aq.status = 'correct') AS correct, COUNT(DISTINCT a.id) AS attempts
                 FROM attempts a
                 JOIN attempt_questions aq ON aq.attempt_id = a.id
                 JOIN questions q ON q.id = aq.question_id
                 JOIN topics t ON t.id = q.topic_id
                WHERE a.player_id = ? AND a.status = 'finished'
                GROUP BY t.id
                ORDER BY t.position""",
            (player["id"],),
        ).fetchall()
        return {
            "player": {"name": player["name"]},
            "stats": {
                "attempts": totals["attempts"],
                "questions": totals["questions"],
                "correct": totals["correct"],
                "averagePercentage": int(totals["average"]),
                "bestPercentage": totals["best"],
                "overallAccuracy": _percent(totals["correct"], totals["questions"]),
            },
            "byTopic": [{**dict(r), "percentage": _percent(r["correct"], r["total"])} for r in mastery],
            "attempts": [self._attempt_row(a) for a in attempts],
        }

    def clear_history(self, player):
        with db.transaction(self.conn):
            deleted = self.conn.execute(
                "DELETE FROM attempts WHERE player_id = ? AND status = 'finished'", (player["id"],)
            ).rowcount
        return {"deleted": deleted}

    def leaderboard(self, limit=20, player=None):
        """Each player's single best eligible attempt, ranked by points.

        With a player, also reports that player's own rank even when it falls
        outside the top `limit` ("you are #34 of 57").
        """
        limit = max(1, min(limit, 100))
        rows = self.conn.execute(
            """WITH best AS (
                 SELECT a.player_id, p.name, a.total_points, a.percentage, a.question_count, a.difficulty,
                        a.timer_mode, a.finished_at,
                        COUNT(*) OVER (PARTITION BY a.player_id) AS attempts,
                        ROW_NUMBER() OVER (PARTITION BY a.player_id
                                           ORDER BY a.total_points DESC, a.percentage DESC, a.finished_at ASC) AS rn
                   FROM attempts a JOIN players p ON p.id = a.player_id
                  WHERE a.status = 'finished' AND a.end_reason <> 'quit' AND a.is_retry = 0
                    AND a.question_count >= :min_questions),
               ranked AS (
                 SELECT *, ROW_NUMBER() OVER (ORDER BY total_points DESC, percentage DESC, finished_at ASC) AS rank,
                        COUNT(*) OVER () AS players
                   FROM best WHERE rn = 1)
               SELECT * FROM ranked WHERE rank <= :limit OR player_id = :player ORDER BY rank""",
            {"min_questions": rules.LEADERBOARD_MIN_QUESTIONS, "limit": limit, "player": player["id"] if player else None},
        ).fetchall()

        def entry(r):
            return {
                "rank": r["rank"],
                "name": r["name"],
                "points": r["total_points"],
                "percentage": r["percentage"],
                "questionCount": r["question_count"],
                "difficulty": r["difficulty"],
                "timerMode": r["timer_mode"],
                "finishedAt": r["finished_at"],
                "attempts": r["attempts"],
            }

        mine = next((r for r in rows if player and r["player_id"] == player["id"]), None)
        return {
            "entries": [entry(r) for r in rows if r["rank"] <= limit],
            "you": entry(mine) if mine else None,
            "players": rows[0]["players"] if rows else 0,
            "minQuestions": rules.LEADERBOARD_MIN_QUESTIONS,
        }

    # ------------------------------------------------------------ internals

    def _load(self, player, attempt_id):
        attempt = self.conn.execute(
            "SELECT * FROM attempts WHERE id = ? AND player_id = ?", (attempt_id, player["id"])
        ).fetchone()
        if attempt is None:
            # Same answer whether it doesn't exist or belongs to someone else.
            raise NotFound("Quiz not found.")
        items = self.conn.execute(
            """SELECT aq.*, q.topic_id, q.difficulty, q.prompt, q.code, q.explanation
                 FROM attempt_questions aq JOIN questions q ON q.id = aq.question_id
                WHERE aq.attempt_id = ? ORDER BY aq.position""",
            (attempt_id,),
        ).fetchall()
        return attempt, items

    def _reload(self, attempt):
        return self._load({"id": attempt["player_id"]}, attempt["id"])

    def _options_for(self, question_ids):
        if not question_ids:
            return {}
        rows = self.conn.execute(
            f"SELECT id, question_id, text, is_correct FROM question_options WHERE question_id IN ({','.join('?' * len(question_ids))}) ORDER BY position",
            question_ids,
        ).fetchall()
        grouped = {qid: [] for qid in question_ids}
        for row in rows:
            grouped[row["question_id"]].append(row)
        return grouped

    def _resolve_expiry(self, attempt, items):
        """Settles clocks that ran out while nobody was looking. Call inside a transaction."""
        if attempt["status"] != "active":
            return attempt, items
        now = self.clock()
        current = items[attempt["current_index"]]

        session_limit = _session_limit(attempt)
        if session_limit and _session_elapsed(attempt, items, now) >= session_limit + rules.DEADLINE_GRACE_MS:
            return self._time_up(attempt, items, now)

        limit = _question_limit(attempt)
        if limit and current["status"] == "pending" and current["served_at"] is not None:
            if now - current["served_at"] >= limit + rules.DEADLINE_GRACE_MS:
                self.conn.execute(
                    """UPDATE attempt_questions SET status = 'timeout', points = 0, time_spent_ms = ?, answered_at = ?
                        WHERE attempt_id = ? AND position = ?""",
                    (limit, current["served_at"] + limit, attempt["id"], current["position"]),
                )
                return self._reload(attempt)
        return attempt, items

    def _time_up(self, attempt, items, now):
        current = items[attempt["current_index"]]
        if current["status"] == "pending":
            used_before = sum(i["time_spent_ms"] for i in items if i["status"] in RESOLVED)
            self.conn.execute(
                """UPDATE attempt_questions SET status = 'timeout', points = 0, time_spent_ms = ?, answered_at = ?
                    WHERE attempt_id = ? AND position = ?""",
                (max(0, _session_limit(attempt) - used_before), now, attempt["id"], current["position"]),
            )
            attempt, items = self._reload(attempt)
        return self._finish(attempt, items, "time-up")

    def _finish(self, attempt, items, reason):
        now = self.clock()
        self.conn.execute(
            "UPDATE attempt_questions SET status = 'skipped', points = 0 WHERE attempt_id = ? AND status = 'pending'",
            (attempt["id"],),
        )
        attempt, items = self._reload(attempt)
        summary = self._summary({**dict(attempt), "end_reason": reason}, items, with_review=False)
        self.conn.execute(
            """UPDATE attempts SET status = 'finished', end_reason = ?, finished_at = ?, correct_count = ?,
                 total_points = ?, max_points = ?, percentage = ?, duration_ms = ? WHERE id = ?""",
            (reason, now, summary["correct"], summary["points"], summary["maxPoints"], summary["percentage"],
             summary["durationMs"], attempt["id"]),
        )
        return self._reload(attempt)

    def _config(self, attempt):
        return {
            "topics": json.loads(attempt["topics"]),
            "difficulty": attempt["difficulty"],
            "count": attempt["question_count"],
            "timerMode": attempt["timer_mode"],
            "secondsPerQuestion": attempt["seconds_per_question"],
            "shuffle": bool(attempt["shuffle"]),
            "negativeMarking": bool(attempt["negative_marking"]),
            "isRetry": bool(attempt["is_retry"]),
        }

    def _state(self, attempt, items):
        now = self.clock()
        answered = [i for i in items if i["status"] in ("correct", "wrong", "timeout")]
        state = {
            "id": attempt["id"],
            "status": attempt["status"],
            "endReason": attempt["end_reason"],
            "config": self._config(attempt),
            "currentIndex": attempt["current_index"],
            "total": len(items),
            "live": {
                "points": max(0, sum(i["points"] for i in items)),
                "correct": sum(1 for i in items if i["status"] == "correct"),
                "answered": len(answered),
                "streak": _current_streak(items),
            },
            "clock": {
                "questionLimitMs": _question_limit(attempt),
                "sessionLimitMs": _session_limit(attempt),
                "sessionElapsedMs": _session_elapsed(attempt, items, now),
                "questionElapsedMs": 0,
            },
            "current": None,
        }
        if attempt["status"] != "active":
            return state

        item = items[attempt["current_index"]]
        options = {o["id"]: o for o in self._options_for([item["question_id"]])[item["question_id"]]}
        resolved = item["status"] != "pending"
        state["clock"]["questionElapsedMs"] = item["time_spent_ms"] if resolved else _live_elapsed(item, now)
        state["current"] = {
            "position": item["position"],
            "status": item["status"],
            "points": item["points"],
            "isLast": item["position"] == len(items) - 1,
            "question": {
                "id": item["question_id"],
                "topic": item["topic_id"],
                "difficulty": item["difficulty"],
                "prompt": item["prompt"],
                "code": item["code"],
                "options": [{"id": oid, "text": options[oid]["text"]} for oid in json.loads(item["option_order"])],
            },
            "selectedOptionId": item["selected_option_id"],
            # Revealed only once the question is resolved.
            "correctOptionId": next((oid for oid, o in options.items() if o["is_correct"]), None) if resolved else None,
            "explanation": item["explanation"] if resolved else None,
        }
        return state

    def _summary(self, attempt, items, *, with_review):
        counts = {"correct": 0, "wrong": 0, "timeout": 0, "skipped": 0, "pending": 0}
        by_topic, by_difficulty = {}, {d: {"correct": 0, "total": 0} for d in rules.DIFFICULTIES}
        points = max_points = duration = 0
        for item in items:
            counts[item["status"]] += 1
            points += item["points"]
            max_points += rules.max_points_for(item["difficulty"], attempt["timer_mode"])
            duration += item["time_spent_ms"]
            for bucket in (by_topic.setdefault(item["topic_id"], {"correct": 0, "total": 0}), by_difficulty[item["difficulty"]]):
                bucket["total"] += 1
                bucket["correct"] += item["status"] == "correct"

        total = len(items)
        answered = counts["correct"] + counts["wrong"]
        percentage = _percent(counts["correct"], total)
        topics = {r["id"]: r for r in self.conn.execute("SELECT id, name, icon, position FROM topics")}
        summary = {
            "id": attempt["id"],
            "config": self._config(attempt),
            "endReason": attempt["end_reason"],
            "finishedAt": attempt["finished_at"],
            "total": total,
            "answered": answered,
            "correct": counts["correct"],
            "wrong": counts["wrong"],
            "timedOut": counts["timeout"],
            "skipped": counts["skipped"] + counts["pending"],
            "percentage": percentage,
            "accuracy": _percent(counts["correct"], answered),
            "points": max(0, points),
            "maxPoints": max_points,
            "grade": rules.grade_for(percentage),
            "bestStreak": _best_streak(items),
            "durationMs": duration,
            "averageTimeMs": round(duration / total) if total else 0,
            "byTopic": [
                {"key": key, "label": topics[key]["name"], "icon": topics[key]["icon"], **b, "percentage": _percent(b["correct"], b["total"])}
                for key, b in sorted(by_topic.items(), key=lambda kv: topics[kv[0]]["position"])
            ],
            "byDifficulty": [
                {"key": d, "label": d.capitalize(), **b, "percentage": _percent(b["correct"], b["total"])}
                for d, b in by_difficulty.items()
                if b["total"]
            ],
            "missedQuestionIds": [i["question_id"] for i in items if i["status"] != "correct"],
        }
        if with_review:
            options = self._options_for([i["question_id"] for i in items])
            summary["review"] = [
                {
                    "number": i["position"] + 1,
                    "questionId": i["question_id"],
                    "topic": i["topic_id"],
                    "difficulty": i["difficulty"],
                    "question": i["prompt"],
                    "code": i["code"],
                    "explanation": i["explanation"],
                    "status": i["status"],
                    "points": i["points"],
                    "timeSpentMs": i["time_spent_ms"],
                    "options": _review_options(options[i["question_id"]], json.loads(i["option_order"]), i["selected_option_id"]),
                }
                for i in items
            ]
        return summary

    def _attempt_row(self, a):
        return {
            "id": a["id"],
            "finishedAt": a["finished_at"],
            "endReason": a["end_reason"],
            "config": self._config(a),
            "total": a["question_count"],
            "correct": a["correct_count"],
            "percentage": a["percentage"],
            "points": a["total_points"],
            "maxPoints": a["max_points"],
            "grade": rules.grade_for(a["percentage"])["grade"],
            "durationMs": a["duration_ms"],
        }


# ---------------------------------------------------------------- helpers


def _require(payload, field, kind):
    if not isinstance(payload, dict):
        raise ValidationError("Request body must be a JSON object.")
    value = payload.get(field)
    if kind is int and (not isinstance(value, int) or isinstance(value, bool)):
        raise ValidationError(f"'{field}' must be an integer.")
    if kind is str and not isinstance(value, str):
        raise ValidationError(f"'{field}' must be a string.")
    return value


def _clean_name(raw):
    name = " ".join(unicodedata.normalize("NFC", raw).split())  # collapse whitespace
    if not name or len(name) > rules.PLAYER_NAME_MAX:
        raise ValidationError(f"Name must be 1-{rules.PLAYER_NAME_MAX} characters.")
    if any(unicodedata.category(ch).startswith("C") for ch in name):
        raise ValidationError("Name contains invalid characters.")
    return name


def _validate_config(payload):
    if not isinstance(payload, dict):
        raise ValidationError("Request body must be a JSON object.")

    def int_in(field, low, high):
        value = payload.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
            raise ValidationError(f"'{field}' must be an integer between {low} and {high}.")
        return value

    def boolean(field):
        value = payload.get(field, False)
        if not isinstance(value, bool):
            raise ValidationError(f"'{field}' must be true or false.")
        return value

    topics = payload.get("topics")
    if not isinstance(topics, list) or not topics or not all(isinstance(t, str) for t in topics):
        raise ValidationError("Choose at least one topic.")
    if payload.get("difficulty") not in rules.DIFFICULTY_FILTERS:
        raise ValidationError(f"'difficulty' must be one of {', '.join(rules.DIFFICULTY_FILTERS)}.")
    if payload.get("timerMode") not in rules.TIMER_MODES:
        raise ValidationError(f"'timerMode' must be one of {', '.join(rules.TIMER_MODES)}.")
    return {
        "topics": list(dict.fromkeys(topics)),
        "difficulty": payload["difficulty"],
        "count": int_in("count", rules.MIN_QUESTIONS, rules.MAX_QUESTIONS),
        "timerMode": payload["timerMode"],
        "secondsPerQuestion": int_in("secondsPerQuestion", rules.MIN_SECONDS, rules.MAX_SECONDS),
        "shuffle": boolean("shuffle"),
        "negativeMarking": boolean("negativeMarking"),
    }


def _question_limit(attempt):
    return attempt["seconds_per_question"] * 1000 if attempt["timer_mode"] == "question" else None


def _session_limit(attempt):
    if attempt["timer_mode"] != "session":
        return None
    return attempt["seconds_per_question"] * 1000 * attempt["question_count"]


def _live_elapsed(item, now):
    return max(0, now - item["served_at"]) if item["served_at"] is not None else 0


def _session_elapsed(attempt, items, now):
    """Time charged to the whole quiz: resolved questions plus the live one.
    Time spent reading feedback (between answering and the next question) is free."""
    spent = sum(i["time_spent_ms"] for i in items if i["status"] in RESOLVED)
    if attempt["status"] == "active":
        current = items[attempt["current_index"]]
        if current["status"] == "pending":
            spent += _live_elapsed(current, now)
    return spent


def _current_streak(items):
    streak = 0
    for item in items:
        if item["status"] == "correct":
            streak += 1
        elif item["status"] != "pending":
            streak = 0
    return streak


def _best_streak(items):
    best = run = 0
    for item in items:
        run = run + 1 if item["status"] == "correct" else 0
        best = max(best, run)
    return best


def _percent(part, whole):
    return rules.round_half_up(part * 100 / whole) if whole else 0


def _review_options(options, order, selected):
    by_id = {o["id"]: o for o in options}
    return [
        {"text": by_id[oid]["text"], "isCorrect": bool(by_id[oid]["is_correct"]), "isSelected": oid == selected}
        for oid in order
        if oid in by_id
    ]

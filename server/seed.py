"""Loads the question bank (data/questions.json) into the database.

Idempotent sync, all-or-nothing:
  - new topics/questions are inserted, changed ones updated
  - questions missing from the file are deactivated (never deleted: past
    attempts still reference them)
  - a question that has already been played keeps its options fixed; changing
    them is rejected, because history would silently change meaning. Give the
    changed question a new id instead.

Run directly to sync an existing database:  python -m server.seed [--db path]
"""

import argparse
import json
import pathlib
import time

from . import db
from .rules import DIFFICULTIES

DEFAULT_SEED_PATH = pathlib.Path(__file__).resolve().parent.parent / "data" / "questions.json"


class SeedError(Exception):
    pass


def _nonempty(value):
    return isinstance(value, str) and value.strip() != ""


def validate_bank(bank):
    """Returns a list of problems (empty when the file is valid)."""
    problems = []
    topics = bank.get("topics") if isinstance(bank, dict) else None
    questions = bank.get("questions") if isinstance(bank, dict) else None
    if not isinstance(topics, list) or not isinstance(questions, list):
        return ["file must be an object with 'topics' and 'questions' arrays"]

    topic_ids = set()
    for topic in topics:
        if not isinstance(topic, dict) or not _nonempty(topic.get("id")) or not _nonempty(topic.get("name")):
            problems.append(f"topic {topic!r}: needs id and name")
        elif topic["id"] in topic_ids:
            problems.append(f"topic {topic['id']}: duplicate id")
        else:
            topic_ids.add(topic["id"])

    seen = set()
    for index, q in enumerate(questions):
        label = q.get("id", f"#{index}") if isinstance(q, dict) else f"#{index}"
        if not isinstance(q, dict):
            problems.append(f"question {label}: not an object")
            continue
        issues = []
        if not _nonempty(q.get("id")):
            issues.append("missing id")
        elif q["id"] in seen:
            issues.append("duplicate id")
        if q.get("topic") not in topic_ids:
            issues.append(f"unknown topic {q.get('topic')!r}")
        if q.get("difficulty") not in DIFFICULTIES:
            issues.append(f"invalid difficulty {q.get('difficulty')!r}")
        if not _nonempty(q.get("question")):
            issues.append("missing question text")
        if not _nonempty(q.get("explanation")):
            issues.append("missing explanation")
        if "code" in q and q["code"] is not None and not isinstance(q["code"], str):
            issues.append("code must be a string")
        options = q.get("options")
        if not isinstance(options, list) or not 2 <= len(options) <= 6 or not all(_nonempty(o) for o in options):
            issues.append("options must be 2-6 non-empty strings")
        else:
            if len({o.strip().lower() for o in options}) != len(options):
                issues.append("options must be unique")
            answer = q.get("answer")
            if not isinstance(answer, int) or isinstance(answer, bool) or not 0 <= answer < len(options):
                issues.append("answer must be a valid option index")
        if issues:
            problems.append(f"question {label}: {'; '.join(issues)}")
        else:
            seen.add(q["id"])
    return problems


def load_bank(path=DEFAULT_SEED_PATH):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def seed(conn, bank, now_ms=None):
    """Syncs `bank` into the database. Returns counts of what changed."""
    problems = validate_bank(bank)
    if problems:
        raise SeedError("Question bank is invalid:\n  " + "\n  ".join(problems))

    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    counts = {"inserted": 0, "updated": 0, "unchanged": 0, "deactivated": 0}

    with db.transaction(conn):
        for position, topic in enumerate(bank["topics"]):
            conn.execute(
                """INSERT INTO topics (id, name, icon, description, position) VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT (id) DO UPDATE SET name = excluded.name, icon = excluded.icon,
                     description = excluded.description, position = excluded.position""",
                (topic["id"], topic["name"], topic.get("icon", ""), topic.get("description", ""), position),
            )

        for position, q in enumerate(bank["questions"]):
            counts[_upsert_question(conn, q, position, now_ms)] += 1

        file_ids = [q["id"] for q in bank["questions"]]
        placeholders = ",".join("?" * len(file_ids))
        counts["deactivated"] = conn.execute(
            f"UPDATE questions SET is_active = 0, updated_at = ? WHERE is_active = 1 AND id NOT IN ({placeholders})",
            (now_ms, *file_ids),
        ).rowcount
    return counts


def _upsert_question(conn, q, position, now_ms):
    fields = (q["topic"], q["difficulty"], q["question"], q.get("code") or None, q["explanation"], position)
    wanted_options = [(text, int(i == q["answer"])) for i, text in enumerate(q["options"])]

    existing = conn.execute(
        "SELECT topic_id, difficulty, prompt, code, explanation, position, is_active FROM questions WHERE id = ?",
        (q["id"],),
    ).fetchone()

    if existing is None:
        conn.execute(
            """INSERT INTO questions (id, topic_id, difficulty, prompt, code, explanation, position, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (q["id"], *fields, now_ms),
        )
        _insert_options(conn, q["id"], wanted_options)
        return "inserted"

    current_options = [
        (row["text"], row["is_correct"])
        for row in conn.execute("SELECT text, is_correct FROM question_options WHERE question_id = ? ORDER BY position", (q["id"],))
    ]
    fields_changed = tuple(existing)[:6] != fields or existing["is_active"] != 1
    options_changed = current_options != wanted_options

    if not fields_changed and not options_changed:
        return "unchanged"

    if options_changed:
        played = conn.execute("SELECT 1 FROM attempt_questions WHERE question_id = ? LIMIT 1", (q["id"],)).fetchone()
        if played:
            raise SeedError(
                f"question {q['id']}: its options changed but it has already been played. "
                "Give the edited question a new id (the old one will be deactivated)."
            )
        conn.execute("DELETE FROM question_options WHERE question_id = ?", (q["id"],))
        _insert_options(conn, q["id"], wanted_options)

    conn.execute(
        """UPDATE questions SET topic_id = ?, difficulty = ?, prompt = ?, code = ?, explanation = ?,
             position = ?, is_active = 1, updated_at = ? WHERE id = ?""",
        (*fields, now_ms, q["id"]),
    )
    return "updated"


def _insert_options(conn, question_id, options):
    conn.executemany(
        "INSERT INTO question_options (question_id, position, text, is_correct) VALUES (?, ?, ?, ?)",
        [(question_id, i, text, correct) for i, (text, correct) in enumerate(options)],
    )


def main():
    from .app import DEFAULT_DB_PATH  # local import: app imports this module

    parser = argparse.ArgumentParser(description="Sync data/questions.json into the database.")
    parser.add_argument("--db", default=str(DEFAULT_DB_PATH))
    parser.add_argument("--file", default=str(DEFAULT_SEED_PATH))
    args = parser.parse_args()

    conn = db.connect(args.db)
    db.migrate(conn)
    try:
        counts = seed(conn, load_bank(args.file))
    except SeedError as error:
        raise SystemExit(str(error)) from None
    print(", ".join(f"{k}: {v}" for k, v in counts.items()))


if __name__ == "__main__":
    main()

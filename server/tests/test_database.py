import json
import pathlib
import sqlite3
import tempfile
import unittest

from server import db, seed
from server.tests import helpers

ROOT = pathlib.Path(__file__).resolve().parents[2]


class MigrationTests(unittest.TestCase):
    def test_migrate_is_idempotent_and_versioned(self):
        conn = db.connect(":memory:")
        self.assertEqual(db.migrate(conn), len(db.MIGRATIONS))
        self.assertEqual(db.migrate(conn), len(db.MIGRATIONS))
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        self.assertTrue({"topics", "questions", "question_options", "players", "attempts", "attempt_questions"} <= tables)

    def test_file_database_uses_wal(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = db.connect(pathlib.Path(tmp) / "t.db")
            self.assertEqual(conn.execute("PRAGMA journal_mode").fetchone()[0], "wal")
            conn.close()

    def test_transaction_rolls_back_on_error(self):
        conn = helpers.fresh_db()
        with self.assertRaises(RuntimeError):
            with db.transaction(conn):
                conn.execute("UPDATE topics SET name = 'Changed' WHERE id = 'alpha'")
                raise RuntimeError("boom")
        self.assertEqual(conn.execute("SELECT name FROM topics WHERE id = 'alpha'").fetchone()[0], "Alpha")


class ConstraintTests(unittest.TestCase):
    def setUp(self):
        self.conn = helpers.fresh_db()

    def test_foreign_keys_are_enforced(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO questions (id, topic_id, difficulty, prompt, explanation, updated_at) VALUES ('x', 'ghost', 'easy', 'p', 'e', 0)"
            )

    def test_only_one_correct_option_per_question(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("UPDATE question_options SET is_correct = 1 WHERE question_id = 'a-e1'")

    def test_check_constraints(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("UPDATE questions SET difficulty = 'extreme' WHERE id = 'a-e1'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("INSERT INTO players (name, key_hash, created_at) VALUES ('   ', 'h', 0)")

    def test_player_names_are_case_insensitively_unique(self):
        self.conn.execute("INSERT INTO players (name, key_hash, created_at) VALUES ('Alex', 'h1', 0)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("INSERT INTO players (name, key_hash, created_at) VALUES ('alex', 'h2', 0)")


class SeedTests(unittest.TestCase):
    def test_shipped_question_bank_is_valid_and_balanced(self):
        bank = seed.load_bank()
        self.assertEqual(seed.validate_bank(bank), [])
        for topic in bank["topics"]:
            mine = [q for q in bank["questions"] if q["topic"] == topic["id"]]
            for difficulty in ("easy", "medium", "hard"):
                self.assertGreaterEqual(sum(q["difficulty"] == difficulty for q in mine), 4, f"{topic['id']}/{difficulty}")
            positions = [q["answer"] for q in mine]
            self.assertLessEqual(max(positions.count(i) for i in range(4)) / len(mine), 0.35, f"{topic['id']} answer bias")

    def test_seed_loads_bank_and_is_idempotent(self):
        conn = db.connect(":memory:")
        db.migrate(conn)
        first = seed.seed(conn, helpers.bank())
        self.assertEqual(first["inserted"], 8)
        again = seed.seed(conn, helpers.bank())
        self.assertEqual(again, {"inserted": 0, "updated": 0, "unchanged": 8, "deactivated": 0})
        self.assertEqual(conn.execute("SELECT COUNT(*) FROM question_options").fetchone()[0], 32)

    def test_seed_updates_changed_and_deactivates_removed(self):
        conn = helpers.fresh_db()
        bank = helpers.bank()
        bank["questions"][0]["explanation"] = "Better explanation."
        del bank["questions"][-1]
        counts = seed.seed(conn, bank)
        self.assertEqual((counts["updated"], counts["deactivated"]), (1, 1))
        self.assertEqual(conn.execute("SELECT is_active FROM questions WHERE id = 'b-h2'").fetchone()[0], 0)
        # Bringing it back re-activates it.
        self.assertEqual(seed.seed(conn, helpers.bank())["updated"], 2)

    def test_seed_rejects_option_changes_to_played_questions(self):
        service, conn, _ = helpers.make_service()
        player = service.create_player({"name": "Sam"})
        service.create_attempt(player, helpers.config(count=8))
        bank = helpers.bank()
        bank["questions"][0]["options"] = ["new", "set", "of", "options"]
        with self.assertRaises(seed.SeedError):
            seed.seed(conn, bank)
        # Nothing was half-applied.
        self.assertEqual(conn.execute("SELECT text FROM question_options WHERE question_id = 'a-e1' AND position = 0").fetchone()[0], "zero")

    def test_invalid_bank_is_rejected_as_a_whole(self):
        conn = helpers.fresh_db()
        bank = helpers.bank()
        bank["questions"].append({**bank["questions"][0]})  # duplicate id
        bank["questions"].append(helpers.question("x1", "ghost", "easy", 0))
        bank["questions"].append({**helpers.question("x2", "alpha", "easy", 9)})
        problems = seed.validate_bank(bank)
        self.assertEqual(len(problems), 3)
        with self.assertRaises(seed.SeedError):
            seed.seed(conn, bank)

    def test_json_file_round_trips(self):
        raw = (ROOT / "data" / "questions.json").read_text(encoding="utf-8")
        self.assertEqual(json.loads(raw)["version"], 1)


if __name__ == "__main__":
    unittest.main()

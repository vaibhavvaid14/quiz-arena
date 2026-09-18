"""Shared fixtures: a small question bank, a controllable clock, fresh databases."""

import random

from server import db, seed
from server.service import QuizService

TOPICS = [
    {"id": "alpha", "name": "Alpha", "icon": "A", "description": "First topic"},
    {"id": "beta", "name": "Beta", "icon": "B", "description": "Second topic"},
]


def question(qid, topic, difficulty, answer):
    return {
        "id": qid,
        "topic": topic,
        "difficulty": difficulty,
        "question": f"Question {qid}?",
        "options": ["zero", "one", "two", "three"],
        "answer": answer,
        "explanation": f"Because {answer}.",
    }


QUESTIONS = [
    question("a-e1", "alpha", "easy", 0),
    question("a-e2", "alpha", "easy", 1),
    question("a-m1", "alpha", "medium", 2),
    question("a-h1", "alpha", "hard", 3),
    question("b-e1", "beta", "easy", 1),
    question("b-m1", "beta", "medium", 0),
    question("b-h1", "beta", "hard", 2),
    question("b-h2", "beta", "hard", 3),
]


def bank(**overrides):
    return {"version": 1, "topics": [dict(t) for t in TOPICS], "questions": [dict(q) for q in QUESTIONS], **overrides}


class FakeClock:
    def __init__(self, start=1_700_000_000_000):
        self.now = start

    def __call__(self):
        return self.now

    def advance(self, ms):
        self.now += ms


def fresh_db(path=":memory:"):
    conn = db.connect(path)
    db.migrate(conn)
    seed.seed(conn, bank(), now_ms=1)
    return conn


def make_service(conn=None, clock=None, seed_value=7):
    conn = conn or fresh_db()
    clock = clock or FakeClock()
    return QuizService(conn, clock=clock, rng=random.Random(seed_value)), conn, clock


def config(**overrides):
    return {
        "topics": ["alpha", "beta"],
        "difficulty": "mixed",
        "count": 5,
        "timerMode": "off",
        "secondsPerQuestion": 30,
        "shuffle": True,
        "negativeMarking": False,
        **overrides,
    }


def correct_option(conn, state):
    """The correct option id for the current question (tests may peek; clients may not)."""
    qid = state["current"]["question"]["id"]
    return conn.execute("SELECT id FROM question_options WHERE question_id = ? AND is_correct = 1", (qid,)).fetchone()[0]


def wrong_option(conn, state):
    right = correct_option(conn, state)
    return next(o["id"] for o in state["current"]["question"]["options"] if o["id"] != right)

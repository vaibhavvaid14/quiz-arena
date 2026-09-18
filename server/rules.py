"""Game rules: the single source of truth for scoring and timing.

The browser receives these via GET /api/catalog, so nothing is duplicated
client-side.
"""

DIFFICULTIES = ("easy", "medium", "hard")
DIFFICULTY_FILTERS = ("mixed", *DIFFICULTIES)
DIFFICULTY_POINTS = {"easy": 10, "medium": 20, "hard": 30}

TIMER_MODES = ("question", "session", "off")

SPEED_BONUS_RATIO = 0.5  # up to +50% of base for instant answers (per-question timer only)
NEGATIVE_MARK_RATIO = 0.25  # -25% of base for a wrong answer when negative marking is on

MIN_QUESTIONS, MAX_QUESTIONS = 1, 50
MIN_SECONDS, MAX_SECONDS = 5, 600
PLAYER_NAME_MAX = 30

# Network/render latency we do not charge to the player when timing an answer.
LATENCY_ALLOWANCE_MS = 300
# An answer arriving this long after the deadline still counts (slow networks).
DEADLINE_GRACE_MS = 1500

# Leaderboard eligibility: finished (not quit) attempts of at least this many
# questions that were not "retry missed" runs.
LEADERBOARD_MIN_QUESTIONS = 5

GRADE_BANDS = (
    (90, "A", "Outstanding"),
    (75, "B", "Great work"),
    (60, "C", "Good effort"),
    (40, "D", "Keep practising"),
    (0, "F", "Time to review"),
)


def grade_for(percentage):
    for minimum, grade, label in GRADE_BANDS:
        if percentage >= minimum:
            return {"grade": grade, "label": label}
    return {"grade": "F", "label": GRADE_BANDS[-1][2]}


def score_answer(status, difficulty, timer_mode, negative_marking, remaining_fraction=0.0):
    """Points for one resolved question."""
    base = DIFFICULTY_POINTS[difficulty]
    if status == "correct":
        if timer_mode != "question":
            return base
        fraction = min(1.0, max(0.0, remaining_fraction))
        return base + round_half_up(base * SPEED_BONUS_RATIO * fraction)
    if status == "wrong" and negative_marking:
        return -round_half_up(base * NEGATIVE_MARK_RATIO)
    return 0


def max_points_for(difficulty, timer_mode):
    base = DIFFICULTY_POINTS[difficulty]
    return base + round_half_up(base * SPEED_BONUS_RATIO) if timer_mode == "question" else base


def round_half_up(value):
    """Python's round() is banker's rounding (round(2.5) == 2); scores want 2.5 -> 3."""
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)


def public_rules():
    return {
        "difficultyPoints": DIFFICULTY_POINTS,
        "speedBonusRatio": SPEED_BONUS_RATIO,
        "negativeMarkRatio": NEGATIVE_MARK_RATIO,
        "minQuestions": MIN_QUESTIONS,
        "maxQuestions": MAX_QUESTIONS,
        "minSeconds": MIN_SECONDS,
        "maxSeconds": MAX_SECONDS,
        "playerNameMax": PLAYER_NAME_MAX,
        "leaderboardMinQuestions": LEADERBOARD_MIN_QUESTIONS,
        "gradeBands": [{"min": m, "grade": g, "label": label} for m, g, label in GRADE_BANDS],
    }

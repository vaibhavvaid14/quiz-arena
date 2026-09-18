/**
 * Front-end constants. Scoring rules are NOT here: the server owns them and
 * sends them with the catalog (GET /api/catalog), so they cannot drift apart.
 */

export const DIFFICULTIES = Object.freeze(['easy', 'medium', 'hard']);
export const DIFFICULTY_FILTERS = Object.freeze(['mixed', ...DIFFICULTIES]);
export const DIFFICULTY_LABELS = Object.freeze({ mixed: 'Mixed', easy: 'Easy', medium: 'Medium', hard: 'Hard' });

export const TIMER_MODES = Object.freeze({
  QUESTION: 'question', // countdown restarts on every question
  SESSION: 'session', // one countdown for the whole quiz
  OFF: 'off', // untimed (time is still measured for stats)
});

export const ITEM_STATUS = Object.freeze({
  PENDING: 'pending',
  CORRECT: 'correct',
  WRONG: 'wrong',
  TIMEOUT: 'timeout',
  SKIPPED: 'skipped',
});

export const END_REASONS = Object.freeze({
  COMPLETED: 'completed',
  TIME_UP: 'time-up',
  QUIT: 'quit',
});

export const QUESTION_COUNT_OPTIONS = Object.freeze([5, 10, 15, 20]);
export const SECONDS_PER_QUESTION_OPTIONS = Object.freeze([10, 15, 20, 30, 45, 60]);

export const DEFAULT_CONFIG = Object.freeze({
  topics: [],
  difficulty: 'mixed',
  count: 10,
  timerMode: TIMER_MODES.QUESTION,
  secondsPerQuestion: 30,
  shuffle: true,
  negativeMarking: false,
});

/** Remaining-time fraction below which the timer switches to its warning / danger look. */
export const TIMER_WARNING_FRACTION = 0.5;
export const TIMER_DANGER_FRACTION = 0.2;

export const STORAGE_NAMESPACE = 'quizapp';
export const STORAGE_SCHEMA_VERSION = 2;
export const LEADERBOARD_SIZE = 20;

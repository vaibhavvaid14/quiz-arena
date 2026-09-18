/**
 * Application-wide constants and defaults.
 * Everything tunable about scoring, timing and persistence lives here so the
 * engine and UI never hard-code magic numbers.
 */

export const APP_VERSION = '1.0.0';

export const DIFFICULTIES = Object.freeze(['easy', 'medium', 'hard']);

export const DIFFICULTY_META = Object.freeze({
  easy: { label: 'Easy', points: 10 },
  medium: { label: 'Medium', points: 20 },
  hard: { label: 'Hard', points: 30 },
});

/** Difficulty filter options shown on the setup screen ('mixed' = no filter). */
export const DIFFICULTY_FILTERS = Object.freeze(['mixed', ...DIFFICULTIES]);

export const TIMER_MODES = Object.freeze({
  QUESTION: 'question', // countdown restarts on every question
  SESSION: 'session', // one countdown for the whole quiz
  OFF: 'off', // untimed (time is still measured for stats)
});

export const QUESTION_COUNT_OPTIONS = Object.freeze([5, 10, 15, 20]);
export const SECONDS_PER_QUESTION_OPTIONS = Object.freeze([10, 15, 20, 30, 45, 60]);

export const DEFAULT_CONFIG = Object.freeze({
  playerName: '',
  topics: [],
  difficulty: 'mixed',
  count: 10,
  timerMode: TIMER_MODES.QUESTION,
  secondsPerQuestion: 30,
  shuffle: true,
  negativeMarking: false,
});

/** Share of a question's base points awarded as a speed bonus (per-question timer only). */
export const SPEED_BONUS_RATIO = 0.5;

/** Share of a question's base points deducted for a wrong answer when negative marking is on. */
export const NEGATIVE_MARK_RATIO = 0.25;

/** Remaining-time fraction below which the timer switches to its warning / danger look. */
export const TIMER_WARNING_FRACTION = 0.5;
export const TIMER_DANGER_FRACTION = 0.2;

export const GRADE_BANDS = Object.freeze([
  { min: 90, grade: 'A', label: 'Outstanding' },
  { min: 75, grade: 'B', label: 'Great work' },
  { min: 60, grade: 'C', label: 'Good effort' },
  { min: 40, grade: 'D', label: 'Keep practising' },
  { min: 0, grade: 'F', label: 'Time to review' },
]);

export const STORAGE_NAMESPACE = 'quizapp';
export const STORAGE_SCHEMA_VERSION = 1;
export const HISTORY_LIMIT = 50;
export const PLAYER_NAME_MAX_LENGTH = 30;

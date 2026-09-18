/**
 * Quiz engine — pure, framework-free state transitions.
 *
 * A session is a plain serialisable object, so it can be saved to storage at any
 * moment and resumed later. Every transition returns a NEW session object; the
 * input is never mutated. The UI owns time measurement and passes durations in.
 *
 * Session shape
 * {
 *   id, schemaVersion, createdAt, finishedAt|null,
 *   status: 'active' | 'finished',
 *   endReason: null | 'completed' | 'time-up' | 'quit',
 *   config: { ...normalised quiz config },
 *   currentIndex: number,
 *   clock: { questionElapsedMs, sessionElapsedMs },   // for resuming timers
 *   items: [{
 *     questionId,
 *     optionOrder: number[],   // display position -> original option index
 *     selected: number|null,   // ORIGINAL option index the player chose
 *     status: 'pending' | 'correct' | 'wrong' | 'timeout' | 'skipped',
 *     timeSpentMs, points,
 *   }]
 * }
 */

import {
  DEFAULT_CONFIG,
  DIFFICULTIES,
  DIFFICULTY_FILTERS,
  DIFFICULTY_META,
  GRADE_BANDS,
  NEGATIVE_MARK_RATIO,
  PLAYER_NAME_MAX_LENGTH,
  SPEED_BONUS_RATIO,
  TIMER_MODES,
} from './config.js';
import { createId, shuffle } from './random.js';

export const SESSION_SCHEMA_VERSION = 1;

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

export class QuizError extends Error {
  constructor(message) {
    super(message);
    this.name = 'QuizError';
  }
}

/* ------------------------------------------------------------------ config */

/**
 * Coerces arbitrary (possibly stale or hand-edited) config into a valid one.
 */
export function normalizeConfig(config = {}) {
  const merged = { ...DEFAULT_CONFIG, ...config };
  const timerModes = Object.values(TIMER_MODES);
  return {
    playerName: String(merged.playerName ?? '').trim().slice(0, PLAYER_NAME_MAX_LENGTH),
    topics: Array.isArray(merged.topics) ? [...new Set(merged.topics.filter((t) => typeof t === 'string'))] : [],
    difficulty: DIFFICULTY_FILTERS.includes(merged.difficulty) ? merged.difficulty : DEFAULT_CONFIG.difficulty,
    count: clampInt(merged.count, 1, 100, DEFAULT_CONFIG.count),
    timerMode: timerModes.includes(merged.timerMode) ? merged.timerMode : DEFAULT_CONFIG.timerMode,
    secondsPerQuestion: clampInt(merged.secondsPerQuestion, 5, 600, DEFAULT_CONFIG.secondsPerQuestion),
    shuffle: Boolean(merged.shuffle),
    negativeMarking: Boolean(merged.negativeMarking),
  };
}

function clampInt(value, min, max, fallback) {
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

/** Per-question limit in ms, or Infinity when questions are not individually timed. */
export function getQuestionTimeLimitMs(config) {
  return config.timerMode === TIMER_MODES.QUESTION ? config.secondsPerQuestion * 1000 : Infinity;
}

/** Whole-quiz limit in ms, or Infinity when the session is not timed as a whole. */
export function getSessionTimeLimitMs(config, questionCount) {
  return config.timerMode === TIMER_MODES.SESSION ? config.secondsPerQuestion * 1000 * questionCount : Infinity;
}

/* ----------------------------------------------------------------- creation */

/**
 * Builds a new active session.
 * @param {ReturnType<import('./questionBank.js').createQuestionBank>} bank
 * @param {object} rawConfig
 * @param {{ rng?: () => number, now?: number, questionIds?: string[] }} [options]
 *   `questionIds` pins the exact question set (used by "Retry incorrect").
 */
export function createSession(bank, rawConfig, { rng = Math.random, now = Date.now(), questionIds } = {}) {
  const config = normalizeConfig(rawConfig);

  let pool = questionIds
    ? questionIds.map((id) => bank.getQuestion(id)).filter(Boolean)
    : bank.filter({ topics: config.topics, difficulty: config.difficulty });

  if (pool.length === 0) {
    throw new QuizError('No questions match the selected topics and difficulty.');
  }

  // Always sample randomly so repeated quizzes see different questions; when
  // shuffling is off, present the sample in a gentle easy -> hard progression.
  pool = shuffle(pool, rng).slice(0, Math.min(config.count, pool.length));
  if (!config.shuffle) {
    pool.sort(
      (a, b) =>
        DIFFICULTIES.indexOf(a.difficulty) - DIFFICULTIES.indexOf(b.difficulty) || bank.orderOf(a.id) - bank.orderOf(b.id),
    );
  }

  const items = pool.map((question) => {
    const identity = question.options.map((_, index) => index);
    return {
      questionId: question.id,
      optionOrder: config.shuffle ? shuffle(identity, rng) : identity,
      selected: null,
      status: ITEM_STATUS.PENDING,
      timeSpentMs: 0,
      points: 0,
    };
  });

  return {
    id: createId('quiz', rng, now),
    schemaVersion: SESSION_SCHEMA_VERSION,
    createdAt: now,
    finishedAt: null,
    status: 'active',
    endReason: null,
    config: { ...config, count: items.length },
    currentIndex: 0,
    clock: { questionElapsedMs: 0, sessionElapsedMs: 0 },
    items,
  };
}

/* ------------------------------------------------------------------ queries */

export function getCurrentItem(session) {
  return session.items[session.currentIndex] ?? null;
}

export function isLastQuestion(session) {
  return session.currentIndex >= session.items.length - 1;
}

export function isItemAnswered(item) {
  return item.status !== ITEM_STATUS.PENDING;
}

/** Running totals for the in-quiz header. */
export function getLiveStats(session) {
  let points = 0;
  let correct = 0;
  let answered = 0;
  for (const item of session.items) {
    if (!isItemAnswered(item)) continue;
    answered += 1;
    points += item.points;
    if (item.status === ITEM_STATUS.CORRECT) correct += 1;
  }
  return { points: Math.max(0, points), correct, answered, streak: currentStreak(session.items) };
}

function currentStreak(items) {
  let streak = 0;
  for (const item of items) {
    if (item.status === ITEM_STATUS.CORRECT) streak += 1;
    else if (item.status !== ITEM_STATUS.PENDING) streak = 0;
  }
  return streak;
}

function bestStreak(items) {
  let best = 0;
  let run = 0;
  for (const item of items) {
    run = item.status === ITEM_STATUS.CORRECT ? run + 1 : 0;
    best = Math.max(best, run);
  }
  return best;
}

/* ------------------------------------------------------------------ scoring */

/**
 * Points for one answer.
 *  - correct: base points by difficulty, plus up to SPEED_BONUS_RATIO extra for
 *    answering quickly (per-question timer mode only)
 *  - wrong:   0, or -NEGATIVE_MARK_RATIO of base with negative marking
 *  - timeout / skipped: 0
 */
export function scoreAnswer({ status, difficulty, config, remainingFraction = 0 }) {
  const base = DIFFICULTY_META[difficulty]?.points ?? 0;
  if (status === ITEM_STATUS.CORRECT) {
    const bonus =
      config.timerMode === TIMER_MODES.QUESTION
        ? Math.round(base * SPEED_BONUS_RATIO * Math.min(1, Math.max(0, remainingFraction)))
        : 0;
    return base + bonus;
  }
  if (status === ITEM_STATUS.WRONG && config.negativeMarking) {
    return -Math.round(base * NEGATIVE_MARK_RATIO);
  }
  return 0;
}

/** Highest achievable score for a question under the session's rules. */
export function maxPointsFor(difficulty, config) {
  const base = DIFFICULTY_META[difficulty]?.points ?? 0;
  return config.timerMode === TIMER_MODES.QUESTION ? base + Math.round(base * SPEED_BONUS_RATIO) : base;
}

export function gradeFor(percentage) {
  return GRADE_BANDS.find((band) => percentage >= band.min) ?? GRADE_BANDS[GRADE_BANDS.length - 1];
}

/* -------------------------------------------------------------- transitions */

function assertActivePending(session) {
  if (session.status !== 'active') throw new QuizError('This quiz has already finished.');
  const item = getCurrentItem(session);
  if (!item) throw new QuizError('There is no current question.');
  if (isItemAnswered(item)) throw new QuizError('This question has already been answered.');
  return item;
}

function replaceCurrentItem(session, patch) {
  const items = session.items.slice();
  items[session.currentIndex] = { ...items[session.currentIndex], ...patch };
  return { ...session, items };
}

/**
 * Records the player's choice for the current question.
 * @param {number} selected ORIGINAL option index (not the display position)
 * @param {{ timeSpentMs?: number, remainingFraction?: number }} timing
 * @returns {{ session: object, correct: boolean, points: number }}
 */
export function answerCurrent(session, bank, selected, { timeSpentMs = 0, remainingFraction = 0 } = {}) {
  const item = assertActivePending(session);
  const question = bank.getQuestion(item.questionId);
  if (!question) throw new QuizError(`Question ${item.questionId} is missing from the bank.`);
  if (!Number.isInteger(selected) || selected < 0 || selected >= question.options.length) {
    throw new QuizError('Invalid option selected.');
  }

  const correct = selected === question.answer;
  const status = correct ? ITEM_STATUS.CORRECT : ITEM_STATUS.WRONG;
  const points = scoreAnswer({ status, difficulty: question.difficulty, config: session.config, remainingFraction });
  const next = replaceCurrentItem(session, { selected, status, points, timeSpentMs: Math.max(0, Math.round(timeSpentMs)) });
  return { session: next, correct, points };
}

/** Marks the current question as timed out (no answer, no points). */
export function timeoutCurrent(session, { timeSpentMs = 0 } = {}) {
  assertActivePending(session);
  return replaceCurrentItem(session, {
    selected: null,
    status: ITEM_STATUS.TIMEOUT,
    points: 0,
    timeSpentMs: Math.max(0, Math.round(timeSpentMs)),
  });
}

/**
 * Moves to the next question, or finishes the quiz after the last one.
 * The current question must have been answered (or timed out) first.
 */
export function goToNext(session, { now = Date.now() } = {}) {
  if (session.status !== 'active') throw new QuizError('This quiz has already finished.');
  const item = getCurrentItem(session);
  if (item && !isItemAnswered(item)) throw new QuizError('Answer the current question before moving on.');
  if (isLastQuestion(session)) return finishSession(session, { now, reason: END_REASONS.COMPLETED });
  return {
    ...session,
    currentIndex: session.currentIndex + 1,
    clock: { ...session.clock, questionElapsedMs: 0 },
  };
}

/**
 * Ends the quiz. Any unanswered question is marked as skipped (0 points).
 * Idempotent for sessions that are already finished.
 */
export function finishSession(session, { now = Date.now(), reason = END_REASONS.COMPLETED } = {}) {
  if (session.status === 'finished') return session;
  return {
    ...session,
    status: 'finished',
    endReason: reason,
    finishedAt: now,
    items: session.items.map((item) =>
      isItemAnswered(item) ? item : { ...item, status: ITEM_STATUS.SKIPPED, selected: null, points: 0 },
    ),
  };
}

/** Stores timer progress so a reloaded page can resume exactly where it paused. */
export function withClock(session, { questionElapsedMs, sessionElapsedMs }) {
  return {
    ...session,
    clock: {
      questionElapsedMs: Math.max(0, Math.round(questionElapsedMs ?? session.clock.questionElapsedMs)),
      sessionElapsedMs: Math.max(0, Math.round(sessionElapsedMs ?? session.clock.sessionElapsedMs)),
    },
  };
}

/* ------------------------------------------------------------------ results */

function emptyBucket() {
  return { correct: 0, total: 0, points: 0 };
}

function bucketList(map, labelFor) {
  return [...map.entries()].map(([key, b]) => ({
    key,
    label: labelFor(key),
    ...b,
    percentage: b.total === 0 ? 0 : Math.round((b.correct / b.total) * 100),
  }));
}

/**
 * Full results summary + per-question review for a (normally finished) session.
 * Questions that no longer exist in the bank are ignored gracefully.
 */
export function summarizeSession(session, bank) {
  const byTopic = new Map();
  const byDifficulty = new Map(DIFFICULTIES.map((d) => [d, emptyBucket()]));
  const counts = { correct: 0, wrong: 0, timeout: 0, skipped: 0, pending: 0 };
  const review = [];
  let points = 0;
  let maxPoints = 0;
  let durationMs = 0;

  const validItems = session.items.filter((item) => bank.getQuestion(item.questionId));

  validItems.forEach((item, index) => {
    const question = bank.getQuestion(item.questionId);
    counts[item.status] += 1;
    points += item.points;
    maxPoints += maxPointsFor(question.difficulty, session.config);
    durationMs += item.timeSpentMs;

    if (!byTopic.has(question.topic)) byTopic.set(question.topic, emptyBucket());
    for (const bucket of [byTopic.get(question.topic), byDifficulty.get(question.difficulty)]) {
      bucket.total += 1;
      bucket.points += item.points;
      if (item.status === ITEM_STATUS.CORRECT) bucket.correct += 1;
    }

    const order = Array.isArray(item.optionOrder) && item.optionOrder.length === question.options.length
      ? item.optionOrder
      : question.options.map((_, i) => i);

    review.push({
      number: index + 1,
      questionId: question.id,
      topic: question.topic,
      difficulty: question.difficulty,
      question: question.question,
      code: question.code ?? null,
      explanation: question.explanation,
      status: item.status,
      points: item.points,
      timeSpentMs: item.timeSpentMs,
      options: order.map((originalIndex) => ({
        text: question.options[originalIndex],
        isCorrect: originalIndex === question.answer,
        isSelected: originalIndex === item.selected,
      })),
    });
  });

  const total = validItems.length;
  const answered = counts.correct + counts.wrong;
  const percentage = total === 0 ? 0 : Math.round((counts.correct / total) * 100);

  return {
    total,
    answered,
    correct: counts.correct,
    wrong: counts.wrong,
    timedOut: counts.timeout,
    skipped: counts.skipped + counts.pending,
    percentage,
    accuracy: answered === 0 ? 0 : Math.round((counts.correct / answered) * 100),
    points: Math.max(0, points),
    maxPoints,
    grade: gradeFor(percentage),
    bestStreak: bestStreak(validItems),
    durationMs,
    averageTimeMs: total === 0 ? 0 : Math.round(durationMs / total),
    endReason: session.endReason,
    byTopic: bucketList(byTopic, (id) => bank.getTopic(id)?.name ?? id).sort(
      (a, b) => bank.topics.findIndex((t) => t.id === a.key) - bank.topics.findIndex((t) => t.id === b.key),
    ),
    byDifficulty: bucketList(byDifficulty, (d) => DIFFICULTY_META[d].label).filter((b) => b.total > 0),
    review,
    missedQuestionIds: validItems.filter((item) => item.status !== ITEM_STATUS.CORRECT).map((item) => item.questionId),
  };
}

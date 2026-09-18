import { TIMER_MODES } from '../js/core/config.js';
import {
  END_REASONS,
  ITEM_STATUS,
  QuizError,
  answerCurrent,
  createSession,
  finishSession,
  getLiveStats,
  goToNext,
  normalizeConfig,
  scoreAnswer,
  summarizeSession,
  timeoutCurrent,
  withClock,
} from '../js/core/quizEngine.js';
import { createSeededRng } from '../js/core/random.js';
import { fixtureBank } from './fixtures.js';
import { assert, deepEqual, equal, test, throws } from './harness.js';

const bank = fixtureBank();
const base = { topics: ['alpha', 'beta'], difficulty: 'mixed', count: 5, timerMode: TIMER_MODES.OFF, shuffle: true };
const correctIndex = (session) => bank.getQuestion(session.items[session.currentIndex].questionId).answer;
const wrongIndex = (session) => (correctIndex(session) + 1) % 4;

test('engine: normalizeConfig clamps and repairs bad input', () => {
  const c = normalizeConfig({ count: '999', difficulty: 'impossible', timerMode: 'x', secondsPerQuestion: 1, topics: ['a', 'a', 3], playerName: '  Sam  ' });
  equal(c.count, 100);
  equal(c.difficulty, 'mixed');
  equal(c.timerMode, TIMER_MODES.QUESTION);
  equal(c.secondsPerQuestion, 5);
  deepEqual(c.topics, ['a']);
  equal(c.playerName, 'Sam');
});

test('engine: createSession is deterministic for a seed and caps count at the pool size', () => {
  const s1 = createSession(bank, { ...base, count: 50 }, { rng: createSeededRng(7), now: 1000 });
  const s2 = createSession(bank, { ...base, count: 50 }, { rng: createSeededRng(7), now: 1000 });
  deepEqual(s1.items, s2.items);
  equal(s1.items.length, 8);
  equal(s1.config.count, 8);
  equal(new Set(s1.items.map((i) => i.questionId)).size, 8, 'no duplicate questions');
  equal(s1.status, 'active');
});

test('engine: createSession filters by topic and difficulty', () => {
  const s = createSession(bank, { ...base, topics: ['beta'], difficulty: 'hard' }, { rng: createSeededRng(1) });
  equal(s.items.length, 2);
  assert(s.items.every((i) => ['b-h1', 'b-h2'].includes(i.questionId)));
});

test('engine: createSession throws QuizError when nothing matches', () => {
  throws(() => createSession(bank, { ...base, topics: ['nope'] }), QuizError);
});

test('engine: shuffle off orders easy -> hard with original option order', () => {
  const s = createSession(bank, { ...base, count: 8, shuffle: false }, { rng: createSeededRng(3) });
  const difficulties = s.items.map((i) => bank.getQuestion(i.questionId).difficulty);
  deepEqual(difficulties, ['easy', 'easy', 'easy', 'medium', 'medium', 'hard', 'hard', 'hard']);
  assert(s.items.every((i) => JSON.stringify(i.optionOrder) === '[0,1,2,3]'));
});

test('engine: shuffled option orders are permutations', () => {
  const s = createSession(bank, { ...base, count: 8 }, { rng: createSeededRng(11) });
  for (const item of s.items) deepEqual(item.optionOrder.slice().sort(), [0, 1, 2, 3]);
});

test('engine: pinned questionIds (retry missed) are respected', () => {
  const s = createSession(bank, base, { questionIds: ['b-h2', 'a-e1', 'missing'], rng: createSeededRng(2) });
  deepEqual(s.items.map((i) => i.questionId).sort(), ['a-e1', 'b-h2']);
});

test('engine: scoring — base points, speed bonus, negative marking', () => {
  const perQ = normalizeConfig({ timerMode: TIMER_MODES.QUESTION });
  equal(scoreAnswer({ status: 'correct', difficulty: 'hard', config: perQ, remainingFraction: 1 }), 45);
  equal(scoreAnswer({ status: 'correct', difficulty: 'hard', config: perQ, remainingFraction: 0.5 }), 38);
  equal(scoreAnswer({ status: 'correct', difficulty: 'easy', config: normalizeConfig({ timerMode: TIMER_MODES.OFF }), remainingFraction: 1 }), 10);
  equal(scoreAnswer({ status: 'wrong', difficulty: 'medium', config: perQ }), 0);
  equal(scoreAnswer({ status: 'wrong', difficulty: 'medium', config: { ...perQ, negativeMarking: true } }), -5);
  equal(scoreAnswer({ status: 'timeout', difficulty: 'hard', config: { ...perQ, negativeMarking: true } }), 0);
});

test('engine: answerCurrent records correct and wrong answers immutably', () => {
  const s0 = createSession(bank, base, { rng: createSeededRng(5) });
  const { session: s1, correct } = answerCurrent(s0, bank, correctIndex(s0), { timeSpentMs: 1234.4 });
  assert(correct);
  equal(s0.items[0].status, ITEM_STATUS.PENDING, 'input not mutated');
  equal(s1.items[0].status, ITEM_STATUS.CORRECT);
  equal(s1.items[0].timeSpentMs, 1234);
  assert(s1.items[0].points > 0);

  const s2 = goToNext(s1);
  const { session: s3, correct: c2 } = answerCurrent(s2, bank, wrongIndex(s2));
  equal(c2, false);
  equal(s3.items[1].status, ITEM_STATUS.WRONG);
  equal(s3.items[1].selected, wrongIndex(s2));
});

test('engine: guards — double answer, invalid option, next before answering', () => {
  const s0 = createSession(bank, base, { rng: createSeededRng(5) });
  throws(() => goToNext(s0), QuizError);
  throws(() => answerCurrent(s0, bank, 9), QuizError);
  const { session: s1 } = answerCurrent(s0, bank, 0);
  throws(() => answerCurrent(s1, bank, 0), QuizError);
  throws(() => timeoutCurrent(s1), QuizError);
});

test('engine: timeout scores zero and allows moving on', () => {
  const s0 = createSession(bank, { ...base, negativeMarking: true }, { rng: createSeededRng(5) });
  const s1 = timeoutCurrent(s0, { timeSpentMs: 30000 });
  equal(s1.items[0].status, ITEM_STATUS.TIMEOUT);
  equal(s1.items[0].points, 0);
  equal(goToNext(s1).currentIndex, 1);
});

test('engine: finishing after the last question completes the session', () => {
  let s = createSession(bank, { ...base, count: 2 }, { rng: createSeededRng(9) });
  s = answerCurrent(s, bank, correctIndex(s)).session;
  s = goToNext(s);
  s = answerCurrent(s, bank, correctIndex(s)).session;
  s = goToNext(s, { now: 5000 });
  equal(s.status, 'finished');
  equal(s.endReason, END_REASONS.COMPLETED);
  equal(s.finishedAt, 5000);
  throws(() => goToNext(s), QuizError);
});

test('engine: finishSession marks unanswered as skipped and is idempotent', () => {
  let s = createSession(bank, base, { rng: createSeededRng(4) });
  s = answerCurrent(s, bank, correctIndex(s)).session;
  const f = finishSession(s, { reason: END_REASONS.QUIT, now: 1 });
  equal(f.items.filter((i) => i.status === ITEM_STATUS.SKIPPED).length, 4);
  equal(f.endReason, END_REASONS.QUIT);
  equal(finishSession(f, { reason: END_REASONS.TIME_UP }), f);
});

test('engine: live stats track points, answered count and streak', () => {
  let s = createSession(bank, base, { rng: createSeededRng(4) });
  s = goToNext(answerCurrent(s, bank, correctIndex(s)).session);
  s = goToNext(answerCurrent(s, bank, correctIndex(s)).session);
  let stats = getLiveStats(s);
  equal(stats.answered, 2);
  equal(stats.correct, 2);
  equal(stats.streak, 2);
  s = answerCurrent(s, bank, wrongIndex(s)).session;
  stats = getLiveStats(s);
  equal(stats.streak, 0);
});

test('engine: negative marking never pushes the total below zero', () => {
  let s = createSession(bank, { ...base, count: 3, negativeMarking: true }, { rng: createSeededRng(8) });
  s = goToNext(answerCurrent(s, bank, wrongIndex(s)).session);
  equal(getLiveStats(s).points, 0);
  s = finishSession(s);
  equal(summarizeSession(s, bank).points, 0);
});

test('engine: summarizeSession computes totals, breakdowns and review', () => {
  let s = createSession(bank, { ...base, count: 4 }, { rng: createSeededRng(21) });
  s = goToNext(answerCurrent(s, bank, correctIndex(s), { timeSpentMs: 1000 }).session);
  s = goToNext(answerCurrent(s, bank, wrongIndex(s), { timeSpentMs: 2000 }).session);
  s = goToNext(timeoutCurrent(s, { timeSpentMs: 3000 }));
  s = finishSession(s, { reason: END_REASONS.QUIT });
  const r = summarizeSession(s, bank);

  equal(r.total, 4);
  equal(r.correct, 1);
  equal(r.wrong, 1);
  equal(r.timedOut, 1);
  equal(r.skipped, 1);
  equal(r.percentage, 25);
  equal(r.accuracy, 50);
  equal(r.durationMs, 6000);
  equal(r.averageTimeMs, 1500);
  equal(r.bestStreak, 1);
  equal(r.grade.grade, 'F');
  equal(r.endReason, END_REASONS.QUIT);
  equal(r.byTopic.reduce((n, t) => n + t.total, 0), 4);
  equal(r.missedQuestionIds.length, 3);

  // Review options are in the order the player saw, with flags pointing at the right text.
  for (const [i, entry] of r.review.entries()) {
    const question = bank.getQuestion(s.items[i].questionId);
    equal(entry.options.filter((o) => o.isCorrect).length, 1);
    equal(entry.options.find((o) => o.isCorrect).text, question.options[question.answer]);
    deepEqual(entry.options.map((o) => o.text), s.items[i].optionOrder.map((k) => question.options[k]));
  }
  equal(r.review[1].options.find((o) => o.isSelected).text, bank.getQuestion(s.items[1].questionId).options[s.items[1].selected]);
});

test('engine: summarizeSession ignores questions missing from the bank', () => {
  let s = createSession(bank, { ...base, count: 3 }, { rng: createSeededRng(6) });
  s = { ...s, items: [...s.items, { ...s.items[0], questionId: 'deleted' }] };
  equal(summarizeSession(finishSession(s), bank).total, 3);
});

test('engine: withClock stores rounded, non-negative timer progress', () => {
  const s = withClock(createSession(bank, base), { questionElapsedMs: 1500.6, sessionElapsedMs: -5 });
  deepEqual(s.clock, { questionElapsedMs: 1501, sessionElapsedMs: 0 });
});

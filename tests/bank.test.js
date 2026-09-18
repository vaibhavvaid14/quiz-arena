import { DIFFICULTIES } from '../js/core/config.js';
import { createQuestionBank, filterQuestions, validateQuestionBank } from '../js/core/questionBank.js';
import { aggregateHistory } from '../js/core/stats.js';
import { QUESTIONS, TOPICS } from '../js/data/questions.js';
import { FIXTURE_QUESTIONS, FIXTURE_TOPICS } from './fixtures.js';
import { assert, deepEqual, equal, test } from './harness.js';

test('bank: the shipped question bank is fully valid', () => {
  const { errors, questions, topics } = validateQuestionBank(TOPICS, QUESTIONS);
  deepEqual(errors, []);
  equal(questions.length, QUESTIONS.length);
  equal(topics.length, TOPICS.length);
});

test('bank: every topic offers every difficulty', () => {
  for (const topic of TOPICS) {
    for (const difficulty of DIFFICULTIES) {
      const n = QUESTIONS.filter((q) => q.topic === topic.id && q.difficulty === difficulty).length;
      assert(n >= 2, `${topic.id}/${difficulty} has only ${n} question(s)`);
    }
  }
});

test('bank: correct answers are not biased toward one option position', () => {
  const counts = [0, 0, 0, 0];
  for (const q of QUESTIONS) counts[q.answer] += 1;
  const share = Math.max(...counts) / QUESTIONS.length;
  assert(share <= 0.35, `answer position distribution too skewed: ${counts}`);
});

test('bank: validation rejects broken questions and duplicates', () => {
  const bad = [
    FIXTURE_QUESTIONS[0],
    { ...FIXTURE_QUESTIONS[1], id: FIXTURE_QUESTIONS[0].id },
    { ...FIXTURE_QUESTIONS[2], id: 'x1', answer: 4 },
    { ...FIXTURE_QUESTIONS[2], id: 'x2', options: ['same', 'Same'] },
    { ...FIXTURE_QUESTIONS[2], id: 'x3', topic: 'ghost' },
    { ...FIXTURE_QUESTIONS[2], id: 'x4', difficulty: 'extreme' },
    { ...FIXTURE_QUESTIONS[2], id: 'x5', explanation: '  ' },
    null,
  ];
  const { questions, errors } = validateQuestionBank(FIXTURE_TOPICS, bad);
  equal(questions.length, 1);
  equal(errors.length, 7);
});

test('bank: filtering and per-topic counts', () => {
  const bank = createQuestionBank(FIXTURE_TOPICS, FIXTURE_QUESTIONS);
  equal(filterQuestions(FIXTURE_QUESTIONS, { topics: ['alpha'] }).length, 4);
  equal(filterQuestions(FIXTURE_QUESTIONS, { difficulty: 'hard' }).length, 3);
  equal(filterQuestions(FIXTURE_QUESTIONS, {}).length, 8, 'no topics means all topics');
  deepEqual(bank.countByTopic('easy'), { alpha: 2, beta: 1 });
  equal(bank.getQuestion('nope'), null);
  equal(bank.getTopic('beta').name, 'Beta');
});

test('stats: aggregateHistory combines attempts and topic mastery', () => {
  const history = [
    { id: '2', summary: { total: 4, correct: 3, percentage: 75, byTopic: [{ key: 'beta', correct: 1, total: 2 }, { key: 'alpha', correct: 2, total: 2 }] } },
    { id: '1', summary: { total: 2, correct: 0, percentage: 0, byTopic: [{ key: 'alpha', correct: 0, total: 2 }] } },
  ];
  const stats = aggregateHistory(history, FIXTURE_TOPICS);
  equal(stats.attempts, 2);
  equal(stats.questions, 6);
  equal(stats.averagePercentage, 38);
  equal(stats.bestPercentage, 75);
  equal(stats.overallAccuracy, 50);
  deepEqual(stats.byTopic.map((t) => [t.key, t.percentage, t.attempts]), [['alpha', 50, 2], ['beta', 50, 1]]);
  equal(aggregateHistory([], FIXTURE_TOPICS).averagePercentage, 0);
});

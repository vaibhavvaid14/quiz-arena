/**
 * Question bank: validation, indexing and filtering.
 *
 * Schema
 *   Topic    { id: string, name: string, icon: string, description: string }
 *   Question { id: string, topic: Topic.id, difficulty: 'easy'|'medium'|'hard',
 *              question: string, code?: string, options: string[] (2-6, unique),
 *              answer: number (index into options), explanation: string }
 */

import { DIFFICULTIES } from './config.js';

const isNonEmptyString = (value) => typeof value === 'string' && value.trim().length > 0;

/**
 * Checks a single question against the schema.
 * @returns {string[]} human-readable problems (empty when valid)
 */
export function validateQuestion(question, topicIds) {
  const problems = [];
  if (!question || typeof question !== 'object') return ['question is not an object'];

  if (!isNonEmptyString(question.id)) problems.push('missing id');
  if (!topicIds.has(question.topic)) problems.push(`unknown topic "${question.topic}"`);
  if (!DIFFICULTIES.includes(question.difficulty)) problems.push(`invalid difficulty "${question.difficulty}"`);
  if (!isNonEmptyString(question.question)) problems.push('missing question text');
  if (question.code !== undefined && typeof question.code !== 'string') problems.push('code must be a string');
  if (!isNonEmptyString(question.explanation)) problems.push('missing explanation');

  const { options } = question;
  if (!Array.isArray(options) || options.length < 2 || options.length > 6) {
    problems.push('options must be an array of 2-6 strings');
  } else {
    if (!options.every(isNonEmptyString)) problems.push('every option must be a non-empty string');
    if (new Set(options.map((o) => String(o).trim().toLowerCase())).size !== options.length) {
      problems.push('options must be unique');
    }
    if (!Number.isInteger(question.answer) || question.answer < 0 || question.answer >= options.length) {
      problems.push('answer must be a valid option index');
    }
  }
  return problems;
}

/**
 * Validates the whole bank. Invalid or duplicate questions are dropped (and
 * reported) instead of crashing the app, so one bad entry never takes the quiz down.
 * @returns {{ topics: object[], questions: object[], errors: string[] }}
 */
export function validateQuestionBank(topics, questions) {
  const errors = [];
  const validTopics = [];
  const topicIds = new Set();

  for (const topic of topics) {
    if (!isNonEmptyString(topic?.id) || !isNonEmptyString(topic?.name)) {
      errors.push(`Topic ${JSON.stringify(topic)}: needs id and name`);
    } else if (topicIds.has(topic.id)) {
      errors.push(`Topic "${topic.id}": duplicate id`);
    } else {
      topicIds.add(topic.id);
      validTopics.push(Object.freeze({ icon: '', description: '', ...topic }));
    }
  }

  const seenIds = new Set();
  const validQuestions = [];
  questions.forEach((question, position) => {
    const label = question?.id ?? `#${position}`;
    const problems = validateQuestion(question, topicIds);
    if (seenIds.has(question?.id)) problems.push('duplicate id');
    if (problems.length > 0) {
      errors.push(`Question ${label}: ${problems.join('; ')}`);
      return;
    }
    seenIds.add(question.id);
    validQuestions.push(Object.freeze({ ...question, options: Object.freeze(question.options.slice()) }));
  });

  return { topics: validTopics, questions: validQuestions, errors };
}

/**
 * Immutable, indexed view over a validated bank.
 */
export function createQuestionBank(topics, questions) {
  const byId = new Map(questions.map((q) => [q.id, q]));
  const topicsById = new Map(topics.map((t) => [t.id, t]));
  const order = new Map(questions.map((q, index) => [q.id, index]));

  return Object.freeze({
    topics,
    questions,
    getQuestion: (id) => byId.get(id) ?? null,
    getTopic: (id) => topicsById.get(id) ?? null,
    /** Position of a question in the source file; used for stable, un-shuffled ordering. */
    orderOf: (id) => order.get(id) ?? Number.MAX_SAFE_INTEGER,
    filter: (criteria) => filterQuestions(questions, criteria),
    countByTopic: (difficulty = 'mixed') => {
      const counts = Object.fromEntries(topics.map((t) => [t.id, 0]));
      for (const q of questions) {
        if (difficulty === 'mixed' || q.difficulty === difficulty) counts[q.topic] += 1;
      }
      return counts;
    },
  });
}

/**
 * @param {object[]} questions
 * @param {{ topics?: string[], difficulty?: string }} criteria
 *   An empty/missing topic list means "all topics"; difficulty 'mixed' means any.
 */
export function filterQuestions(questions, { topics = [], difficulty = 'mixed' } = {}) {
  const topicSet = new Set(topics);
  return questions.filter(
    (q) => (topicSet.size === 0 || topicSet.has(q.topic)) && (difficulty === 'mixed' || q.difficulty === difficulty),
  );
}

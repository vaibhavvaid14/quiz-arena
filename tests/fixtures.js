import { createQuestionBank } from '../js/core/questionBank.js';

export const FIXTURE_TOPICS = [
  { id: 'alpha', name: 'Alpha', icon: 'A', description: 'First topic' },
  { id: 'beta', name: 'Beta', icon: 'B', description: 'Second topic' },
];

const q = (id, topic, difficulty, answer) => ({
  id,
  topic,
  difficulty,
  question: `Question ${id}?`,
  options: ['zero', 'one', 'two', 'three'],
  answer,
  explanation: `Because ${answer}.`,
});

export const FIXTURE_QUESTIONS = [
  q('a-e1', 'alpha', 'easy', 0),
  q('a-e2', 'alpha', 'easy', 1),
  q('a-m1', 'alpha', 'medium', 2),
  q('a-h1', 'alpha', 'hard', 3),
  q('b-e1', 'beta', 'easy', 1),
  q('b-m1', 'beta', 'medium', 0),
  q('b-h1', 'beta', 'hard', 2),
  q('b-h2', 'beta', 'hard', 3),
];

export const fixtureBank = () => createQuestionBank(FIXTURE_TOPICS, FIXTURE_QUESTIONS);

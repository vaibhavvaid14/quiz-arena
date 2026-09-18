/**
 * Visual preview harness: seeds the throwaway test database through the public
 * API (no back doors, so answers are picked at random), then shows a screen.
 */

import { createApi } from '../js/api.js';
import { createApp } from '../js/app.js';
import { createMemoryBackend, createStorage } from '../js/core/storage.js';

const params = new URLSearchParams(location.search);
const screen = params.get('screen') ?? 'setup';
const storage = createStorage(createMemoryBackend());
if (params.get('theme')) storage.savePrefs({ theme: params.get('theme') });

const api = createApi({ getKey: () => storage.getCurrentPlayer()?.key ?? null });
const app = createApp({ root: document.getElementById('app'), chrome: document.getElementById('site-header'), api, storage });
await app.start();
const { ctx } = app;

const tag = Math.random().toString(36).slice(2, 6);
const allTopics = ctx.catalog.topics.map((t) => t.id);
const base = { topics: allTopics, difficulty: 'mixed', count: 10, timerMode: 'question', secondsPerQuestion: 30, shuffle: true, negativeMarking: false };

/** Plays a whole quiz through the API with random answers. */
async function playQuiz(config) {
  let state = await api.startAttempt(config);
  while (state.status === 'active') {
    const { options } = state.current.question;
    state = await api.answer(state.id, state.current.position, options[Math.floor(Math.random() * options.length)].id);
    state = await api.next(state.id, state.current.position);
  }
  return state;
}

async function asPlayer(name) {
  return ctx.ensurePlayer(`${name}`);
}

if (screen === 'leaderboard') {
  for (const name of ['Ada', 'Grace', 'Linus', 'Margaret', 'Alan']) {
    await asPlayer(`${name} ${tag}`);
    await playQuiz(base);
  }
}

await asPlayer(`Alex ${tag}`);

if (screen === 'setup') {
  await playQuiz(base);
  ctx.navigate('setup');
} else if (screen === 'quiz' || screen === 'feedback') {
  let state = await api.startAttempt(base);
  for (let i = 0; i < 2; i += 1) {
    state = await api.answer(state.id, i, state.current.question.options[0].id);
    state = await api.next(state.id, i);
  }
  if (screen === 'feedback') state = await api.answer(state.id, 2, state.current.question.options[1].id);
  storage.setActiveAttemptId(state.id);
  ctx.navigate('quiz', { state });
} else if (screen === 'results') {
  const state = await playQuiz(base);
  ctx.navigate('results', { attemptId: state.id, fresh: true });
} else if (screen === 'history') {
  await playQuiz({ ...base, topics: ['javascript', 'web'] });
  await playQuiz({ ...base, difficulty: 'hard', timerMode: 'session' });
  await playQuiz({ ...base, topics: ['science', 'geography', 'math'], timerMode: 'off', count: 15 });
  ctx.navigate('history');
} else if (screen === 'leaderboard') {
  await playQuiz(base);
  ctx.navigate('leaderboard');
}
document.body.dataset.ready = 'true';

/**
 * Browser entry point: validates the question bank, wires persistence and
 * mounts the app.
 */

import { createApp } from './app.js';
import { createQuestionBank, validateQuestionBank } from './core/questionBank.js';
import { createStorage, resolveBackend } from './core/storage.js';
import { QUESTIONS, TOPICS } from './data/questions.js';

function boot() {
  const root = document.getElementById('app');
  const chrome = document.getElementById('site-header');

  const { topics, questions, errors } = validateQuestionBank(TOPICS, QUESTIONS);
  if (errors.length > 0) {
    console.warn(`[quiz] ${errors.length} question bank problem(s); affected entries were skipped:\n${errors.join('\n')}`);
  }
  if (questions.length === 0) {
    root.textContent = 'The question bank is empty or invalid. Check js/data/questions.js.';
    return;
  }

  const { backend, persistent } = resolveBackend();
  const storage = createStorage(backend, { onError: (error) => console.warn('[quiz] storage error', error) });
  const app = createApp({ root, chrome, bank: createQuestionBank(topics, questions), storage, persistent });
  app.start();
}

boot();

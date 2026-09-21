/**
 * End-to-end flows: the real app, the real API and a real (throwaway) database.
 * Run the server with `python serve.py --test`, then open /tests/.
 *
 * The browser never knows correct answers in advance, so these tests check
 * consistency instead: whatever the server says per question must add up on
 * the results, history and leaderboard screens.
 */

import { createApi } from '../js/api.js';
import { createApp } from '../js/app.js';
import { createMemoryBackend, createStorage } from '../js/core/storage.js';
import { assert, equal, flush, test } from './harness.js';

const RUN = Date.now().toString(36).slice(-5);
let playerCounter = 0;
const uniqueName = (label) => `${label}-${RUN}-${(playerCounter += 1)}`;

export async function waitFor(predicate, { timeoutMs = 8000, what = 'condition' } = {}) {
  const start = performance.now();
  for (;;) {
    const value = predicate();
    if (value) return value;
    if (performance.now() - start > timeoutMs) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

/**
 * Guards the regression where a null child got appended instead of skipped, printing the
 * literal text "null". Such a child becomes a text node of its own, so this checks nodes
 * rather than the panel's combined text, and skips the two server-supplied spots (the
 * explanation and the revealed option label), which legitimately read "null" or
 * "undefined" for questions like js-m01. Returns the offending text node, or null.
 */
function strayNullNode(el) {
  const SERVER_TEXT = '.feedback-explanation, .feedback-answer strong';
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.parentElement?.closest(SERVER_TEXT)) continue;
    if (['null', 'undefined', 'false'].includes(node.nodeValue.trim())) return node;
  }
  return null;
}

async function mount({ storage = createStorage(createMemoryBackend()) } = {}) {
  const host = document.createElement('div');
  host.className = 'e2e-host';
  const root = document.createElement('main');
  host.append(root);
  document.body.append(host);
  const api = createApi({ getKey: () => storage.getCurrentPlayer()?.key ?? null });
  const app = createApp({ root, api, storage });
  await app.start();
  const $ = (selector) => root.querySelector(selector);
  const $$ = (selector) => [...root.querySelectorAll(selector)];

  const t = {
    root,
    api,
    app,
    storage,
    $,
    $$,
    async startQuiz({ name = uniqueName('e2e'), count = '5', timerMode = 'off' } = {}) {
      await waitFor(() => $('.screen-setup'), { what: 'setup screen' });
      const input = $('#player-name');
      input.value = name;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      $(`input[name="count"][value="${count}"]`).click();
      $(`input[name="timerMode"][value="${timerMode}"]`).click();
      $('[data-action="start"]').click();
      await waitFor(() => $('.screen-quiz .option') || $('.form-error')?.textContent, { what: 'quiz to start' });
      return name;
    },
    /** Answers the current question with option `index`; resolves with the feedback title. */
    async answer(index = 0) {
      await waitFor(() => $$('.option:not(:disabled)').length > 0, { what: 'answerable question' });
      $$('.option')[index].click();
      const title = await waitFor(() => $('.feedback-title')?.textContent, { what: 'feedback' });
      // Regression: a null child once rendered as the literal text "null".
      assert(!strayNullNode($('.feedback')), `stray "null" in feedback: ${$('.feedback').textContent}`);
      return title;
    },
    async next() {
      const before = $('.quiz-progress-text')?.textContent;
      $('[data-action="next"]').click();
      await waitFor(() => $('.screen-results .score-ring') || ($('.quiz-progress-text') && $('.quiz-progress-text').textContent !== before && !$('.feedback-title')), {
        what: 'next question or results',
      });
    },
    teardown() {
      app.destroy();
      host.remove();
      document.querySelectorAll('.toast-region, dialog').forEach((el) => el.remove());
    },
  };
  return t;
}

test('e2e: play a full quiz; results, history and leaderboard agree with the feedback', async () => {
  const t = await mount();
  try {
    const name = await t.startQuiz();
    let correct = 0;
    for (let i = 0; i < 5; i += 1) {
      equal(t.$('.quiz-progress-text').textContent, `Question ${i + 1} of 5`);
      const title = await t.answer(i % 4);
      if (title.includes('Correct!')) correct += 1;
      assert(t.$('.option.is-correct'), 'server revealed the correct option');
      equal(t.$$('.option:disabled').length, 4, 'options locked after answering');
      assert(t.$('.feedback-explanation').textContent.length > 0, 'explanation shown');
      await t.next();
    }

    await waitFor(() => t.$('.score-ring-value'), { what: 'results' });
    equal(t.$('.score-ring-value').textContent, `${Math.round((correct / 5) * 100)}%`);
    equal(t.$$('.review-card').length, 5);
    equal(t.storage.getActiveAttemptId(), null, 'active attempt cleared');

    t.app.ctx.navigate('history');
    await waitFor(() => t.$('.data-table tbody tr'), { what: 'history table' });
    equal(t.$$('.data-table tbody tr').length, 1);
    assert(t.$('h1').textContent.includes(name));

    t.app.ctx.navigate('leaderboard');
    await waitFor(() => t.$('.leaderboard-table') || t.$('.empty-state'), { what: 'leaderboard' });
    // Either highlighted in the top list, or told their rank below it -- never both, never neither.
    const mine = t.$$('.leaderboard-table tr.is-me');
    const rankNote = t.$('.your-rank')?.textContent ?? '';
    equal(mine.length + (rankNote.includes(name) ? 1 : 0), 1, 'player placed exactly once');
    if (mine.length) assert(mine[0].textContent.includes(name));
  } finally {
    t.teardown();
  }
});

test('e2e: retry missed starts a quiz with exactly the missed questions', async () => {
  const t = await mount();
  try {
    await t.startQuiz();
    for (let i = 0; i < 5; i += 1) {
      await t.answer(0);
      await t.next();
    }
    await waitFor(() => t.$('.results-actions'), { what: 'results' });
    const retry = t.$('[data-action="retry-missed"]');
    if (!retry) return; // all five happened to be correct: nothing to retry
    const missed = Number(retry.textContent.match(/\d+/)[0]);
    retry.click();
    await waitFor(() => t.$('.screen-quiz .option'), { what: 'retry quiz' });
    equal(t.$('.quiz-progress-text').textContent, `Question 1 of ${missed}`);
  } finally {
    t.teardown();
  }
});

test('e2e: leaving mid-quiz and resuming restores the exact state', async () => {
  const t = await mount();
  try {
    await t.startQuiz();
    await t.answer(1);
    t.app.ctx.navigate('setup');
    const resume = await waitFor(() => t.$('[data-action="resume"]'), { what: 'resume banner' });
    resume.click();
    await waitFor(() => t.$('.screen-quiz .feedback-title'), { what: 'restored feedback' });
    equal(t.$$('.option:disabled').length, 4, 'answered question is still locked');
    await t.next();
    equal(t.$('.quiz-progress-text').textContent, 'Question 2 of 5');
  } finally {
    t.teardown();
  }
});

test('e2e: ending early marks the rest as skipped', async () => {
  const t = await mount();
  try {
    await t.startQuiz();
    await t.answer(0);
    await t.next();
    t.$$('.quiz-actions .btn').find((b) => b.textContent === 'End quiz').click();
    const confirm = await waitFor(() => document.querySelector('dialog [data-action="confirm"]'), { what: 'confirm dialog' });
    confirm.click();
    await waitFor(() => t.$('.screen-results .notice'), { what: 'results notice' });
    assert(t.$('.notice').textContent.includes('ended this quiz early'));
    const skipped = t.$$('.stat-tile').find((tile) => tile.textContent.startsWith('Skipped'));
    assert(skipped.textContent.includes('4'), `skipped tile: ${skipped.textContent}`);
  } finally {
    t.teardown();
  }
});

test('e2e: keyboard shortcuts answer and advance', async () => {
  const t = await mount();
  try {
    await t.startQuiz();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: '2', bubbles: true }));
    await waitFor(() => t.$('.feedback-title'), { what: 'feedback after key 2' });
    equal(t.$('.option.is-selected').dataset.index, '1');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await waitFor(() => t.$('.quiz-progress-text').textContent === 'Question 2 of 5', { what: 'question 2' });
  } finally {
    t.teardown();
  }
});

test('e2e: a name claimed by another browser is refused', async () => {
  const first = await mount();
  const name = uniqueName('owner');
  try {
    await first.startQuiz({ name });
  } finally {
    first.teardown();
  }
  const second = await mount(); // fresh storage = a different browser
  try {
    await second.startQuiz({ name: name.toUpperCase() });
    const error = second.$('.form-error').textContent;
    assert(error.includes('already taken'), error);
    assert(second.$('.screen-setup'), 'still on setup');
  } finally {
    second.teardown();
  }
});

test('e2e: a per-question countdown that runs out reveals the answer', async () => {
  const t = await mount();
  try {
    await waitFor(() => t.$('.screen-setup'));
    await t.app.ctx.ensurePlayer(uniqueName('timer'));
    // 5 s is the server minimum; the headless runner fast-forwards virtual time.
    await t.app.ctx.startQuiz({ topics: ['math'], difficulty: 'easy', count: 2, timerMode: 'question', secondsPerQuestion: 5, shuffle: true, negativeMarking: false });
    assert(t.$('.timer-ring'), 'timer ring rendered');
    await waitFor(() => t.$('.feedback.tone-warning'), { timeoutMs: 15000, what: 'time-out feedback' });
    assert(t.$('.feedback-title').textContent.includes("Time's up"));
    assert(t.$('.option.is-correct'), 'correct answer revealed');
  } finally {
    t.teardown();
  }
});

test('e2e: a server that cannot be reached shows a retry screen', async () => {
  const host = document.createElement('div');
  host.className = 'e2e-host';
  const root = document.createElement('main');
  host.append(root);
  document.body.append(host);
  const api = createApi({ baseUrl: 'http://127.0.0.1:9' }); // nothing listens on the discard port
  const app = createApp({ root, api, storage: createStorage(createMemoryBackend()) });
  try {
    await app.start();
    assert(root.textContent.includes("Can't reach the quiz server"));
    assert([...root.querySelectorAll('button')].some((b) => b.textContent === 'Try again'));
  } finally {
    app.destroy();
    host.remove();
    await flush();
  }
});

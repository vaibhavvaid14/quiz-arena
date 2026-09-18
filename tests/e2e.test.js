/**
 * End-to-end flows: mounts the real app (real screens, real timers) against an
 * in-memory store and a seeded RNG, then drives it through the DOM.
 */

import { createApp } from '../js/app.js';
import { createSeededRng } from '../js/core/random.js';
import { createMemoryBackend, createStorage } from '../js/core/storage.js';
import { fixtureBank } from './fixtures.js';
import { assert, equal, flush, test } from './harness.js';

function mount() {
  const host = document.createElement('div');
  host.className = 'e2e-host';
  const root = document.createElement('main');
  host.append(root);
  document.body.append(host);
  const bank = fixtureBank();
  const storage = createStorage(createMemoryBackend());
  const app = createApp({ root, bank, storage, rng: createSeededRng(42) });
  app.start();
  const $ = (selector) => root.querySelector(selector);
  const $$ = (selector) => [...root.querySelectorAll(selector)];
  return {
    root,
    bank,
    storage,
    app,
    $,
    $$,
    currentAnswer() {
      const session = storage.getActiveSession();
      return bank.getQuestion(session.items[session.currentIndex].questionId).answer;
    },
    clickOption(correct) {
      const answer = this.currentAnswer();
      const button = $$('.option').find((b) => (Number(b.dataset.original) === answer) === correct);
      button.click();
    },
    async startQuiz({ count = '5', timerMode = 'off' } = {}) {
      $(`input[name="count"][value="${count}"]`).click();
      $(`input[name="timerMode"][value="${timerMode}"]`).click();
      $('[data-action="start"]').click();
      await flush();
    },
    teardown() {
      app.destroy();
      host.remove();
      document.querySelectorAll('.toast-region, dialog').forEach((el) => el.remove());
    },
  };
}

const waitFor = async (predicate, timeoutMs = 9000) => {
  const start = performance.now();
  while (!predicate()) {
    if (performance.now() - start > timeoutMs) throw new Error('waitFor timed out');
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
};

test('e2e: setup -> answer every question -> results -> history', async () => {
  const t = mount();
  try {
    assert(t.$('.screen-setup'), 'starts on setup');
    equal(t.$$('.topic-input:checked').length, 2, 'all topics preselected');
    await t.startQuiz();
    assert(t.$('.screen-quiz'), 'quiz screen shown');

    for (let i = 0; i < 5; i += 1) {
      equal(t.$('.quiz-progress-text').textContent, `Question ${i + 1} of 5`);
      t.clickOption(i < 3);
      assert(!t.$('[data-action="next"]').hidden, 'next revealed after answering');
      assert(t.$('.option.is-correct'), 'correct option highlighted');
      if (i >= 3) assert(t.$('.option.is-wrong'), 'wrong choice highlighted');
      assert(t.$('.feedback-explanation').textContent.length > 0, 'explanation shown');
      equal(t.$$('.option:disabled').length, 4, 'options locked');
      t.$('[data-action="next"]').click();
    }

    assert(t.$('.screen-results'), 'results shown');
    equal(t.$('.score-ring-value').textContent, '60%');
    equal(t.$$('.review-card').length, 5);
    equal(t.storage.getActiveSession(), null, 'active session cleared');
    equal(t.storage.getHistory().length, 1, 'attempt saved to history');
    equal(t.storage.getHistory()[0].summary.correct, 3);

    t.$('input[name="review-filter"][value="missed"]').click();
    equal(t.$$('.review-card').filter((c) => !c.hidden).length, 2, 'missed filter');

    t.$('[data-action="retry-missed"]').click();
    await flush();
    assert(t.$('.screen-quiz'), 'retry quiz started');
    equal(t.$('.quiz-progress-text').textContent, 'Question 1 of 2');
  } finally {
    t.teardown();
  }
});

test('e2e: leaving mid-quiz saves progress and resume restores it', async () => {
  const t = mount();
  try {
    await t.startQuiz();
    t.clickOption(true);
    t.app.ctx.navigate('setup');
    const resume = t.$('[data-action="resume"]');
    assert(resume, 'resume banner offered');
    resume.click();
    assert(t.$('.screen-quiz'));
    assert(!t.$('[data-action="next"]').hidden, 'answered question restored with feedback');
    equal(t.$$('.option:disabled').length, 4);
    t.$('[data-action="next"]').click();
    equal(t.$('.quiz-progress-text').textContent, 'Question 2 of 5');
  } finally {
    t.teardown();
  }
});

test('e2e: ending early marks the rest as skipped', async () => {
  const t = mount();
  try {
    await t.startQuiz();
    t.clickOption(true);
    t.$('[data-action="next"]').click();
    [...t.$$('.quiz-actions .btn')].find((b) => b.textContent === 'End quiz').click();
    const confirm = document.querySelector('dialog [data-action="confirm"]');
    assert(confirm, 'confirmation dialog shown');
    confirm.click();
    await flush();
    assert(t.$('.screen-results'));
    assert(t.$('.notice').textContent.includes('ended this quiz early'));
    equal(t.storage.getHistory()[0].summary.skipped, 4);
  } finally {
    t.teardown();
  }
});

test('e2e: keyboard shortcuts answer and advance', async () => {
  const t = mount();
  try {
    await t.startQuiz();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: '2', bubbles: true }));
    assert(t.$('.option.is-selected'), 'key 2 selected an option');
    equal(t.$('.option.is-selected').dataset.display, '1');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    equal(t.$('.quiz-progress-text').textContent, 'Question 2 of 5');
  } finally {
    t.teardown();
  }
});

test('e2e: resuming a whole-quiz countdown that already ran out auto-submits', async () => {
  const t = mount();
  try {
    t.app.ctx.startQuiz({ topics: ['alpha'], count: 2, timerMode: 'session', secondsPerQuestion: 5 });
    const saved = t.storage.getActiveSession();
    t.app.ctx.navigate('setup');
    // Simulate a tab closed right as the clock hit zero.
    t.storage.saveActiveSession({ ...saved, clock: { questionElapsedMs: 10000, sessionElapsedMs: 10000 } });
    t.app.ctx.navigate('quiz', { session: t.storage.getActiveSession() });
    await flush();
    assert(t.$('.screen-results'), 'submitted straight to results');
    assert(t.$('.notice').textContent.includes('Time ran out'));
    equal(t.storage.getActiveSession(), null);
    equal(t.storage.getHistory()[0].summary.timedOut, 1);
  } finally {
    t.teardown();
  }
});

test('e2e: per-question countdown expires into a timed-out answer', async () => {
  const t = mount();
  try {
    // 5 s is the minimum the engine allows; this test waits for it in real time.
    t.app.ctx.startQuiz({ topics: ['alpha', 'beta'], count: 5, timerMode: 'question', secondsPerQuestion: 5 });
    assert(t.$('.timer-ring'), 'timer ring rendered');
    await waitFor(() => t.$('.feedback.tone-warning'));
    assert(t.$('.feedback-title').textContent.includes("Time's up"));
    equal(t.storage.getActiveSession().items[0].status, 'timeout');
    assert(t.$('.option.is-correct'), 'correct answer revealed');
  } finally {
    t.teardown();
  }
});

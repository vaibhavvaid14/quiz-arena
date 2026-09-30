/**
 * End-to-end flows against the real application, the real API and a real
 * (throwaway) database. Run the server in test mode, then open /tests/.
 *
 * These drive the app the way a person does: they load the actual page in an
 * iframe and click things. Nothing is imported from the application, so the
 * suite does not care what the UI is written in — it checked the JavaScript
 * frontend and now checks the Kotlin/JS one without changing a single
 * assertion.
 *
 * The browser never knows correct answers in advance, so these tests check
 * consistency instead: whatever the server says per question must add up on the
 * results, history and leaderboard screens.
 */

import { assert, equal, test } from './harness.js';

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
  const walker = el.ownerDocument.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.parentElement?.closest(SERVER_TEXT)) continue;
    if (['null', 'undefined', 'false'].includes(node.nodeValue.trim())) return node;
  }
  return null;
}

/**
 * Loads the app in an iframe and returns helpers bound to it. Device storage is
 * cleared first: the iframe shares this page's origin, so a previous test's
 * player would otherwise still be signed in.
 */
async function mount() {
  localStorage.clear();

  const frame = document.createElement('iframe');
  frame.className = 'e2e-frame';
  frame.setAttribute('title', 'Application under test');
  // Off-screen rather than hidden: a display:none frame does not lay out, and
  // the app's focus handling expects a real viewport.
  frame.style.cssText = 'position:fixed;left:-10000px;top:0;width:1200px;height:900px;border:0';
  document.body.append(frame);

  await new Promise((resolve, reject) => {
    frame.addEventListener('load', resolve, { once: true });
    frame.addEventListener('error', reject, { once: true });
    frame.src = '/';
  });

  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (selector) => doc.querySelector(selector);
  const $$ = (selector) => [...doc.querySelectorAll(selector)];

  await waitFor(() => $('.screen-setup'), { what: 'the app to start' });

  const t = {
    frame,
    win,
    doc,
    $,
    $$,

    /** Clicks a link in the site header, the way a person changes screens. */
    async nav(section, settled) {
      $(`.site-nav [data-nav="${section}"]`).click();
      await waitFor(settled, { what: `the ${section} screen` });
    },

    /** The id of the unfinished quiz this device remembers, or null. */
    activeAttemptId() {
      const raw = localStorage.getItem('quizapp:activeAttempt');
      if (!raw) return null;
      try {
        return JSON.parse(raw).data ?? null;
      } catch {
        return null;
      }
    },

    async startQuiz({ name = uniqueName('e2e'), count = '5', timerMode = 'off', topics = null, seconds = null } = {}) {
      const input = $('#player-name');
      input.value = name;
      input.dispatchEvent(new win.Event('input', { bubbles: true }));

      if (topics) {
        $$('.topic-input').forEach((box) => {
          const want = topics.includes(box.value);
          if (box.checked !== want) box.click();
        });
      }
      $(`input[name="count"][value="${count}"]`).click();
      $(`input[name="timerMode"][value="${timerMode}"]`).click();
      if (seconds) {
        const select = $('#seconds-per-question');
        select.value = String(seconds);
        select.dispatchEvent(new win.Event('change', { bubbles: true }));
      }

      $('[data-action="start"]').click();
      await waitFor(() => $('.screen-quiz .option') || $('.form-error')?.textContent, { what: 'the quiz to start' });
      return name;
    },

    /** Answers the current question with option `index`; resolves with the feedback title. */
    async answer(index = 0) {
      await waitFor(() => $$('.option:not(:disabled)').length > 0, { what: 'an answerable question' });
      $$('.option')[index].click();
      const title = await waitFor(() => $('.feedback-title')?.textContent, { what: 'feedback' });
      // Regression: a null child once rendered as the literal text "null".
      assert(!strayNullNode($('.feedback')), `stray "null" in feedback: ${$('.feedback').textContent}`);
      return title;
    },

    async next() {
      const before = $('.quiz-progress-text')?.textContent;
      $('[data-action="next"]').click();
      await waitFor(
        () =>
          $('.screen-results .score-ring') ||
          ($('.quiz-progress-text') && $('.quiz-progress-text').textContent !== before && !$('.feedback-title')),
        { what: 'the next question or the results' },
      );
    },

    /** Dispatches a key on the application's own document. */
    key(key) {
      doc.dispatchEvent(new win.KeyboardEvent('keydown', { key, bubbles: true }));
    },

    teardown() {
      frame.remove();
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
      assert(t.$('.option.is-correct'), 'the server revealed the correct option');
      equal(t.$$('.option:disabled').length, 4, 'options locked after answering');
      assert(t.$('.feedback-explanation').textContent.length > 0, 'explanation shown');
      await t.next();
    }

    await waitFor(() => t.$('.score-ring-value'), { what: 'the results' });
    equal(t.$('.score-ring-value').textContent, `${Math.round((correct / 5) * 100)}%`);
    equal(t.$$('.review-card').length, 5);
    equal(t.activeAttemptId(), null, 'active attempt cleared');

    await t.nav('history', () => t.$('.data-table tbody tr'));
    equal(t.$$('.data-table tbody tr').length, 1);
    assert(t.$('h1').textContent.includes(name));

    await t.nav('leaderboard', () => t.$('.leaderboard-table') || t.$('.empty-state'));
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
    await waitFor(() => t.$('.results-actions'), { what: 'the results' });
    const retry = t.$('[data-action="retry-missed"]');
    if (!retry) return; // all five happened to be correct: nothing to retry
    const missed = Number(retry.textContent.match(/\d+/)[0]);
    retry.click();
    await waitFor(() => t.$('.screen-quiz .option'), { what: 'the retry quiz' });
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
    await t.nav('setup', () => t.$('[data-action="resume"]'));
    t.$('[data-action="resume"]').click();
    await waitFor(() => t.$('.screen-quiz .feedback-title'), { what: 'the restored feedback' });
    equal(t.$$('.option:disabled').length, 4, 'the answered question is still locked');
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
    const confirm = await waitFor(() => t.doc.querySelector('dialog [data-action="confirm"]'), {
      what: 'the confirm dialog',
    });
    confirm.click();
    await waitFor(() => t.$('.screen-results .notice'), { what: 'the results notice' });
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
    t.key('2');
    await waitFor(() => t.$('.feedback-title'), { what: 'feedback after pressing 2' });
    equal(t.$('.option.is-selected').dataset.index, '1');
    t.key('ArrowRight');
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
  const second = await mount(); // mount() clears device storage: a different browser
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
    // 10 s is the shortest the setup screen offers; the headless runner
    // fast-forwards virtual time, so this does not really wait.
    await t.startQuiz({ name: uniqueName('timer'), count: '5', timerMode: 'question', seconds: 10 });
    assert(t.$('.timer-ring'), 'timer ring rendered');
    await waitFor(() => t.$('.feedback.tone-warning'), { timeoutMs: 20000, what: 'the time-out feedback' });
    assert(t.$('.feedback-title').textContent.includes("Time's up"));
    assert(t.$('.option.is-correct'), 'the correct answer is revealed');
  } finally {
    t.teardown();
  }
});

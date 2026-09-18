/**
 * Application controller: owns navigation between screens and the cross-screen
 * workflows (start quiz, complete quiz). Dependencies are injected so the whole
 * app can be mounted in tests with an in-memory store and a seeded RNG.
 */

import { createSession, finishSession, summarizeSession } from './core/quizEngine.js';
import { buildHistoryEntry } from './core/stats.js';
import { focusElement, h } from './ui/dom.js';
import { renderHistoryScreen } from './ui/screens/historyScreen.js';
import { renderQuizScreen } from './ui/screens/quizScreen.js';
import { renderResultsScreen } from './ui/screens/resultsScreen.js';
import { renderSetupScreen } from './ui/screens/setupScreen.js';

const SCREENS = {
  setup: renderSetupScreen,
  quiz: renderQuizScreen,
  results: renderResultsScreen,
  history: renderHistoryScreen,
};

/**
 * @param {object} deps
 * @param {HTMLElement} deps.root            where screens render
 * @param {ReturnType<import('./core/questionBank.js').createQuestionBank>} deps.bank
 * @param {ReturnType<import('./core/storage.js').createStorage>} deps.storage
 * @param {boolean} [deps.persistent=true]  false when storage is memory-only
 * @param {() => number} [deps.rng]
 * @param {() => number} [deps.now]
 * @param {HTMLElement|null} [deps.chrome]   header containing [data-nav] and [data-theme-toggle]
 */
export function createApp({ root, bank, storage, persistent = true, rng = Math.random, now = () => Date.now(), chrome = null }) {
  let cleanup = null;
  let currentScreen = null;

  const liveRegion = h('div', { class: 'visually-hidden', attrs: { 'aria-live': 'polite', 'aria-atomic': 'true' } });
  const toastRegion = h('div', { class: 'toast-region' });
  root.after(liveRegion, toastRegion);

  const ctx = {
    bank,
    storage,
    persistent,
    rng,
    now,

    navigate(screen, params = {}) {
      const render = SCREENS[screen];
      if (!render) throw new Error(`Unknown screen "${screen}"`);
      cleanup?.();
      cleanup = null;
      currentScreen = screen;
      root.replaceChildren();
      root.dataset.screen = screen;
      cleanup = render(root, ctx, params) ?? null;
      updateNav(screen);
      window.scrollTo?.({ top: 0 });
      focusElement(root.querySelector('[data-autofocus]') ?? root.querySelector('h1'));
    },

    get currentScreen() {
      return currentScreen;
    },

    /** Creates a session, persists it as the active quiz and opens the quiz screen. */
    startQuiz(config, { questionIds } = {}) {
      const session = createSession(bank, config, { rng, now: now(), questionIds });
      // A "retry incorrect" run is a one-off; it must not overwrite the saved setup.
      if (!questionIds) storage.updatePrefs({ lastConfig: config });
      storage.saveActiveSession(session);
      ctx.navigate('quiz', { session });
      return session;
    },

    /** Finalises a session (if needed), records it in history and shows the results. */
    completeQuiz(session, reason) {
      const finished = finishSession(session, { now: now(), reason });
      const summary = summarizeSession(finished, bank);
      storage.addHistoryEntry(buildHistoryEntry(finished, summary));
      ctx.navigate('results', { session: finished, fresh: true });
      // Cleared after navigating: the quiz screen's cleanup saves progress on
      // exit, and must not resurrect a quiz that has just been completed.
      storage.clearActiveSession();
    },

    announce(message) {
      liveRegion.textContent = '';
      // A fresh text node on the next frame makes screen readers re-announce repeats.
      requestAnimationFrame(() => {
        liveRegion.textContent = message;
      });
    },

    toast(message, { tone = 'info', durationMs = 3500 } = {}) {
      const toast = h('div', { class: ['toast', `toast-${tone}`], attrs: { role: 'status' } }, message);
      toastRegion.append(toast);
      setTimeout(() => toast.classList.add('toast-leaving'), durationMs);
      setTimeout(() => toast.remove(), durationMs + 400);
    },
  };

  function updateNav(screen) {
    if (!chrome) return;
    const section = screen === 'history' ? 'history' : 'setup';
    for (const link of chrome.querySelectorAll('[data-nav]')) {
      if (link.dataset.nav === section) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
  }

  function applyTheme(theme) {
    if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
    else delete document.documentElement.dataset.theme;
    const toggle = chrome?.querySelector('[data-theme-toggle]');
    if (toggle) {
      const dark = effectiveTheme() === 'dark';
      toggle.setAttribute('aria-pressed', String(dark));
      toggle.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
      toggle.querySelector('[data-theme-icon]').textContent = dark ? '☀️' : '🌙';
    }
  }

  function effectiveTheme() {
    const explicit = document.documentElement.dataset.theme;
    if (explicit) return explicit;
    return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  if (chrome) {
    for (const link of chrome.querySelectorAll('[data-nav]')) {
      link.addEventListener('click', (event) => {
        event.preventDefault();
        const leavingQuiz = currentScreen === 'quiz';
        ctx.navigate(link.dataset.nav);
        if (leavingQuiz) ctx.toast('Quiz paused — your progress is saved. Resume it any time.');
      });
    }
    chrome.querySelector('[data-theme-toggle]')?.addEventListener('click', () => {
      const next = effectiveTheme() === 'dark' ? 'light' : 'dark';
      storage.updatePrefs({ theme: next });
      applyTheme(next);
    });
  }

  return {
    ctx,
    start() {
      applyTheme(storage.getPrefs().theme);
      ctx.navigate('setup');
      if (!persistent) {
        ctx.toast('Browser storage is unavailable, so history will not be saved after you close this tab.', {
          tone: 'warning',
          durationMs: 7000,
        });
      }
    },
    destroy() {
      cleanup?.();
      cleanup = null;
    },
  };
}

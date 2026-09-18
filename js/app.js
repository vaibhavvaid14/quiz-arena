/**
 * Application controller: owns navigation between screens and the workflows
 * that span screens (identify the player, start a quiz, finish a quiz).
 * The API client and storage are injected, so tests can mount the whole app.
 */

import { focusElement, h } from './ui/dom.js';
import { renderHistoryScreen } from './ui/screens/historyScreen.js';
import { renderLeaderboardScreen } from './ui/screens/leaderboardScreen.js';
import { renderQuizScreen } from './ui/screens/quizScreen.js';
import { renderResultsScreen } from './ui/screens/resultsScreen.js';
import { renderSetupScreen } from './ui/screens/setupScreen.js';

const SCREENS = {
  setup: renderSetupScreen,
  quiz: renderQuizScreen,
  results: renderResultsScreen,
  history: renderHistoryScreen,
  leaderboard: renderLeaderboardScreen,
};

const NAV_SECTION = { history: 'history', leaderboard: 'leaderboard' };

/**
 * @param {object} deps
 * @param {HTMLElement} deps.root                 where screens render
 * @param {ReturnType<import('./api.js').createApi>} deps.api
 * @param {ReturnType<import('./core/storage.js').createStorage>} deps.storage
 * @param {boolean} [deps.persistent=true]       false when storage is memory-only
 * @param {HTMLElement|null} [deps.chrome]        header with [data-nav] links and [data-theme-toggle]
 */
export function createApp({ root, api, storage, persistent = true, chrome = null }) {
  let cleanup = null;
  let currentScreen = null;

  const liveRegion = h('div', { class: 'visually-hidden', attrs: { 'aria-live': 'polite', 'aria-atomic': 'true' } });
  const toastRegion = h('div', { class: 'toast-region' });
  root.after(liveRegion, toastRegion);

  const ctx = {
    api,
    storage,
    persistent,
    /** { topics, rules } from the server; loaded once in start(). */
    catalog: null,

    getTopic(id) {
      return ctx.catalog?.topics.find((t) => t.id === id) ?? null;
    },

    get player() {
      return storage.getCurrentPlayer();
    },

    get currentScreen() {
      return currentScreen;
    },

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

    /**
     * Makes `name` the current player: reuses a name this device already owns,
     * otherwise claims it on the server. Throws ApiError 409 if someone else has it.
     */
    async ensurePlayer(name) {
      const known = storage.findKnownPlayer(name);
      if (known) {
        storage.setCurrentPlayer(known.name);
        return known;
      }
      const created = await api.createPlayer(name);
      storage.rememberPlayer(created);
      return created;
    },

    /** Starts a quiz on the server and opens it. */
    async startQuiz(config, { questionIds } = {}) {
      const { isRetry, ...clean } = config;
      const state = await api.startAttempt(clean, questionIds);
      // A "retry missed" run is a one-off; it must not overwrite the saved setup.
      if (!questionIds) storage.updatePrefs({ lastConfig: clean });
      storage.setActiveAttemptId(state.id);
      ctx.navigate('quiz', { state });
      return state;
    },

    /** Called by the quiz screen once the server reports the attempt finished. */
    completeQuiz(state) {
      storage.clearActiveAttemptId();
      ctx.navigate('results', { attemptId: state.id, fresh: true });
    },

    /** Central handling for API failures that reach the UI. */
    handleError(error) {
      if (error?.status === 401) {
        const player = storage.getCurrentPlayer();
        if (player) storage.forgetPlayer(player.name);
        ctx.toast('Your player session expired. Enter your name again to continue.', { tone: 'warning' });
        ctx.navigate('setup');
        return;
      }
      ctx.toast(error?.message ?? 'Something went wrong.', { tone: 'warning' });
      if (!(error?.name === 'ApiError')) console.error(error);
    },

    announce(message) {
      liveRegion.textContent = '';
      // Setting the text on the next frame makes screen readers re-announce repeats.
      requestAnimationFrame(() => {
        liveRegion.textContent = message;
      });
    },

    toast(message, { tone = 'info', durationMs = 4000 } = {}) {
      const toast = h('div', { class: ['toast', `toast-${tone}`], attrs: { role: 'status' } }, message);
      toastRegion.append(toast);
      setTimeout(() => toast.classList.add('toast-leaving'), durationMs);
      setTimeout(() => toast.remove(), durationMs + 400);
    },
  };

  function updateNav(screen) {
    if (!chrome) return;
    const section = NAV_SECTION[screen] ?? 'setup';
    for (const link of chrome.querySelectorAll('[data-nav]')) {
      if (link.dataset.nav === section && !link.classList.contains('brand')) link.setAttribute('aria-current', 'page');
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
        if (!ctx.catalog) return; // still loading / server unreachable
        const leavingQuiz = currentScreen === 'quiz';
        ctx.navigate(link.dataset.nav);
        if (leavingQuiz) ctx.toast('Quiz saved. Resume it from New quiz — timed quizzes keep counting down while you are away.');
      });
    }
    chrome.querySelector('[data-theme-toggle]')?.addEventListener('click', () => {
      const next = effectiveTheme() === 'dark' ? 'light' : 'dark';
      storage.updatePrefs({ theme: next });
      applyTheme(next);
    });
  }

  function renderStatus({ title, message, retry }) {
    cleanup?.();
    cleanup = null;
    currentScreen = null;
    root.replaceChildren(
      h(
        'section',
        { class: 'screen screen-status' },
        h('div', { class: 'empty-state' }, h('h1', {}, title), h('p', {}, message), retry ?? null),
      ),
    );
  }

  async function load() {
    renderStatus({ title: 'Loading…', message: 'Fetching topics from the quiz server.' });
    try {
      ctx.catalog = await api.catalog();
    } catch (error) {
      renderStatus({
        title: "Can't reach the quiz server",
        message: `${error.message} Start it with "python serve.py" in the project folder.`,
        retry: h('button', { type: 'button', class: 'btn btn-primary', onClick: () => load() }, 'Try again'),
      });
      return;
    }
    // A stored key can go stale if the database was reset; forget it quietly.
    const player = storage.getCurrentPlayer();
    if (player) {
      try {
        await api.me();
      } catch (error) {
        if (error.status === 401) storage.forgetPlayer(player.name);
      }
    }
    ctx.navigate('setup');
  }

  return {
    ctx,
    async start() {
      applyTheme(storage.getPrefs().theme);
      if (!persistent) {
        ctx.toast('Browser storage is unavailable, so this device will forget your player name when the tab closes.', {
          tone: 'warning',
          durationMs: 7000,
        });
      }
      await load();
    },
    destroy() {
      cleanup?.();
      cleanup = null;
    },
  };
}

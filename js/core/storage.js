/**
 * Persistence layer (repository pattern over a key/value backend).
 *
 * Keys are namespaced and every value is wrapped in a versioned envelope
 * `{ v: <schema version>, data: <payload> }`. Corrupt or out-of-date entries are
 * discarded instead of crashing the app. The backend is injectable, so tests
 * (and browsers with storage disabled) run on an in-memory map.
 *
 * Keys
 *   quizapp:prefs          last used config + theme
 *   quizapp:activeSession  the in-progress quiz (resume after reload)
 *   quizapp:history        finished attempts, newest first (capped)
 */

import { HISTORY_LIMIT, STORAGE_NAMESPACE, STORAGE_SCHEMA_VERSION } from './config.js';

export function createMemoryBackend(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => void map.set(key, String(value)),
    removeItem: (key) => void map.delete(key),
    dump: () => Object.fromEntries(map),
  };
}

/**
 * Returns window.localStorage when it is actually usable (it can throw in
 * private mode, sandboxed iframes or when disabled), otherwise a memory backend.
 */
export function resolveBackend() {
  try {
    const storage = globalThis.localStorage;
    const probe = `${STORAGE_NAMESPACE}:probe`;
    storage.setItem(probe, '1');
    storage.removeItem(probe);
    return { backend: storage, persistent: true };
  } catch {
    return { backend: createMemoryBackend(), persistent: false };
  }
}

export function createStorage(backend = createMemoryBackend(), { namespace = STORAGE_NAMESPACE, onError = () => {} } = {}) {
  const key = (name) => `${namespace}:${name}`;

  function read(name, fallback, isValid = () => true) {
    try {
      const raw = backend.getItem(key(name));
      if (raw === null || raw === undefined) return fallback;
      const envelope = JSON.parse(raw);
      if (envelope?.v !== STORAGE_SCHEMA_VERSION || !isValid(envelope.data)) {
        backend.removeItem(key(name));
        return fallback;
      }
      return envelope.data;
    } catch (error) {
      onError(error);
      try {
        backend.removeItem(key(name));
      } catch {
        /* backend unusable; nothing more to do */
      }
      return fallback;
    }
  }

  function write(name, data) {
    try {
      backend.setItem(key(name), JSON.stringify({ v: STORAGE_SCHEMA_VERSION, data }));
      return true;
    } catch (error) {
      // Quota exceeded or storage disabled: the app keeps working in memory.
      onError(error);
      return false;
    }
  }

  function remove(name) {
    try {
      backend.removeItem(key(name));
    } catch (error) {
      onError(error);
    }
  }

  const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
  const isSession = (s) => isObject(s) && s.status === 'active' && Array.isArray(s.items) && s.items.length > 0 && isObject(s.config);
  const isHistoryEntry = (e) => isObject(e) && typeof e.id === 'string' && isObject(e.summary) && Array.isArray(e.items);

  return {
    getPrefs: () => read('prefs', {}, isObject),
    savePrefs: (prefs) => write('prefs', prefs),
    updatePrefs(patch) {
      return write('prefs', { ...read('prefs', {}, isObject), ...patch });
    },

    getActiveSession: () => read('activeSession', null, isSession),
    saveActiveSession: (session) => write('activeSession', session),
    clearActiveSession: () => remove('activeSession'),

    getHistory: () => read('history', [], Array.isArray).filter(isHistoryEntry),
    getHistoryEntry(id) {
      return this.getHistory().find((entry) => entry.id === id) ?? null;
    },
    /** Adds (or replaces, by id) an attempt and keeps only the newest HISTORY_LIMIT. */
    addHistoryEntry(entry) {
      const history = this.getHistory().filter((existing) => existing.id !== entry.id);
      history.unshift(entry);
      write('history', history.slice(0, HISTORY_LIMIT));
      return entry;
    },
    clearHistory: () => remove('history'),
  };
}

/**
 * Browser-side persistence for things that belong to this device only.
 * Quiz content, attempts, scores and history live in the server's database.
 *
 * Keys are namespaced and every value is wrapped in a versioned envelope
 * `{ v: <schema version>, data: <payload> }`. Corrupt or out-of-date entries are
 * discarded instead of crashing the app. The backend is injectable, so tests
 * (and browsers with storage disabled) run on an in-memory map.
 *
 * Keys
 *   quizapp:prefs          last used quiz setup + theme
 *   quizapp:players        names claimed on this device -> secret player keys
 *   quizapp:activeAttempt  id of the unfinished quiz, to offer "resume"
 */

import { STORAGE_NAMESPACE, STORAGE_SCHEMA_VERSION } from './config.js';

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
  const isPlayers = (v) =>
    isObject(v) && Array.isArray(v.known) && v.known.every((p) => typeof p?.name === 'string' && typeof p?.key === 'string');
  const readPlayers = () => read('players', { current: null, known: [] }, isPlayers);
  const sameName = (a, b) => a.localeCompare(b, undefined, { sensitivity: 'accent' }) === 0;

  return {
    getPrefs: () => read('prefs', {}, isObject),
    savePrefs: (prefs) => write('prefs', prefs),
    updatePrefs(patch) {
      return write('prefs', { ...read('prefs', {}, isObject), ...patch });
    },

    /** The player currently playing on this device: { name, key } or null. */
    getCurrentPlayer() {
      const { current, known } = readPlayers();
      return (current && known.find((p) => sameName(p.name, current))) ?? null;
    },
    /** A name this device already owns (case-insensitive), or null. */
    findKnownPlayer(name) {
      return readPlayers().known.find((p) => sameName(p.name, name)) ?? null;
    },
    /** Remembers a player and makes them current. */
    rememberPlayer(player) {
      const { known } = readPlayers();
      const others = known.filter((p) => !sameName(p.name, player.name));
      return write('players', { current: player.name, known: [...others, { name: player.name, key: player.key }] });
    },
    setCurrentPlayer(name) {
      const players = readPlayers();
      return write('players', { ...players, current: name });
    },
    /** Drops a player whose key the server no longer recognises (e.g. database reset). */
    forgetPlayer(name) {
      const { current, known } = readPlayers();
      return write('players', {
        current: current && sameName(current, name) ? null : current,
        known: known.filter((p) => !sameName(p.name, name)),
      });
    },

    getActiveAttemptId: () => read('activeAttempt', null, (v) => typeof v === 'string' && v.length > 0),
    setActiveAttemptId: (id) => write('activeAttempt', id),
    clearActiveAttemptId: () => remove('activeAttempt'),
  };
}

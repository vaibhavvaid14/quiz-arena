import { HISTORY_LIMIT } from '../js/core/config.js';
import { createMemoryBackend, createStorage } from '../js/core/storage.js';
import { assert, deepEqual, equal, test } from './harness.js';

const session = { id: 's1', status: 'active', config: { count: 1 }, items: [{ questionId: 'q' }] };
const entry = (id) => ({ id, finishedAt: 1, config: {}, items: [], summary: { total: 1, correct: 1, percentage: 100 } });

test('storage: prefs round-trip and merge', () => {
  const store = createStorage(createMemoryBackend());
  deepEqual(store.getPrefs(), {});
  store.savePrefs({ theme: 'dark' });
  store.updatePrefs({ lastConfig: { count: 5 } });
  deepEqual(store.getPrefs(), { theme: 'dark', lastConfig: { count: 5 } });
});

test('storage: values are wrapped in a versioned, namespaced envelope', () => {
  const backend = createMemoryBackend();
  createStorage(backend).savePrefs({ theme: 'light' });
  deepEqual(JSON.parse(backend.dump()['quizapp:prefs']), { v: 1, data: { theme: 'light' } });
});

test('storage: active session save / load / clear', () => {
  const store = createStorage(createMemoryBackend());
  equal(store.getActiveSession(), null);
  store.saveActiveSession(session);
  deepEqual(store.getActiveSession(), session);
  store.clearActiveSession();
  equal(store.getActiveSession(), null);
});

test('storage: finished or malformed sessions are not offered for resume', () => {
  const store = createStorage(createMemoryBackend());
  store.saveActiveSession({ ...session, status: 'finished' });
  equal(store.getActiveSession(), null);
  store.saveActiveSession({ ...session, items: [] });
  equal(store.getActiveSession(), null);
});

test('storage: corrupt JSON and old schema versions fall back safely', () => {
  const errors = [];
  const backend = createMemoryBackend({
    'quizapp:history': '{not json',
    'quizapp:prefs': JSON.stringify({ v: 0, data: { theme: 'dark' } }),
  });
  const store = createStorage(backend, { onError: (e) => errors.push(e) });
  deepEqual(store.getHistory(), []);
  deepEqual(store.getPrefs(), {});
  equal(errors.length, 1);
  equal(backend.getItem('quizapp:history'), null, 'corrupt key removed');
  equal(backend.getItem('quizapp:prefs'), null, 'stale key removed');
});

test('storage: history is newest-first, de-duplicated and capped', () => {
  const store = createStorage(createMemoryBackend());
  for (let i = 0; i < HISTORY_LIMIT + 5; i += 1) store.addHistoryEntry(entry(`e${i}`));
  equal(store.getHistoryEntry('e4'), null, 'oldest entries evicted');
  store.addHistoryEntry(entry('e10')); // re-saving an existing attempt moves it to the top
  const history = store.getHistory();
  equal(history.length, HISTORY_LIMIT);
  equal(history[0].id, 'e10');
  equal(history[1].id, `e${HISTORY_LIMIT + 4}`);
  equal(history.filter((h) => h.id === 'e10').length, 1);
  equal(store.getHistoryEntry('e20').id, 'e20');
  store.clearHistory();
  deepEqual(store.getHistory(), []);
});

test('storage: a throwing backend (quota / disabled) never crashes the app', () => {
  const errors = [];
  const broken = {
    getItem() {
      throw new Error('denied');
    },
    setItem() {
      throw new Error('quota');
    },
    removeItem() {
      throw new Error('denied');
    },
  };
  const store = createStorage(broken, { onError: (e) => errors.push(e) });
  equal(store.savePrefs({ a: 1 }), false);
  deepEqual(store.getPrefs(), {});
  equal(store.getActiveSession(), null);
  store.clearActiveSession();
  assert(errors.length >= 3);
});

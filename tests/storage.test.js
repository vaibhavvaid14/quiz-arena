import { createMemoryBackend, createStorage } from '../js/core/storage.js';
import { assert, deepEqual, equal, test } from './harness.js';

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
  deepEqual(JSON.parse(backend.dump()['quizapp:prefs']), { v: 2, data: { theme: 'light' } });
});

test('storage: players are remembered per device, case-insensitively', () => {
  const store = createStorage(createMemoryBackend());
  equal(store.getCurrentPlayer(), null);
  store.rememberPlayer({ name: 'Alex', key: 'k1' });
  store.rememberPlayer({ name: 'Sam', key: 'k2' });
  deepEqual(store.getCurrentPlayer(), { name: 'Sam', key: 'k2' });
  deepEqual(store.findKnownPlayer('alex'), { name: 'Alex', key: 'k1' });
  store.setCurrentPlayer('Alex');
  equal(store.getCurrentPlayer().key, 'k1');
  store.forgetPlayer('ALEX');
  equal(store.getCurrentPlayer(), null);
  equal(store.findKnownPlayer('Alex'), null);
  equal(store.findKnownPlayer('Sam').key, 'k2');
});

test('storage: active attempt id save / load / clear', () => {
  const store = createStorage(createMemoryBackend());
  equal(store.getActiveAttemptId(), null);
  store.setActiveAttemptId('abc123');
  equal(store.getActiveAttemptId(), 'abc123');
  store.clearActiveAttemptId();
  equal(store.getActiveAttemptId(), null);
});

test('storage: corrupt JSON and old schema versions fall back safely', () => {
  const errors = [];
  const backend = createMemoryBackend({
    'quizapp:players': '{not json',
    'quizapp:prefs': JSON.stringify({ v: 1, data: { theme: 'dark' } }),
  });
  const store = createStorage(backend, { onError: (e) => errors.push(e) });
  equal(store.getCurrentPlayer(), null);
  deepEqual(store.getPrefs(), {});
  equal(errors.length, 1);
  equal(backend.getItem('quizapp:players'), null, 'corrupt key removed');
  equal(backend.getItem('quizapp:prefs'), null, 'stale key removed');
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
  equal(store.getCurrentPlayer(), null);
  store.clearActiveAttemptId();
  assert(errors.length >= 3);
});

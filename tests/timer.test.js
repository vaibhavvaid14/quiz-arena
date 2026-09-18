import { Timer } from '../js/core/timer.js';
import { assert, equal, test } from './harness.js';

/** Manual clock + interval scheduler so tests control time exactly. */
function fakeEnv() {
  let now = 0;
  const intervals = new Map();
  let nextId = 1;
  return {
    now: () => now,
    setIntervalFn: (fn) => {
      intervals.set(nextId, fn);
      return nextId++;
    },
    clearIntervalFn: (id) => intervals.delete(id),
    advance(ms) {
      now += ms;
      for (const fn of [...intervals.values()]) fn();
    },
    get activeIntervals() {
      return intervals.size;
    },
  };
}

test('timer: counts down from the limit using the clock', () => {
  const env = fakeEnv();
  const t = new Timer({ limitMs: 10000, ...env }).start();
  env.advance(2500);
  equal(t.elapsed(), 2500);
  equal(t.remaining(), 7500);
  equal(t.fraction(), 0.75);
});

test('timer: pause freezes elapsed time and resume continues', () => {
  const env = fakeEnv();
  const t = new Timer({ limitMs: 10000, ...env }).start();
  env.advance(1000);
  t.pause();
  env.advance(5000);
  equal(t.elapsed(), 1000);
  equal(env.activeIntervals, 0, 'interval cleared while paused');
  t.start();
  env.advance(1000);
  equal(t.elapsed(), 2000);
});

test('timer: fires onExpire exactly once and clamps at zero', () => {
  const env = fakeEnv();
  let expired = 0;
  const t = new Timer({ limitMs: 3000, onExpire: () => (expired += 1), ...env }).start();
  env.advance(2000);
  equal(expired, 0);
  env.advance(5000);
  env.advance(1000);
  equal(expired, 1);
  equal(t.remaining(), 0);
  assert(t.isExpired);
  assert(!t.isRunning);
  t.start();
  equal(t.isRunning, false, 'an expired timer cannot restart');
});

test('timer: resumes from previously elapsed time', () => {
  const env = fakeEnv();
  const ticks = [];
  const t = new Timer({ limitMs: 10000, elapsedMs: 4000, onTick: (s) => ticks.push(s.remainingMs), ...env }).start();
  equal(ticks[0], 6000, 'first tick fires immediately on start');
  env.advance(1000);
  equal(t.remaining(), 5000);
});

test('timer: resuming at or past the limit expires on start (no stuck 0:00)', () => {
  const env = fakeEnv();
  let expired = 0;
  const t = new Timer({ limitMs: 5000, elapsedMs: 5000, onExpire: () => (expired += 1), ...env });
  equal(t.remaining(), 0);
  t.start();
  equal(expired, 1);
  assert(t.isExpired);
  assert(!t.isRunning);
});

test('timer: unlimited timer acts as a stopwatch and never expires', () => {
  const env = fakeEnv();
  let expired = false;
  const t = new Timer({ onExpire: () => (expired = true), ...env }).start();
  env.advance(1e9);
  equal(t.elapsed(), 1e9);
  equal(t.remaining(), Infinity);
  equal(t.fraction(), 1);
  equal(expired, false);
});

test('timer: dispose stops callbacks', () => {
  const env = fakeEnv();
  let ticks = 0;
  const t = new Timer({ limitMs: 1000, onTick: () => (ticks += 1), ...env }).start();
  t.dispose();
  const before = ticks;
  env.advance(5000);
  equal(ticks, before);
  equal(env.activeIntervals, 0);
});

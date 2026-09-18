/**
 * Randomness helpers. Every function takes an injectable `rng` (a function
 * returning a float in [0, 1)) so the engine is deterministic under test.
 */

/**
 * Seeded PRNG (mulberry32). Same seed -> same sequence.
 * @param {number} seed
 * @returns {() => number}
 */
export function createSeededRng(seed) {
  let state = seed >>> 0;
  return function rng() {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Fisher–Yates shuffle. Returns a new array; the input is not mutated.
 * @template T
 * @param {T[]} items
 * @param {() => number} [rng]
 * @returns {T[]}
 */
export function shuffle(items, rng = Math.random) {
  const result = items.slice();
  for (let i = result.length - 1; i > 0; i -= 1) {
    const j = Math.floor(rng() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

/**
 * Short unique-enough id for sessions and history entries.
 * @param {string} prefix
 * @param {() => number} [rng]
 * @param {number} [now]
 */
export function createId(prefix, rng = Math.random, now = Date.now()) {
  const random = Math.floor(rng() * 0x100000000).toString(36).padStart(7, '0');
  return `${prefix}_${now.toString(36)}${random}`;
}

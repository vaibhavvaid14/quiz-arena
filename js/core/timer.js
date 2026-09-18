/**
 * Drift-free countdown / stopwatch.
 *
 * Elapsed time is always derived from a monotonic clock (`now()`), never from
 * counting ticks, so throttled background tabs and slow frames cannot make the
 * timer lie. The interval only drives UI updates and expiry detection.
 *
 * With `limitMs: Infinity` it behaves as a plain stopwatch (never expires).
 */

const defaultNow = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

export class Timer {
  /**
   * @param {object} options
   * @param {number} [options.limitMs=Infinity]
   * @param {number} [options.elapsedMs=0]   resume from previously elapsed time
   * @param {(state: TimerState) => void} [options.onTick]
   * @param {() => void} [options.onExpire]
   * @param {number} [options.intervalMs=100]
   * @param {() => number} [options.now]      injectable clock (tests)
   * @param {typeof setInterval} [options.setIntervalFn]
   * @param {typeof clearInterval} [options.clearIntervalFn]
   *
   * @typedef {{ elapsedMs: number, remainingMs: number, fraction: number }} TimerState
   *   `fraction` is remaining/limit in [0, 1] (always 1 for an unlimited timer).
   */
  constructor({
    limitMs = Infinity,
    elapsedMs = 0,
    onTick = null,
    onExpire = null,
    intervalMs = 100,
    now = defaultNow,
    setIntervalFn = (fn, ms) => setInterval(fn, ms),
    clearIntervalFn = (handle) => clearInterval(handle),
  } = {}) {
    this.limitMs = limitMs;
    this._baseElapsed = Math.max(0, Math.min(elapsedMs, limitMs));
    this._onTick = onTick;
    this._onExpire = onExpire;
    this._intervalMs = intervalMs;
    this._now = now;
    this._setInterval = setIntervalFn;
    this._clearInterval = clearIntervalFn;
    this._startedAt = null;
    this._handle = null;
    // Not marked expired yet even if resumed at/after the limit: the first tick
    // after start() fires onExpire, so a quiz reloaded at 0:00 still gets submitted.
    this._expired = false;
  }

  get isLimited() {
    return Number.isFinite(this.limitMs);
  }

  get isRunning() {
    return this._startedAt !== null;
  }

  get isExpired() {
    return this._expired;
  }

  elapsed() {
    const live = this.isRunning ? this._now() - this._startedAt : 0;
    return Math.min(this.limitMs, this._baseElapsed + live);
  }

  remaining() {
    return this.isLimited ? Math.max(0, this.limitMs - this.elapsed()) : Infinity;
  }

  /** Remaining share of the limit, 0..1 (1 when unlimited). */
  fraction() {
    return this.isLimited ? this.remaining() / this.limitMs : 1;
  }

  state() {
    return { elapsedMs: this.elapsed(), remainingMs: this.remaining(), fraction: this.fraction() };
  }

  start() {
    if (this.isRunning || this._expired) return this;
    this._startedAt = this._now();
    this._handle = this._setInterval(() => this.tick(), this._intervalMs);
    this.tick();
    return this;
  }

  pause() {
    if (!this.isRunning) return this;
    this._baseElapsed = this.elapsed();
    this._startedAt = null;
    this._clearInterval(this._handle);
    this._handle = null;
    return this;
  }

  /** Stops for good; no further callbacks will fire. */
  dispose() {
    this.pause();
    this._onTick = null;
    this._onExpire = null;
  }

  /** Publishes the current state and fires `onExpire` exactly once. Public so tests can drive it. */
  tick() {
    const state = this.state();
    this._onTick?.(state);
    if (this.isLimited && !this._expired && state.remainingMs <= 0) {
      this._expired = true;
      this.pause();
      this._onExpire?.();
    }
  }
}

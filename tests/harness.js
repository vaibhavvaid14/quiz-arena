/**
 * Tiny zero-dependency test runner that runs in the browser.
 * Results render into the page; `document.title` and `body[data-status]` expose
 * the outcome so a headless browser can read it.
 */

const tests = [];

export function test(name, fn) {
  tests.push({ name, fn });
}

export class AssertionError extends Error {}

export function assert(condition, message = 'assertion failed') {
  if (!condition) throw new AssertionError(message);
}

export function equal(actual, expected, message = '') {
  if (!Object.is(actual, expected)) {
    throw new AssertionError(`${message ? `${message}: ` : ''}expected ${fmt(expected)}, got ${fmt(actual)}`);
  }
}

export function deepEqual(actual, expected, message = '') {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new AssertionError(`${message ? `${message}: ` : ''}expected ${e}, got ${a}`);
}

export function throws(fn, ErrorType = Error, message = '') {
  try {
    fn();
  } catch (error) {
    if (error instanceof ErrorType) return error;
    throw new AssertionError(`${message ? `${message}: ` : ''}expected ${ErrorType.name}, got ${error?.name}: ${error?.message}`);
  }
  throw new AssertionError(`${message ? `${message}: ` : ''}expected ${ErrorType.name} to be thrown`);
}

/** Waits for pending microtasks + one macrotask (lets async UI handlers settle). */
export const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fmt(value) {
  return typeof value === 'string' ? JSON.stringify(value) : String(value);
}

export async function run() {
  const list = document.getElementById('results');
  const failures = [];
  for (const { name, fn } of tests) {
    const li = document.createElement('li');
    try {
      await fn();
      li.className = 'pass';
      li.textContent = `✓ ${name}`;
    } catch (error) {
      failures.push(name);
      li.className = 'fail';
      li.textContent = `✗ ${name} — ${error?.message ?? error}`;
      console.error(name, error);
    }
    list.append(li);
  }
  const passed = tests.length - failures.length;
  const status = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.status = status;
  document.title = `${status.toUpperCase()} ${passed}/${tests.length}`;
  document.getElementById('summary').textContent =
    status === 'pass' ? `All ${tests.length} tests passed.` : `${failures.length} of ${tests.length} tests failed.`;
}

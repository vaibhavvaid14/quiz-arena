/**
 * Thin client for the Quiz Arena REST API. Every method returns the parsed
 * JSON body or throws an ApiError with a message that is safe to show users.
 */

export class ApiError extends Error {
  /**
   * @param {number} status  HTTP status (0 when the server could not be reached)
   * @param {string} code    machine-readable error code from the server
   * @param {string} message human-readable message
   */
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }

  get isNetwork() {
    return this.status === 0;
  }
}

/**
 * @param {object} [options]
 * @param {string} [options.baseUrl]            prefix for every request ('' = same origin)
 * @param {typeof fetch} [options.fetchFn]      injectable for tests
 * @param {() => string|null} [options.getKey]  the current player's secret key
 */
export function createApi({ baseUrl = '', fetchFn = (...args) => globalThis.fetch(...args), getKey = () => null } = {}) {
  async function request(method, path, body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const key = getKey();
    if (key) headers['X-Player-Key'] = key;

    let response;
    try {
      response = await fetchFn(`${baseUrl}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        cache: 'no-store',
      });
    } catch {
      throw new ApiError(0, 'network', "Can't reach the quiz server. Check that it is running, then try again.");
    }

    let data = null;
    try {
      data = await response.json();
    } catch {
      /* non-JSON body: fall through to a generic message */
    }
    if (!response.ok) {
      throw new ApiError(
        response.status,
        data?.error?.code ?? 'http_error',
        data?.error?.message ?? `The server returned an error (${response.status}).`,
      );
    }
    return data;
  }

  const attempt = (id) => `/api/attempts/${encodeURIComponent(id)}`;

  return {
    catalog: () => request('GET', '/api/catalog'),
    createPlayer: (name) => request('POST', '/api/players', { name }),
    me: () => request('GET', '/api/me'),
    history: () => request('GET', '/api/me/history'),
    clearHistory: () => request('DELETE', '/api/me/history'),
    leaderboard: (limit) => request('GET', `/api/leaderboard?limit=${encodeURIComponent(limit)}`),

    startAttempt: (config, questionIds) => request('POST', '/api/attempts', questionIds ? { ...config, questionIds } : config),
    getAttempt: (id) => request('GET', attempt(id)),
    discardAttempt: (id) => request('DELETE', attempt(id)),
    answer: (id, position, optionId) => request('POST', `${attempt(id)}/answer`, { position, optionId }),
    next: (id, position) => request('POST', `${attempt(id)}/next`, { position }),
    finish: (id) => request('POST', `${attempt(id)}/finish`),
    results: (id) => request('GET', `${attempt(id)}/results`),
  };
}

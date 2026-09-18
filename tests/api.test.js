import { ApiError, createApi } from '../js/api.js';
import { assert, deepEqual, equal, test } from './harness.js';

/** Records requests and replies with canned responses. */
function fakeFetch(respond) {
  const calls = [];
  const fetchFn = async (url, init) => {
    calls.push({ url, ...init, body: init.body === undefined ? undefined : JSON.parse(init.body) });
    const { status = 200, body, raw } = respond(url, init);
    return new Response(raw ?? JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  };
  return { fetchFn, calls };
}

test('api: sends JSON, the player key header, and returns parsed data', async () => {
  const { fetchFn, calls } = fakeFetch(() => ({ body: { ok: 1 } }));
  const api = createApi({ fetchFn, getKey: () => 'secret' });
  deepEqual(await api.answer('abc', 2, 17), { ok: 1 });
  equal(calls[0].url, '/api/attempts/abc/answer');
  equal(calls[0].method, 'POST');
  equal(calls[0].headers['X-Player-Key'], 'secret');
  equal(calls[0].headers['Content-Type'], 'application/json');
  deepEqual(calls[0].body, { position: 2, optionId: 17 });
});

test('api: omits the key header and body when not needed', async () => {
  const { fetchFn, calls } = fakeFetch(() => ({ body: { entries: [] } }));
  await createApi({ fetchFn }).leaderboard(20);
  equal(calls[0].url, '/api/leaderboard?limit=20');
  equal(calls[0].headers['X-Player-Key'], undefined);
  equal(calls[0].body, undefined);
});

test('api: retry runs send pinned question ids', async () => {
  const { fetchFn, calls } = fakeFetch(() => ({ status: 201, body: {} }));
  await createApi({ fetchFn }).startAttempt({ count: 2 }, ['q1', 'q2']);
  deepEqual(calls[0].body, { count: 2, questionIds: ['q1', 'q2'] });
});

test('api: server errors become ApiError with the server message', async () => {
  const { fetchFn } = fakeFetch(() => ({ status: 409, body: { error: { code: 'name_taken', message: 'Taken.' } } }));
  try {
    await createApi({ fetchFn }).createPlayer('Sam');
    assert(false, 'should have thrown');
  } catch (error) {
    assert(error instanceof ApiError);
    equal(error.status, 409);
    equal(error.code, 'name_taken');
    equal(error.message, 'Taken.');
  }
});

test('api: non-JSON errors and network failures get readable messages', async () => {
  const html = fakeFetch(() => ({ status: 502, raw: '<html>bad gateway</html>' }));
  try {
    await createApi({ fetchFn: html.fetchFn }).catalog();
    assert(false);
  } catch (error) {
    equal(error.status, 502);
    assert(error.message.includes('502'));
  }
  const offline = createApi({
    fetchFn: async () => {
      throw new TypeError('Failed to fetch');
    },
  });
  try {
    await offline.catalog();
    assert(false);
  } catch (error) {
    assert(error.isNetwork, 'network error flagged');
    assert(error.message.includes('quiz server'));
  }
});

test('api: attempt ids are URL-encoded', async () => {
  const { fetchFn, calls } = fakeFetch(() => ({ body: {} }));
  await createApi({ fetchFn }).results('a/b?c');
  equal(calls[0].url, '/api/attempts/a%2Fb%3Fc/results');
});

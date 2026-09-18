/**
 * Browser entry point: wires storage and the API client, then mounts the app.
 */

import { createApi } from './api.js';
import { createApp } from './app.js';
import { createStorage, resolveBackend } from './core/storage.js';

const { backend, persistent } = resolveBackend();
const storage = createStorage(backend, { onError: (error) => console.warn('[quiz] storage error', error) });
const api = createApi({ getKey: () => storage.getCurrentPlayer()?.key ?? null });

createApp({
  root: document.getElementById('app'),
  chrome: document.getElementById('site-header'),
  api,
  storage,
  persistent,
}).start();

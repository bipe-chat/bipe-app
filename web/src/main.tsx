// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { App } from './App.tsx';
import { applyLook } from './appearance';
import { coreReady } from './core';

// Spike trace (bipe-chat/bipe#900): the SDK writes no logs on wasm, so log every request it makes
// with its status and ETag. A stalled QR sign-in then shows its last step in the console.
const realFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
  const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
  const started = performance.now();
  try {
    const res = await realFetch(input, init);
    const ms = Math.round(performance.now() - started);
    console.info(`[trace] ${method} ${url} → ${res.status} etag=${res.headers.get('etag')} ${ms}ms`);
    return res;
  } catch (e) {
    console.warn(`[trace] ${method} ${url} failed`, e);
    throw e;
  }
};

// The stored theme and palette, before the first screen draws (the CSS is loaded by now).
applyLook();

// The shared rules come from bipe-core (core.ts): load it before the first screen draws. Without it no screen draws,
// because the safety rules would be missing (fail closed).
coreReady.then(
  () =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <App />
      </StrictMode>,
    ),
  (e) => console.error('bipe-core did not load', e),
);

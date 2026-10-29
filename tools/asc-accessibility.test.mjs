// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Accessibility declarations (bipe-chat/bipe#503): tools/asc.mjs against a fake App Store Connect API.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { printAccessibility, sendAccessibility } from './asc.mjs';

const attributes = { supportsVoiceover: true, supportsVoiceControl: false, supportsLargerText: true };
const planned = [{ deviceFamily: 'IPHONE', labels: [{ name: 'VoiceOver', attribute: 'supportsVoiceover', supported: true }], attributes }];

function fake(existing) {
  const calls = [];
  const call = async (method, path, body) => {
    calls.push({ method, path, body });
    if (method === 'GET') return { data: existing };
    if (method === 'POST') return { data: { id: 'D-new', type: 'accessibilityDeclarations' } };
    return { data: {} };
  };
  return { call, calls };
}

async function quiet(fn) {
  const log = console.log;
  console.log = () => {};
  try {
    return await fn();
  } finally {
    console.log = log;
  }
}

test('asc accessibility makes a declaration for the app and publishes it', async () => {
  const { call, calls } = fake([]);
  await quiet(() => sendAccessibility(call, 'A1', planned));
  assert.deepEqual(calls.map((c) => c.method), ['GET', 'POST', 'PATCH']);
  assert.equal(calls[0].path, '/v1/apps/A1/accessibilityDeclarations?limit=200');
  assert.deepEqual(calls[1].body.data.attributes, { deviceFamily: 'IPHONE', ...attributes });
  assert.deepEqual(calls[1].body.data.relationships.app.data, { type: 'apps', id: 'A1' });
  assert.deepEqual(calls[2].body.data, { type: 'accessibilityDeclarations', id: 'D-new', attributes: { publish: true } });
});

test('asc accessibility changes a draft, and leaves a published declaration with the same answers', async () => {
  const draft = fake([{ id: 'D1', attributes: { deviceFamily: 'IPHONE', state: 'DRAFT', supportsVoiceover: false } }]);
  await quiet(() => sendAccessibility(draft.call, 'A1', planned));
  assert.deepEqual(draft.calls.map((c) => `${c.method} ${c.path}`), ['GET /v1/apps/A1/accessibilityDeclarations?limit=200', 'PATCH /v1/accessibilityDeclarations/D1', 'PATCH /v1/accessibilityDeclarations/D1']);
  assert.deepEqual(draft.calls[1].body.data.attributes, attributes);
  const same = fake([{ id: 'D2', attributes: { deviceFamily: 'IPHONE', state: 'PUBLISHED', ...attributes } }]);
  await quiet(() => sendAccessibility(same.call, 'A1', planned));
  assert.deepEqual(same.calls.map((c) => c.method), ['GET']);
});

test('asc accessibility dry run prints each label with its answer', () => {
  const lines = [];
  printAccessibility(planned, (l) => lines.push(l));
  assert.deepEqual(lines, ['IPHONE:', '  yes VoiceOver (supportsVoiceover)']);
});

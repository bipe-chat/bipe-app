// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ACCESSIBILITY_FIELDS, accessibility, accessibilityPlan, NEXT } from './asc.mjs';

const table = (rows) => `# Bipe\n\n## Accessibility\n\nText.\n\n| Label | iPhone |\n|---|---|\n${rows}\n\n### pt-BR\n\nAcessibilidade.\n\n## Screenshots\n`;
const all = (answer) => Object.keys(ACCESSIBILITY_FIELDS).map((name) => `| ${name} | ${answer(name)} |`).join('\n');

test('accessibility plan reads every Apple label from appstore.md', () => {
  const plan = accessibilityPlan(table(all((name) => (name === 'Captions' ? 'no' : 'yes'))));
  assert.equal(plan.deviceFamily, 'IPHONE');
  assert.equal(plan.supportsVoiceover, true);
  assert.equal(plan.supportsCaptions, false);
  assert.equal(Object.keys(plan).length, Object.keys(ACCESSIBILITY_FIELDS).length + 1);
});

test('accessibility plan fails on a missing, unknown or unclear row', () => {
  assert.throws(() => accessibilityPlan('# Bipe\n'), /no ## Accessibility/);
  assert.throws(() => accessibilityPlan(table('| VoiceOver | yes |')), /missing/);
  assert.throws(() => accessibilityPlan(table(`${all(() => 'yes')}\n| Braille | yes |`)), /unknown label Braille/);
  assert.throws(() => accessibilityPlan(table(all((name) => (name === 'VoiceOver' ? 'maybe' : 'yes')))), /not yes or no/);
});

test('accessibility changes a draft, creates one otherwise, and publishes only when asked', async () => {
  const plan = accessibilityPlan(table(all(() => 'yes')));
  const calls = [];
  const fake = (declarations) => async (method, path, body) => {
    calls.push([method, path, body]);
    if (path.startsWith('/v1/apps?')) return { data: [{ id: 'app1', attributes: { bundleId: NEXT.appId } }] };
    if (path.endsWith('/accessibilityDeclarations') && method === 'GET') return { data: declarations };
    if (method === 'POST') return { data: { id: 'new1' } };
    return {};
  };
  const draft = await accessibility(fake([{ id: 'd1', attributes: { deviceFamily: 'IPHONE', state: 'DRAFT' } }]), plan);
  assert.deepEqual(draft, { id: 'd1', published: false });
  assert.ok(calls.some(([m, p]) => m === 'PATCH' && p === '/v1/accessibilityDeclarations/d1'));
  assert.ok(!calls.some(([, , b]) => b?.data?.attributes?.publish));
  calls.length = 0;
  const created = await accessibility(fake([{ id: 'p1', attributes: { deviceFamily: 'IPHONE', state: 'PUBLISHED' } }]), plan, { publish: true });
  assert.deepEqual(created, { id: 'new1', published: true });
  assert.ok(calls.some(([m, , b]) => m === 'POST' && b.data.relationships.app.data.id === 'app1'));
  assert.ok(calls.some(([m, , b]) => m === 'PATCH' && b.data.attributes.publish === true));
});

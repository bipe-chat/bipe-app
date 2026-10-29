// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { generatedSlugs, plan, pngSize } from './store-captures.mjs';
import { REPO_ROOT } from './lib.mjs';

// A PNG header with only the IHDR size, enough for pngSize.
function png(width, height) {
  const buf = Buffer.alloc(24);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(buf);
  buf.writeUInt32BE(width, 16);
  buf.writeUInt32BE(height, 20);
  return buf;
}

test('store captures: the generated list names every slug of the screenshot table', () => {
  const text = readFileSync(join(REPO_ROOT, 'android/app/src/screenshotTest/kotlin/chat/bipe/app/store/generated/StoreCaptures.kt'), 'utf8');
  const { slugs, captured } = generatedSlugs(text);
  assert.ok(slugs.includes('aviso-golpe'));
  assert.ok(captured.every((s) => slugs.includes(s)));
});

test('store captures: every captured slug has a preview per locale', () => {
  const text = readFileSync(join(REPO_ROOT, 'android/app/src/screenshotTest/kotlin/chat/bipe/app/store/generated/StoreCaptures.kt'), 'utf8');
  const tests = readFileSync(join(REPO_ROOT, 'android/app/src/screenshotTest/kotlin/chat/bipe/app/store/StoreCaptureTests.kt'), 'utf8');
  for (const slug of generatedSlugs(text).captured) {
    const name = slug.split('-').map((w) => w[0].toUpperCase() + w.slice(1)).join('');
    for (const locale of ['PtBR', 'EnUS']) assert.match(tests, new RegExp(`fun Store${name}${locale}\\(`), `${slug} ${locale}`);
  }
});

test('store captures map reference images to slugs and locales, and list the missing ones', () => {
  const sizes = { a: png(1080, 1920), b: png(1080, 1920), c: png(1080, 2400) };
  const paths = [
    'ref/chat.bipe.app.store.StoreCaptureTestsKt.StoreAvisoGolpePtBR_1a2b_3c4d_0.png',
    'ref/chat.bipe.app.store.StoreCaptureTestsKt.StoreAvisoGolpeEnUS_1a2b_5e6f_0.png',
    'ref/chat.bipe.app.store.StoreCaptureTestsKt.StorePedidosPtBR_aa_bb_0.png',
    'ref/other.Unrelated_0.png',
  ];
  const read = (p) => (p.includes('Pedidos') ? sizes.c : sizes.a);
  const { copies, missing, problems } = plan(paths, ['aviso-golpe', 'pedidos'], { read });
  assert.deepEqual(copies.map((c) => `${c.slug} ${c.locale}`).sort(), ['aviso-golpe en-US', 'aviso-golpe pt-BR', 'pedidos pt-BR']);
  assert.deepEqual(missing, ['pedidos en-US']);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /1080x2400/);
  assert.deepEqual(pngSize(sizes.b), { width: 1080, height: 1920 });
});

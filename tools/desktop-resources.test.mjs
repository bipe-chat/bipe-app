// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { desktopCatalog, writeDesktopResources } from './desktop-resources.mjs';
import { REPO_ROOT, walk } from './lib.mjs';
import { version } from './version.mjs';

const shared = process.env.BIPE_SHARED || resolve(REPO_ROOT, '../bipe-shared');
test('desktop palettes, trust tokens and translations follow canonical catalogues', async () => {
  const catalog = await desktopCatalog(shared);
  const tokens = JSON.parse(readFileSync(join(shared, 'design/tokens.json')));
  assert.deepEqual(catalog.palettes, Object.keys(tokens.appThemes.palettes));
  for (const locale of ['pt-BR', 'en']) {
    const source = JSON.parse(readFileSync(join(shared, `strings/${locale}.json`))).strings;
    assert.deepEqual(catalog.strings[locale], source);
  }
  for (const palette of catalog.palettes) {
    assert.deepEqual(catalog.themes[palette]['trust.verified.ink'], catalog.themes.acai['trust.verified.ink']);
    assert.deepEqual(Object.keys(catalog.themes[palette]), Object.keys(tokens.semantic));
  }
  assert.notDeepEqual(catalog.themes.jenipapo['accent.fill'], catalog.themes.acai['accent.fill']);
});

test('every desktop UI string key exists in both canonical languages', async () => {
  const catalog = await desktopCatalog(shared);
  const keys = new Set();
  for (const path of walk(REPO_ROOT, 'android/desktop-app').filter(p => p.endsWith('.kt'))) {
    for (const match of readFileSync(join(REPO_ROOT, path), 'utf8').matchAll(/"(bipe_[a-z0-9_]+)"/g)) keys.add(match[1]);
  }
  for (const palette of catalog.palettes) keys.add(`bipe_colors_${palette}`);
  for (const locale of ['pt-BR', 'en']) for (const key of keys) assert.equal(typeof catalog.strings[locale][key], 'string', `${locale}: missing ${key}`);
});

test('desktop resource generation uses the shared version/build and existing Bipe icon', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'bipe-desktop-resources-'));
  try {
    await writeDesktopResources(directory, shared);
    assert.deepEqual(JSON.parse(readFileSync(join(directory, 'bipe-build.json'))), await version(REPO_ROOT));
    const icon = readFileSync(join(directory, 'bipe-icon.png'));
    assert.deepEqual(icon.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    const catalog = JSON.parse(readFileSync(join(directory, 'bipe-desktop.json')));
    assert.equal(catalog.schema, 1);
    for (const palette of catalog.palettes) {
      assert.deepEqual(readFileSync(join(directory, `bipe-icon-${palette}.png`)), readFileSync(join(REPO_ROOT,
        `ios/BipeKit/Sources/BipeKit/Generated/AppIcons.xcassets/BipePalette-${palette}.imageset/icon.png`)));
    }
  } finally { rmSync(directory, { recursive: true }); }
});

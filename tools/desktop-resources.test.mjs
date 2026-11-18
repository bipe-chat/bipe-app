// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
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

test('desktop settings hierarchy, choices and fruit artwork come from the shared sources', async () => {
  const catalog = await desktopCatalog(shared);
  assert.deepEqual(catalog.settings, JSON.parse(readFileSync(join(shared, 'registries/settings.json'))));
  assert.deepEqual(catalog.appearance.map(option => option.id), ['system', 'light', 'dark']);
  assert.equal(catalog.appearance[0].title, 'bipe_web_appearance_system');
  assert.deepEqual(catalog.backgrounds.options, ['marca', 'frutas', 'cor', 'liso']);
  const { fruitTile, svgPath } = await import(pathToFileURL(join(shared, 'design/chat-fruits.mjs')).href);
  const fruit = fruitTile();
  assert.deepEqual(catalog.fruit.placements, fruit.placements);
  assert.deepEqual(catalog.fruit.glyphs[0].bodies, fruit.glyphs[0].bodies.map(svgPath));
  for (const option of catalog.appearance) for (const locale of ['pt-BR', 'en']) assert.equal(typeof catalog.strings[locale][option.title], 'string');
});

test('every Rust scam kind has canonical warning text and the shared pause policy', async () => {
  const catalog = await desktopCatalog(shared);
  const source = readFileSync(join(REPO_ROOT, 'core/crates/bipe-core/src/scam.rs'), 'utf8');
  const body = source.match(/pub enum ScamKind\s*\{([^}]+)\}/)?.[1];
  assert.ok(body, 'Rust ScamKind enum missing');
  const ids = body.split(',').map(s => s.trim()).filter(Boolean).map(s => s[0].toLowerCase() + s.slice(1));
  assert.deepEqual(new Set(ids), new Set(catalog.safety.warnings.map(w => w.id)));
  for (const warning of catalog.safety.warnings) for (const locale of ['pt-BR', 'en']) {
    assert.equal(typeof catalog.strings[locale][warning.messageKey], 'string');
  }
  const policy = JSON.parse(readFileSync(join(shared, 'registries/scam-patterns.json')));
  assert.deepEqual(catalog.safety.moneyPauseKinds, policy.moneyPauseKinds);
  assert.equal(catalog.safety.moneyPauseSeconds, policy.moneyPauseSeconds);
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
    assert.match(readFileSync(join(directory, 'warning-circle.svg'), 'utf8'), /<svg /);
    assert.match(readFileSync(join(directory, 'phosphor-LICENSE.txt'), 'utf8'), /MIT License/);
    assert.match(readFileSync(join(directory, 'phosphor-archive.svg'), 'utf8'), /<svg /);
    for (const group of catalog.settings.groups.filter(group => group.icon)) {
      assert.match(readFileSync(join(directory, `phosphor-${group.icon}.svg`), 'utf8'), /<svg /);
    }
    assert.deepEqual(JSON.parse(readFileSync(join(directory, 'open-source-licences.json'))),
      JSON.parse(readFileSync(join(REPO_ROOT, 'android/app/src/main/assets/open_source_licences.json'))));
  } finally { rmSync(directory, { recursive: true }); }
});

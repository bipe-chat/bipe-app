// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for tools/oss-licences.mjs. Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { OUTPUTS, licenceList, render } from './oss-licences.mjs';
import { REPO_ROOT } from './lib.mjs';

test('the licence lists in the apps match tools/licences.json', () => {
  for (const [platform, path] of Object.entries(OUTPUTS)) {
    assert.equal(readFileSync(join(REPO_ROOT, path), 'utf8'), render(platform), `${path} is stale: run node tools/oss-licences.mjs`);
  }
});

test('Bipe comes first, then the dependencies of the platform, each with a text', () => {
  for (const platform of Object.keys(OUTPUTS)) {
    const { components, texts } = licenceList(platform);
    assert.equal(components[0].name, 'Bipe');
    assert.equal(components[0].licence, 'AGPL-3.0-only');
    for (const c of components) assert.ok(c.text ?? texts[c.licence], `${c.name} has no licence text`);
    assert.ok(!components.some((c) => /element|compound/i.test(c.name)));
  }
  assert.ok(licenceList('ios').components.some((c) => c.name === 'matrix-rust-components-swift'));
  assert.ok(!licenceList('ios').components.some((c) => c.name.startsWith('androidx.')));
  assert.ok(licenceList('android').components.some((c) => c.name === 'org.matrix.rustcomponents:sdk-android'));
});

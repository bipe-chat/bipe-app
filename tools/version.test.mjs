// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the version name and build number (ADR 0227). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { nextVersion, parseVersionFile, releaseTarget, versionName } from './version.mjs';

test('the odometer rolls each digit at 9', () => {
  assert.equal(nextVersion('1.0.1'), '1.0.2');
  assert.equal(nextVersion('1.0.9'), '1.1.0');
  assert.equal(nextVersion('1.9.9'), '2.0.0');
  assert.equal(nextVersion('12.9.9'), '13.0.0');
  assert.throws(() => nextVersion('1.0.10'));
});

test('version.json takes only odometer versions', () => {
  assert.equal(parseVersionFile('{"version":"1.0.1","build":12}').version, '1.0.1');
  assert.throws(() => parseVersionFile('{"version":"1.10.0","build":12}'));
});

test('the version name: version.json before a release, the release on its commit, the next one after it', () => {
  assert.equal(versionName({ base: '1.0.1', released: [] }), '1.0.1');
  assert.equal(versionName({ base: '1.0.1', released: [], atCommit: '1.0.1' }), '1.0.1');
  assert.equal(versionName({ base: '1.0.1', released: ['1.0.1', '1.0.9', '1.0.2'] }), '1.1.0');
});

test('a release names a build on the line, above the last release', () => {
  const history = ['a', 'b', 'c'].map((commit, i) => ({ commit, date: '2026-11-03', count: i + 1 }));
  const none = { base: '1.0.1', all: [], before: () => [], at: () => undefined };
  assert.deepEqual(releaseTarget({ build: 12, base: 10, history, released: none }), { commit: 'b', version: '1.0.1' });
  assert.throws(() => releaseTarget({ build: 99, base: 10, history, released: none }), /no commit/);
  const one = { base: '1.0.1', all: ['1.0.1'], before: (c) => (c >= 'b' ? ['1.0.1'] : []), at: (c) => (c === 'b' ? '1.0.1' : undefined) };
  assert.throws(() => releaseTarget({ build: 12, base: 10, history, released: one }), /already release/);
  assert.throws(() => releaseTarget({ build: 11, base: 10, history, released: one }), /not above/);
  assert.deepEqual(releaseTarget({ build: 13, base: 10, history, released: one }), { commit: 'c', version: '1.0.2' });
});

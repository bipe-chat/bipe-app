// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the changelog (changelog/README.md). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { buildFor, builds, changelogJson, checkUpdates, draftUpdate, missingUpdate, parseUpdate, updateProblems } from './changelog.mjs';
import { REPO_ROOT } from './lib.mjs';

const good = `---
id: 2026-11-02-pr360
date: 2026-11-02
kind: fixed
platforms: [ios, android]
refs: [bipe-app#360]
---

**Send stays on screen.** The keyboard no longer covers it.
`;

test('a well-formed update has no problems', () => {
  const u = parseUpdate(good);
  assert.deepEqual(u.platforms, ['ios', 'android']);
  assert.equal(u.build, undefined);
  assert.deepEqual(updateProblems('2026-11-02-pr360.md', u), []);
});

test('a bad kind, platform or headline fails', () => {
  const u = parseUpdate(good.replace('fixed', 'tweaked').replace('ios, android', 'web').replace('**Send stays on screen.**', 'Send'));
  assert.equal(updateProblems('2026-11-02-pr360.md', u).length, 3);
});

test('builds: tags before version.json, the count after it', () => {
  const history = ['a', 'b', 'c', 'd', 'e'].map((commit, i) => ({ commit, date: `2026-10-2${i}`, count: i + 1 }));
  const list = builds({
    history,
    tags: new Map([['b', 70]]),
    versionAt: (c) => (c >= 'd' ? { version: '1.0.0', build: 100 } : null),
  });
  assert.deepEqual(list.map((b) => b.build), [70, 104, 105]);
  const byCommit = new Map(list.map((b) => [b.commit, b]));
  assert.equal(buildFor(0, history, byCommit).build, 70); // a: rolls forward to the tag on b
  assert.equal(buildFor(2, history, byCommit).build, 104); // c: no tag, next build is d
});

test('builds: a release tag gives its builds a version, later builds wait for the next one', () => {
  const history = ['a', 'b', 'c', 'd'].map((commit, i) => ({ commit, date: `2026-11-0${i + 1}`, count: i + 1 }));
  const list = builds({
    history,
    tags: new Map(),
    versionAt: () => ({ version: '1.0.1', build: 10 }),
    releases: new Map([['b', '1.0.1']]),
    first: '1.0.1',
  });
  assert.deepEqual(list.map((b) => [b.build, b.version, b.released, b.releaseDate]), [
    [11, '1.0.1', true, '2026-11-02'],
    [12, '1.0.1', true, '2026-11-02'],
    [13, '1.0.2', false, null],
    [14, '1.0.2', false, null],
  ]);
});

test('backfill skips tooling commits and moves issue numbers to refs', () => {
  assert.equal(draftUpdate({ subject: 'Regenerate from bipe-shared#348 (#359)', date: '2026-11-02', short: 'x', paths: ['ios/a.swift'] }), null);
  assert.equal(draftUpdate({ subject: 'Docs (#1)', date: '2026-11-02', short: 'x', paths: ['docs/a.md'] }), null);
  const d = draftUpdate({
    subject: 'Chat: open at the newest message (bipe-chat/bipe#965) (#356)',
    date: '2026-11-02',
    short: '13e37a2',
    paths: ['ios/Bipe/Chat.swift'],
    build: 336,
  });
  assert.match(d.text, /refs: \[bipe-app#356, bipe#965\]/);
  assert.match(d.text, /platforms: \[ios\]/);
  assert.match(d.text, /\*\*Chat: open at the newest message\.\*\*/);
  const mixed = draftUpdate({ subject: 'Link approval (bipe-chat/bipe#963, ADR 0223) (#363)', date: '2026-11-02', short: 'd319ec9', paths: ['ios/a.swift'] });
  assert.match(mixed.text, /refs: \[bipe-app#363, bipe#963, adr-0223\]/);
});

test('JSON splits the headline from the detail, newest build first', () => {
  const u = { ...parseUpdate(good), build: 360 };
  const old = { ...parseUpdate(good.replace('pr360', 'pr1')), id: '2026-11-02-pr1', build: 12 };
  const json = changelogJson([old, u], [{ build: 360, version: '1.0.0', date: '2026-11-02' }]);
  assert.deepEqual(json.map((b) => b.build), [360, 12]);
  assert.equal(json[0].updates[0].headline, 'Send stays on screen.');
  assert.equal(json[0].updates[0].detail, 'The keyboard no longer covers it.');
});

test('a pull request that changes app code needs an update file', () => {
  const paths = ['ios/Bipe/Chat.swift', 'android/app/src/main/kotlin/A.kt'];
  assert.match(missingUpdate({ title: 'Chat: scroll', paths, added: [] }), /ios and android/);
  assert.equal(missingUpdate({ title: 'Chat: scroll', paths, added: ['changelog/updates/2026-11-02-pr380.md'] }), null);
  assert.equal(missingUpdate({ title: 'Chat: scroll', body: 'Refactor.\n\nChangelog: none\n', paths, added: [] }), null);
  assert.equal(missingUpdate({ title: 'Regenerate from bipe-shared#356', paths, added: [] }), null);
  assert.equal(missingUpdate({ title: 'Docs', paths: ['docs/a.md', 'ios/BipeKit/Sources/BipeKit/Generated/A.swift'], added: [] }), null);
});

test('every update file in the repo is well formed', () => {
  assert.deepEqual(checkUpdates(REPO_ROOT), []);
});

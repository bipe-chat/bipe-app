// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { artifactURL, artifactsFor, cacheArtifact, cacheKey, checkManifest } from './swiftpm-assets.mjs';

const registry = JSON.parse(readFileSync(new URL('./swiftpm-assets.json', import.meta.url)));
const resolved = JSON.parse(readFileSync(new URL('../ios/Package.resolved', import.meta.url)));

test('SwiftPM prefetch has exact package pins and only the needed platform archives', () => {
  assert.equal(artifactsFor(registry, resolved, 'ios').length, 3);
  assert.deepEqual(artifactsFor(registry, resolved, 'macos').map((value) => value.identity), ['matrix-rust-components-swift']);
  const stale = structuredClone(registry);
  stale.artifacts[0].version = '0.0.0';
  assert.throws(() => artifactsFor(stale, resolved, 'macos'), /stale pinned/);
});

test('the pinned manifest must contain the archive, version and checksum', () => {
  const artifact = registry.artifacts[0];
  checkManifest(artifact, [artifact.filename, artifact.version, artifact.sha256].join('\n'));
  assert.throws(() => checkManifest(artifact, artifact.filename), /pinned Package.swift/);
  const invalid = structuredClone(registry);
  invalid.artifacts[0].filename = '../other.zip';
  assert.throws(() => artifactsFor(invalid, resolved, 'macos'), /Invalid/);
});

test('ASCII artifact URLs use the documented SwiftPM cache key', () => {
  assert.equal(cacheKey('https://github.com/owner/package/releases/download/1.2.3/SDK.zip'),
    'https___github_com_owner_package_releases_download_1_2_3_SDK_zip');
  assert.throws(() => cacheKey('file:///private/archive.zip'));
});

test('a verified cached artifact is reused and a bad download never enters the cache', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'bipe-assets-test-'));
  try {
    const bytes = Buffer.from('synthetic archive');
    const source = join(directory, 'fixture.zip');
    writeFileSync(source, bytes);
    const artifact = { ...registry.artifacts[0], sha256: createHash('sha256').update(bytes).digest('hex') };
    const path = await cacheArtifact(artifact, directory, async () => source);
    assert.deepEqual(readFileSync(path), bytes);
    await cacheArtifact(artifact, directory, async () => { throw new Error('must use the verified cache'); });
    const bad = { ...artifact, filename: 'Bad.zip', sha256: '0'.repeat(64) };
    await assert.rejects(cacheArtifact(bad, directory, async () => source), /SHA-256 mismatch/);
    assert.equal(existsSync(join(directory, 'artifacts', cacheKey(artifactURL(bad)))), false);
    assert.deepEqual(readFileSync(path), bytes);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

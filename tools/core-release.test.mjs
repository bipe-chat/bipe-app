// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// bipe-chat/bipe#1277 item 4: every consumer of a bipe-core release pins what core/release/release.json says.
// release.json is the manifest that core-release.yml published (bipe-core-<version>.json). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { REPO_ROOT } from './lib.mjs';

const read = (path) => readFileSync(join(REPO_ROOT, path), 'utf8');
const release = JSON.parse(read('core/release/release.json'));
const asset = (name) => {
  const sha = release.assets[name];
  assert.match(sha ?? '', /^[0-9a-f]{64}$/, `release.json has no SHA-256 for ${name}`);
  return sha;
};

test('release.json is a complete manifest', () => {
  assert.match(release.version, /^\d+\.\d+\.\d+$/);
  assert.equal(release.tag, `core-${release.version}`);
  assert.match(release.commit, /^[0-9a-f]{40}$/);
  for (const name of [
    `bipe_coreFFI-${release.version}.xcframework.zip`,
    `bipe_core-${release.version}.swift`,
    `bipe-core-${release.version}.aar`,
    `bipe-core-jvm-${release.version}.jar`,
    `bipe-core-web-${release.version}.tgz`,
  ]) asset(name);
});

test('the SwiftPM package pins the release zip and carries its Swift bindings', () => {
  const manifest = read('core/release/swift/Package.swift');
  assert.match(manifest, new RegExp(`let version = "${release.version.replace(/\./g, '\\.')}"`));
  assert.match(manifest, new RegExp(`let checksum = "${asset(`bipe_coreFFI-${release.version}.xcframework.zip`)}"`));
  const bindings = readFileSync(join(REPO_ROOT, 'core/release/swift/Sources/BipeCore/generated/bipe_core.swift'));
  assert.equal(createHash('sha256').update(bindings).digest('hex'), asset(`bipe_core-${release.version}.swift`),
    'Sources/BipeCore/generated/bipe_core.swift is not the bipe_core.swift of the release');
});

test('the Gradle consumer pins the release AAR', () => {
  const metadata = read('core/release/android/gradle/verification-metadata.xml');
  const component = metadata.match(/<component group="chat\.bipe\.core" name="bipe-core" version="([^"]+)">([\s\S]*?)<\/component>/);
  assert.ok(component, 'verification-metadata.xml has no chat.bipe.core:bipe-core component');
  assert.equal(component[1], release.version);
  assert.match(component[2], new RegExp(`<artifact name="bipe-core-${release.version.replace(/\./g, '\\.')}\\.aar">\\s*<sha256 value="${asset(`bipe-core-${release.version}.aar`)}"`));
});

// bipe-chat/bipe#645: the Android app (libraries/bipe) takes the AAR, and its JVM unit tests the jar, from the same release.
test('the Android app pins the release AAR and jar', () => {
  const metadata = read('android/gradle/verification-metadata.xml');
  for (const [name, file] of [['bipe-core', `bipe-core-${release.version}.aar`], ['bipe-core-jvm', `bipe-core-jvm-${release.version}.jar`]]) {
    const component = metadata.match(new RegExp(`<component group="chat\\.bipe\\.core" name="${name}" version="([^"]+)">([\\s\\S]*?)</component>`));
    assert.ok(component, `android/gradle/verification-metadata.xml has no chat.bipe.core:${name} component`);
    assert.equal(component[1], release.version);
    assert.match(component[2], new RegExp(`<artifact name="${file.replace(/\./g, '\\.')}">\\s*<sha256 value="${asset(file)}"`));
  }
});

test('BipeKit takes BipeCore from the pinned release package', () => {
  assert.match(read('ios/BipeKit/Package.swift'), /\.package\(path: "\.\.\/\.\.\/core\/release\/swift"\)/);
});

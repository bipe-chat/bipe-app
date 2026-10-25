// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the release gate (bipe-chat/bipe#432). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { checkSourceText, checkProjectYml, checkGradleText, checkReleaseGate } from './release-gate.mjs';
import { REPO_ROOT } from './lib.mjs';

test('a 7-tap gesture on the version text fails', () => {
  const errors = checkSourceText('ios/Bipe/Settings/X.swift', 'Text(v)\n    .onTapGesture(count: 7) { unlock() }\n');
  assert.equal(errors.length, 1);
  assert.match(errors[0], /X\.swift:2: unlock-gesture/);
});

test('a double tap and a long press stay allowed', () => {
  assert.deepEqual(checkSourceText('ios/Bipe/X.swift', '.onTapGesture(count: 2) { zoom() }\n.onLongPressGesture { copy() }\n'), []);
});

test('a tap counter on Android fails', () => {
  const errors = checkSourceText('android/app/X.kt', 'var versionTapCount by remember { mutableIntStateOf(0) }\n');
  assert.equal(errors.length, 1);
});

test('a stored developer switch fails', () => {
  assert.equal(checkSourceText('ios/Bipe/X.swift', '@AppStorage("developerOptionsEnabled") var on = false\n').length, 1);
  assert.equal(checkSourceText('android/app/X.kt', 'prefs.getBoolean("dev_mode", false)\n').length, 1);
});

test('the flag:developer gate passes only with the build type on the line', () => {
  assert.deepEqual(checkSourceText('android/app/X.kt', 'row.gate == "flag:developer" && BuildConfig.DEBUG\n'), []);
  assert.equal(checkSourceText('android/app/X.kt', 'row.gate == "flag:developer" && prefs.on\n').length, 1);
});

test('a comment that names developer options passes', () => {
  assert.deepEqual(checkSourceText('ios/Bipe/X.swift', '// shows only in developer builds\nlet a = 1 // "developer"\n'), []);
});

test('settings flags from a stored value fail', () => {
  assert.equal(checkSourceText('ios/Bipe/X.swift', 'row.isShownOnIOS(flags: store.flags)\n').length, 1);
  assert.deepEqual(checkSourceText('ios/Bipe/X.swift', 'row.isShownOnIOS(flags: AppConfig.isDeveloperBuild ? ["developer"] : [])\n'), []);
});

test('isDeveloperBuild = true only under #if DEBUG', () => {
  const ok = '#if DEBUG\nstatic let isDeveloperBuild = true\n#else\nstatic let isDeveloperBuild = false\n#endif\n';
  assert.deepEqual(checkSourceText('ios/Bipe/AppConfig.swift', ok), []);
  const bad = '#if DEBUG\nstatic let isDeveloperBuild = false\n#else\nstatic let isDeveloperBuild = true\n#endif\n';
  assert.equal(checkSourceText('ios/Bipe/AppConfig.swift', bad).length, 1);
  assert.equal(checkSourceText('ios/Bipe/AppConfig.swift', 'static var isDeveloperBuild = true\n').length, 1);
});

test('Release with DEBUG in project.yml fails', () => {
  const yml = 'targets:\n  Bipe:\n    settings:\n      configs:\n        Release:\n          SWIFT_ACTIVE_COMPILATION_CONDITIONS: DEBUG BIPE\n        Debug:\n          SWIFT_ACTIVE_COMPILATION_CONDITIONS: DEBUG\n';
  const errors = checkProjectYml(yml);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /:6:/);
});

test('a debuggable release or a DEBUG buildConfigField fails', () => {
  assert.equal(checkGradleText('android/app/build.gradle.kts', 'release {\n  isDebuggable = true\n}\n').length, 1);
  assert.equal(checkGradleText('android/app/build.gradle.kts', 'buildConfigField("boolean", "DEBUG", "true")\n').length, 1);
});

test('the app passes the release gate', () => {
  assert.deepEqual(checkReleaseGate(REPO_ROOT).errors, []);
});

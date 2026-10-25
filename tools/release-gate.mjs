// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Release gate (bipe-chat/bipe#432, decision 1 of bipe-chat/bipe#430): a release build has no developer
// options and no unlock gesture. Developer-only paths read the build type at compile time (iOS
// `AppConfig.isDeveloperBuild` under `#if DEBUG`, Android `BuildConfig.DEBUG`), never a stored value, so
// nothing a user does or stored before an update opens them in a release build.
// Usage: node tools/release-gate.mjs [repo-root]
//
// Rules (Swift and Kotlin outside generated folders):
// - unlock-gesture: no `onTapGesture(count: N)` or `TapGesture(count: N)` with N of 3 or more, and no
//   tap or click counter (`tapCount`, `clickCount`, `...TapCounter`). A double tap stays allowed.
// - stored-switch: no string literal that names a developer switch (developer, dev mode, dev options,
//   debug menu, feature flag), unless the same line reads the build type (the registries gate
//   "flag:developer" next to `BuildConfig.DEBUG`).
// - settings-flags: a call `isShownOnIOS(flags:` or `isShownOnAndroid(flags` takes flags from the build type only:
//   the line reads `AppConfig.isDeveloperBuild` or `BuildConfig.DEBUG`.
// - build-type: `AppConfig.isDeveloperBuild = true` stands inside `#if DEBUG`.
// Build files:
// - ios/project.yml: no Release setting puts DEBUG in SWIFT_ACTIVE_COMPILATION_CONDITIONS.
// - android/**/build.gradle.kts: no `isDebuggable = true` and no buildConfigField named DEBUG.
//
// Limits: a line-based reading of the source. It does not follow values across lines or files.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { sourceFiles, walk, runCheck, isMain } from './lib.mjs';

const BUILD_TYPE = /AppConfig\.isDeveloperBuild|BuildConfig\.DEBUG/;
const SWITCH_WORDS = /developer|dev[ _-]?mode|dev[ _-]?options|debug[ _-]?menu|feature[ _-]?flag/i;
const STRING = /"(?:[^"\\]|\\.)*"/g;

function strip(line) {
  const i = line.indexOf('//');
  // A comment starts at `//` outside a string: good enough for the rules below, which need a code token.
  if (i < 0) return line;
  const before = line.slice(0, i);
  return (before.match(/"/g) || []).length % 2 === 0 ? before : line;
}

export function checkSourceText(path, text) {
  const errors = [];
  const lines = text.split('\n');
  let condition = [];
  lines.forEach((raw, index) => {
    const at = `${path}:${index + 1}`;
    const line = strip(raw);
    const trimmed = line.trim();
    if (path.endsWith('.swift')) {
      if (trimmed.startsWith('#if ')) condition.push(trimmed.slice(4).trim());
      else if (trimmed.startsWith('#else') && condition.length) condition[condition.length - 1] = `!(${condition.at(-1)})`;
      else if (trimmed.startsWith('#endif')) condition.pop();
    }
    const count = line.match(/(?:onTapGesture|TapGesture)\(\s*count:\s*(\d+)/);
    if (count && Number(count[1]) >= 3) {
      errors.push(`${at}: unlock-gesture: a ${count[1]}-tap gesture; release builds have no unlock gesture (bipe#432)`);
    }
    if (/\b\w*(tap|click)(Count|Counter)\b/i.test(line)) {
      errors.push(`${at}: unlock-gesture: a tap or click counter; release builds have no unlock gesture (bipe#432)`);
    }
    for (const literal of line.match(STRING) || []) {
      if (!SWITCH_WORDS.test(literal)) continue;
      if (BUILD_TYPE.test(line)) continue;
      errors.push(`${at}: stored-switch: ${literal} names a developer switch; gate on the build type instead (bipe#432)`);
    }
    if (/isShownOn(IOS|Android)\(\s*flags/.test(line) && !/\bfunc?\s/.test(line) && !BUILD_TYPE.test(line)) {
      errors.push(`${at}: settings-flags: settings flags come from the build type only (bipe#432)`);
    }
    if (/\bisDeveloperBuild\s*=\s*true\b/.test(line) && condition.at(-1) !== 'DEBUG') {
      errors.push(`${at}: build-type: isDeveloperBuild = true outside #if DEBUG (bipe#432)`);
    }
  });
  return errors;
}

// ios/project.yml: the Release configuration never gets the DEBUG condition.
export function checkProjectYml(text) {
  const errors = [];
  let releaseIndent = -1;
  text.split('\n').forEach((line, index) => {
    const indent = line.search(/\S/);
    if (indent < 0) return;
    if (releaseIndent >= 0 && indent <= releaseIndent) releaseIndent = -1;
    if (/^\s*Release\s*:/.test(line)) releaseIndent = indent;
    const releaseInline = /SWIFT_ACTIVE_COMPILATION_CONDITIONS\[config=Release\]/.test(line);
    if ((releaseIndent >= 0 || releaseInline) && /SWIFT_ACTIVE_COMPILATION_CONDITIONS/.test(line) && /\bDEBUG\b/.test(line)) {
      errors.push(`ios/project.yml:${index + 1}: build-type: Release sets DEBUG (bipe#432)`);
    }
  });
  return errors;
}

export function checkGradleText(path, text) {
  const errors = [];
  text.split('\n').forEach((line, index) => {
    if (/isDebuggable\s*=\s*true/.test(line)) errors.push(`${path}:${index + 1}: build-type: isDebuggable = true (bipe#432)`);
    if (/buildConfigField\([^)]*"DEBUG"/.test(line)) errors.push(`${path}:${index + 1}: build-type: buildConfigField DEBUG (bipe#432)`);
  });
  return errors;
}

export function checkReleaseGate(root) {
  const errors = [];
  const files = sourceFiles(root).filter((f) => f.startsWith('ios/') || f.startsWith('android/'));
  for (const f of files) errors.push(...checkSourceText(f, readFileSync(join(root, f), 'utf8')));
  try {
    errors.push(...checkProjectYml(readFileSync(join(root, 'ios/project.yml'), 'utf8')));
  } catch {
    // No iOS project yet.
  }
  for (const f of walk(root, 'android').filter((p) => p.endsWith('build.gradle.kts'))) {
    errors.push(...checkGradleText(f, readFileSync(join(root, f), 'utf8')));
  }
  return { errors, notes: [`${files.length} source files`] };
}

if (isMain(import.meta.url)) runCheck('release-gate', checkReleaseGate);

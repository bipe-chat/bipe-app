// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Licence allow-list: every resolved dependency must be in tools/licences.json
// with an allowed SPDX licence.
// Usage: node tools/licences.mjs [repo-root]
//
// Sources read:
// - SwiftPM: every ios/**/Package.resolved (format v1, v2 and v3). The key is
//   the pin identity (v2/v3) or the package name (v1), lower case.
// - Gradle: android/gradle/libs.versions.toml, section [libraries]. The key is
//   "group:name". Build plugins ([plugins]) do not ship in the app and are not
//   checked.
//
// ADR 0148: a dependency marked "scope": "test" may also have a licence in
// "testOnly" (EPL). It must then appear in Android Gradle files only on
// testImplementation lines.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { walk, runCheck, isMain, REPO_ROOT } from './lib.mjs';

export function swiftPins(text) {
  const json = JSON.parse(text);
  const pins = json.pins ?? json.object?.pins ?? [];
  return pins.map((p) => (p.identity ?? p.package ?? '').toLowerCase()).filter(Boolean);
}

// Reads the [libraries] table of a Gradle version catalog. Supports the three
// common forms:
//   a = "group:name:1.0"
//   a = { module = "group:name", version.ref = "x" }
//   a = { group = "group", name = "name", version = "1.0" }
export function catalogLibraries(text, { withAlias = false } = {}) {
  const out = [];
  let section = '';
  for (const raw of text.split('\n')) {
    const line = raw.replace(/#.*$/, '').trim();
    if (!line) continue;
    const header = line.match(/^\[([^\]]+)\]$/);
    if (header) {
      section = header[1].trim();
      continue;
    }
    if (section !== 'libraries') continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const alias = line.slice(0, eq).trim().replace(/^"|"$/g, '');
    const value = line.slice(eq + 1).trim();
    let coord;
    if (value.startsWith('"')) {
      const parts = value.slice(1, value.indexOf('"', 1)).split(':');
      coord = `${parts[0]}:${parts[1]}`;
    } else {
      const module = value.match(/\bmodule\s*=\s*"([^"]+)"/);
      const group = value.match(/\bgroup\s*=\s*"([^"]+)"/);
      const name = value.match(/\bname\s*=\s*"([^"]+)"/);
      if (module) coord = module[1];
      else if (group && name) coord = `${group[1]}:${name[1]}`;
    }
    if (coord) out.push(withAlias ? { coord, alias } : coord);
  }
  return out;
}

// Gradle accessor of a catalog alias: "a-b_c" is libs.a.b.c.
export function accessor(alias) {
  return `libs.${alias.replace(/[-_]/g, '.')}`;
}

// Lines of Android Gradle files that name `alias` outside testImplementation.
export function nonTestUses(root, alias) {
  const name = accessor(alias);
  const pattern = new RegExp(`${name.replace(/\./g, '\\.')}(?![\\w.])`);
  const uses = [];
  for (const file of walk(root, 'android').filter((f) => f.endsWith('.gradle.kts'))) {
    readFileSync(join(root, file), 'utf8').split('\n').forEach((line, i) => {
      if (pattern.test(line) && !/^\s*testImplementation\(/.test(line)) uses.push(`${file}:${i + 1}`);
    });
  }
  return uses;
}

export function dependencies(root) {
  const deps = [];
  for (const file of walk(root, 'ios').filter((f) => f.endsWith('/Package.resolved'))) {
    for (const name of swiftPins(readFileSync(join(root, file), 'utf8'))) deps.push({ name, file });
  }
  const catalog = 'android/gradle/libs.versions.toml';
  if (walk(root, 'android/gradle').includes(catalog)) {
    for (const { coord, alias } of catalogLibraries(readFileSync(join(root, catalog), 'utf8'), { withAlias: true })) {
      deps.push({ name: coord, file: catalog, alias });
    }
  }
  return deps;
}

export function checkLicences(root, table = loadTable()) {
  const deps = dependencies(root);
  const errors = [];
  const warnings = [];
  if (!deps.length) return { notes: ['no dependencies yet'] };
  const allowed = new Set(table.allowed);
  const testOnly = new Set(table.testOnly ?? []);
  for (const { name, file, alias } of deps) {
    const entry = table.packages[name];
    const isTest = entry?.scope === 'test';
    if (!entry) {
      errors.push(`${name} (${file}) is not in tools/licences.json. Add its SPDX licence, source URL and the date you checked it.`);
    } else if (isTest && alias && nonTestUses(root, alias).length) {
      errors.push(`${name} has "scope": "test" but ${nonTestUses(root, alias).join(', ')} uses it outside testImplementation (ADR 0148).`);
    } else if (!allowed.has(entry.licence) && !(isTest && testOnly.has(entry.licence))) {
      errors.push(`${name} (${file}) has licence ${entry.licence}, which is not allowed. Allowed: ${table.allowed.join(', ')}. Another licence needs an ADR.`);
    } else if (entry.unverified) {
      warnings.push(`${name} (${file}) licence is unverified: ${entry.note ?? entry.source}`);
    }
  }
  return { errors, warnings, notes: [`checked ${deps.length} dependenc${deps.length === 1 ? 'y' : 'ies'}`] };
}

export function loadTable(path = join(REPO_ROOT, 'tools/licences.json')) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

if (isMain(import.meta.url)) runCheck('licences', (root) => checkLicences(root));

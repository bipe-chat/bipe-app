// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Every settings row that opens or changes something has a handler on each platform where it shows
// (bipe-chat/bipe#676). Reads the generated BipeSettings.swift (the registry, bipe-shared registries/settings.json)
// and looks for the row in the settings code: `.caseName` in ios/Bipe/Settings, `BipeSettingsRow.CASE_NAME` in the Android
// settings package. A use elsewhere (reading the stored value) is not a handler.
// Rows of kind url (generic page), info (a value) and destructive (the generic tap) need no handler.
// Run: node tools/settings-rows.mjs

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(fileURLToPath(import.meta.url), '..', '..');
const GENERATED = 'ios/BipeKit/Sources/BipeKit/Generated/BipeSettings.swift';
const NEEDS_HANDLER = new Set(['link', 'choice', 'action', 'switch']);

/** The rows of the generated Swift: name, kind and gate. */
export function parseRows(swift) {
  const names = [...swift.matchAll(/^\s*case (\w+) = "[\w.]+"$/gm)].map((m) => m[1]);
  const property = (name) => {
    const start = swift.indexOf(`public var ${name}:`);
    const body = swift.slice(start, swift.indexOf('\n    }\n', start));
    return Object.fromEntries([...body.matchAll(/case \.(\w+): (?:\.(\w+)|"([^"]*)"|nil)/g)].map((m) => [m[1], m[2] ?? m[3] ?? null]));
  };
  const kinds = property('kind');
  const gates = property('gate');
  return names.map((name) => ({ name, kind: kinds[name], gate: gates[name] ?? null }));
}

export const kotlinName = (name) => name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();

function sources(dir, extension, out = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (!/^generated$/i.test(entry)) sources(path, extension, out);
    } else if (entry.endsWith(extension)) {
      out.push(readFileSync(path, 'utf8'));
    }
  }
  return out;
}

/** The rows that show on a platform and need a handler but have none there. */
export function missingHandlers(rows, iosCode, androidCode) {
  const missing = [];
  for (const row of rows) {
    if (!NEEDS_HANDLER.has(row.kind)) continue;
    if (row.gate && !['ios', 'android'].includes(row.gate)) continue;
    if (row.gate !== 'android' && !new RegExp(`\\.${row.name}\\b`).test(iosCode)) missing.push(`ios ${row.name}`);
    if (row.gate !== 'ios' && !iosOnly(row) && !new RegExp(`BipeSettingsRow\\.${kotlinName(row.name)}\\b`).test(androidCode)) {
      missing.push(`android ${kotlinName(row.name)}`);
    }
  }
  return missing;
}

const iosOnly = (row) => row.gate === 'ios';

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const rows = parseRows(readFileSync(join(ROOT, GENERATED), 'utf8'));
  const iosCode = sources(join(ROOT, 'ios/Bipe/Settings'), '.swift').join('\n');
  const androidCode = sources(join(ROOT, 'android/app/src/main/kotlin/chat/bipe/app/settings'), '.kt').join('\n');
  const missing = missingHandlers(rows, iosCode, androidCode);
  console.log(`settings-rows: checked ${rows.length} rows (${relative(ROOT, join(ROOT, GENERATED))})`);
  for (const line of missing) console.log(`::error title=settings-rows::${line} has no handler`);
  console.log(missing.length ? `settings-rows: FAIL (${missing.length})` : 'settings-rows: OK');
  process.exit(missing.length ? 1 : 0);
}

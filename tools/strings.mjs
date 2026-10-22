// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Strings: UI text comes from the generated bipe-shared strings, not from
// string literals in Swift or Kotlin UI code.
// Usage: node tools/strings.mjs [repo-root]
//
// Flags a string literal passed directly to:
// - Swift: Text("..."), Button("..."), Label("..."), .navigationTitle("...")
// - Kotlin (Compose): Text("..."), text = "..."
// Allowed: empty strings, and strings made only of symbols, punctuation and
// whitespace after interpolations are removed (e.g. "•", "\(count)", "$count").
//
// Limits: this is a line-based regex check, not a parser.
// - It reads one line at a time: a call split over lines between "(" and the
//   string is not seen. Multi-line ("""...""") and raw (#"..."#) strings are
//   not seen.
// - It does not follow values: Text(title) where title = "Hello" passes.
// - Other text APIs (Toggle, Section, accessibilityLabel, contentDescription,
//   placeholder, ...) are not checked.
// - It skips lines that start with // and the rest of a line after " //", so
//   a string literal that contains " //" can hide a match.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { sourceFiles, runCheck, isMain } from './lib.mjs';

const STR = '"((?:[^"\\\\]|\\\\.)*)"';

export const PATTERNS = {
  swift: [
    new RegExp(`(?<![\\w.])(Text|Button|Label)\\(\\s*${STR}`, 'g'),
    new RegExp(`\\.(navigationTitle)\\(\\s*${STR}`, 'g'),
  ],
  kt: [new RegExp(`(?<![\\w.])(Text)\\(\\s*${STR}`, 'g'), new RegExp(`(?<![\\w.])(text)\\s*=\\s*${STR}`, 'g')],
};

// True when the literal holds text a user reads.
export function isUserText(literal) {
  const stripped = literal
    .replace(/\\\((?:[^()]|\([^()]*\))*\)/g, '') // Swift \(expr)
    .replace(/\$\{[^}]*\}/g, '') // Kotlin ${expr}
    .replace(/\$[A-Za-z_][A-Za-z0-9_]*/g, '') // Kotlin $name
    .replace(/\\[nrt"\\0]/g, ''); // escapes
  return /[\p{L}\p{N}]/u.test(stripped);
}

export function findLiterals(text, kind) {
  const hits = [];
  text.split('\n').forEach((raw, i) => {
    if (/^\s*\/\//.test(raw)) return;
    const line = raw.replace(/\s\/\/.*$/, '');
    for (const re of PATTERNS[kind]) {
      re.lastIndex = 0;
      for (const m of line.matchAll(re)) {
        if (isUserText(m[2])) hits.push({ line: i + 1, call: m[1], literal: m[2] });
      }
    }
  });
  return hits;
}

export function checkStrings(root) {
  const files = sourceFiles(root);
  const errors = [];
  for (const file of files) {
    const kind = file.endsWith('.swift') ? 'swift' : 'kt';
    for (const hit of findLiterals(readFileSync(join(root, file), 'utf8'), kind)) {
      errors.push(`${file}:${hit.line} ${hit.call}("${hit.literal}") uses a string literal. Use a generated bipe-shared string.`);
    }
  }
  return { errors, notes: [files.length ? `checked ${files.length} file(s)` : 'no Swift or Kotlin files yet'] };
}

if (isMain(import.meta.url)) runCheck('strings', checkStrings);

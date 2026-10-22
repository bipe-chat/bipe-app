// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Headers: every .swift and .kt file outside folders named Generated or
// generated starts with the Bipe header from AGENTS.md. A SwiftPM manifest
// (Package.swift) must start with its "// swift-tools-version" line, so there
// the header starts on the second line.
// Usage: node tools/headers.mjs [repo-root]

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { sourceFiles, runCheck, isMain } from './lib.mjs';

export const HEADER = [
  '// SPDX-License-Identifier: AGPL-3.0-only',
  '// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)',
  '// Copyright 2026 Bipe contributors',
];

export function hasHeader(text) {
  const lines = text.split(/\r?\n/, HEADER.length + 1);
  const start = lines[0].startsWith('// swift-tools-version') ? 1 : 0;
  return HEADER.every((h, i) => lines[start + i] === h);
}

export function checkHeaders(root) {
  const files = sourceFiles(root);
  const errors = files
    .filter((f) => !hasHeader(readFileSync(join(root, f), 'utf8')))
    .map((f) => `${f} does not start with the Bipe header (see AGENTS.md, "Licence and file header")`);
  return { errors, notes: [files.length ? `checked ${files.length} file(s)` : 'no Swift or Kotlin files yet'] };
}

if (isMain(import.meta.url)) runCheck('headers', checkHeaders);

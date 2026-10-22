// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Shared helpers for the licence gates in tools/. Node built-ins only.

import { readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));

// Folders that no check walks into.
const SKIPPED_DIRS = new Set(['.git', 'node_modules', '.build', 'build', '.gradle', 'DerivedData', 'Pods']);

// Folders that hold files made by bipe-shared tools/gen.mjs. Those files carry
// their own header and their own strings.
export const GENERATED_DIRS = new Set(['Generated', 'generated']);

// Returns every file below `dir` as a path relative to `root`, with forward
// slashes. A missing `dir` returns an empty list.
export function walk(root, dir = '.', { skipGenerated = false } = {}) {
  const out = [];
  const start = join(root, dir);
  let stat;
  try {
    stat = statSync(start);
  } catch {
    return out;
  }
  if (!stat.isDirectory()) return out;
  const stack = [start];
  while (stack.length) {
    const current = stack.pop();
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) {
        if (SKIPPED_DIRS.has(entry.name)) continue;
        if (skipGenerated && GENERATED_DIRS.has(entry.name)) continue;
        stack.push(full);
      } else if (entry.isFile()) {
        out.push(relative(root, full).split(sep).join('/'));
      }
    }
  }
  return out.sort();
}

// Swift and Kotlin source files outside generated folders.
export function sourceFiles(root) {
  return walk(root, '.', { skipGenerated: true }).filter((f) => f.endsWith('.swift') || f.endsWith('.kt'));
}

// Runs `check(root)` and prints its result. A check returns
// { errors: string[], warnings: string[], notes: string[] }.
// Errors print as GitHub annotations and exit 1.
export function runCheck(name, check) {
  const root = process.argv[2] ?? REPO_ROOT;
  const { errors = [], warnings = [], notes = [] } = check(root);
  for (const n of notes) console.log(`${name}: ${n}`);
  for (const w of warnings) console.log(`::warning title=${name}::${w}`);
  for (const e of errors) console.log(`::error title=${name}::${e}`);
  if (errors.length) {
    console.log(`${name}: FAIL (${errors.length} error${errors.length === 1 ? '' : 's'})`);
    process.exit(1);
  }
  console.log(`${name}: OK`);
}

export function isMain(importMetaUrl) {
  return process.argv[1] && fileURLToPath(importMetaUrl) === process.argv[1];
}

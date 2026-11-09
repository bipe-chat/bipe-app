// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Deny-list: fails when a dependency manifest names an Element package.
// Usage: node tools/deny-list.mjs [repo-root]

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { walk, runCheck, isMain } from './lib.mjs';

// Matched case-insensitively as substrings. `compound-design-tokens` also
// covers bipe-chat/compound-design-tokens.
export const DENIED = [
  'element-x-ios',
  'element-x-android',
  'compound-ios',
  'compound-android',
  'compound-design-tokens',
  'matrix-rich-text-editor',
  'element-call',
  'element-hq/',
  'io.element',
  // Web packages (bipe-chat/bipe#1127): Element Web's design system and its
  // former React SDK.
  'compound-web',
  '@vector-im/',
  'matrix-react-sdk',
];

// Dependency manifests. A file that does not exist passes.
export function manifests(root) {
  const ios = [...walk(root, 'ios'), ...walk(root, 'macos')].filter(
    (f) => f.endsWith('/project.yml') || f.endsWith('/Package.swift') || f.endsWith('/Package.resolved'),
  );
  const android = walk(root, 'android').filter(
    (f) => f.endsWith('.gradle.kts') || f.endsWith('.gradle') || f === 'android/gradle/libs.versions.toml',
  );
  // The web client's manifest (bipe-chat/bipe#1127). Its lockfile is not
  // read: a transitive name there is not a choice this repo made.
  const web = walk(root, 'web').filter((f) => f === 'web/package.json');
  return [...ios, ...android, ...web];
}

export function checkDenyList(root) {
  const files = manifests(root);
  const errors = [];
  for (const file of files) {
    const lines = readFileSync(join(root, file), 'utf8').split('\n');
    lines.forEach((line, i) => {
      const lower = line.toLowerCase();
      for (const name of DENIED) {
        if (lower.includes(name)) {
          errors.push(`${file}:${i + 1} names denied package "${name}": ${line.trim()}`);
        }
      }
    });
  }
  const notes = [files.length ? `checked ${files.length} manifest(s)` : 'no dependency manifests yet'];
  return { errors, notes };
}

if (isMain(import.meta.url)) runCheck('deny-list', checkDenyList);

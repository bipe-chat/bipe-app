// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// SDK version: iOS and Android use the same matrix-rust-sdk release. The file
// sdk-version at the repo root holds it. Each platform pin must be equal:
// - ios/project.yml: exactVersion of the matrix-rust-components-swift package.
// - ios/Package.resolved: version of the matrix-rust-components-swift pin.
// - android/gradle/libs.versions.toml: [versions] matrix-rust-sdk.
// Usage: node tools/sdk-version.mjs [repo-root]

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { runCheck, isMain } from './lib.mjs';

export const SWIFT_PACKAGE = 'matrix-rust-components-swift';

function read(root, file) {
  try {
    return readFileSync(join(root, file), 'utf8');
  } catch {
    return null;
  }
}

// exactVersion in the project.yml package entry whose url names the Swift package.
export function projectPin(text) {
  const blocks = text.split(/\n(?=  \S)/);
  for (const block of blocks) {
    if (!block.includes(SWIFT_PACKAGE)) continue;
    const m = block.match(/\bexactVersion:\s*["']?([^"'\s]+)["']?/);
    if (m) return m[1];
  }
  return null;
}

export function resolvedPin(text) {
  const json = JSON.parse(text);
  const pins = json.pins ?? json.object?.pins ?? [];
  const pin = pins.find((p) => (p.identity ?? p.package ?? '').toLowerCase() === SWIFT_PACKAGE);
  return pin?.state?.version ?? null;
}

export function catalogPin(text) {
  let section = '';
  for (const raw of text.split('\n')) {
    const line = raw.replace(/#.*$/, '').trim();
    const header = line.match(/^\[([^\]]+)\]$/);
    if (header) {
      section = header[1].trim();
      continue;
    }
    if (section !== 'versions') continue;
    const m = line.match(/^matrix-rust-sdk\s*=\s*"([^"]+)"/);
    if (m) return m[1];
  }
  return null;
}

export function checkSdkVersion(root) {
  const expected = read(root, 'sdk-version')?.trim();
  if (!expected) return { errors: ['sdk-version is missing or empty. It holds the matrix-rust-sdk release for both platforms.'] };
  const sources = [
    ['ios/project.yml', projectPin, `exactVersion of the ${SWIFT_PACKAGE} package`],
    ['ios/Package.resolved', resolvedPin, `version of the ${SWIFT_PACKAGE} pin`],
    ['android/gradle/libs.versions.toml', catalogPin, '[versions] matrix-rust-sdk'],
  ];
  const errors = [];
  for (const [file, parse, what] of sources) {
    const text = read(root, file);
    if (text === null) {
      errors.push(`${file} is missing.`);
      continue;
    }
    const found = parse(text);
    if (found !== expected) errors.push(`${file}: ${what} is ${found ?? 'not set'}, sdk-version is ${expected}.`);
  }
  return { errors, notes: [`sdk-version ${expected}`] };
}

if (isMain(import.meta.url)) runCheck('sdk-version', checkSdkVersion);

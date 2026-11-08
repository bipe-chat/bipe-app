// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

test('Mac CI watches every Swift source shared from the iOS app manifest', () => {
  const project = readFileSync(new URL('../macos/project.yml', import.meta.url), 'utf8');
  const workflow = readFileSync(new URL('../.github/workflows/macos.yml', import.meta.url), 'utf8');
  const sources = [...project.matchAll(/^\s+- path: \.\.\/(ios\/[^\n]+\.swift)\s*$/gm)].map((match) => match[1]);
  assert.ok(sources.length > 0);
  const filters = [...workflow.matchAll(/paths:\s*(\[[^\n]+\])/g)].map((match) => JSON.parse(match[1]));
  assert.equal(filters.length, 2, 'PR and main-push filters must both cover the Mac inputs');
  for (const source of sources) {
    for (const filter of filters) {
      assert.ok(filter.some((path) => path.endsWith('/**') ? source.startsWith(path.slice(0, -2)) : source === path),
        `Mac CI is missing shared source ${source}`);
    }
  }
});

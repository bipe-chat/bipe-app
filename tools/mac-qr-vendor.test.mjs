// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

test('the native QR encoder matches its pinned MIT upstream sources', () => {
  const base = new URL('../macos/QRCode/', import.meta.url);
  const metadata = JSON.parse(readFileSync(new URL('provenance.json', base), 'utf8'));
  assert.equal(metadata.licence, 'MIT');
  for (const [file, hash] of Object.entries(metadata.sha256)) {
    assert.equal(createHash('sha256').update(readFileSync(new URL(file, base))).digest('hex'), hash, file);
  }
  assert.match(readFileSync(new URL('Nayuki-LICENSE.txt', base), 'utf8'), /Permission is hereby granted, free of charge/);
});

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { imageSize } from './store-captures-ios.mjs';

test('store captures iOS: the image size comes from the JPEG SOF marker or the PNG IHDR', () => {
  const sof = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x00, 0x00, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x01, 0x00, 0x02, 0x00, 0x03]);
  assert.deepEqual(imageSize(sof), { width: 512, height: 256 });
  const png = Buffer.alloc(33);
  png.write('\x89PNG\r\n\x1a\n', 0, 'latin1');
  png.write('IHDR', 12, 'latin1');
  png.writeUInt32BE(640, 16);
  png.writeUInt32BE(624, 20);
  assert.deepEqual(imageSize(png), { width: 640, height: 624 });
  assert.equal(imageSize(Buffer.from([0x89, 0x50])), null);
});

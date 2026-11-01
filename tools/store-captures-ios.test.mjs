// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { jpegSize } from './store-captures-ios.mjs';
import { kotlin, messagePhotos } from './store-capture-photos.mjs';

test('store captures iOS: the JPEG size comes from the SOF marker', () => {
  const sof = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x00, 0x00, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x01, 0x00, 0x02, 0x00, 0x03]);
  assert.deepEqual(jpegSize(sof), { width: 512, height: 256 });
  assert.equal(jpegSize(Buffer.from([0x89, 0x50])), null);
});

test('store captures Android: message photos follow copyOf and fail without a cast photo', () => {
  const cast = new Map([
    ['rafa', { id: 'rafa', photo: { style: 'pet' } }],
    ['falsa', { id: 'falsa', photo: { copyOf: 'rafa' } }],
    ['thiago', { id: 'thiago', photo: null }],
  ]);
  const data = (m) => ({ captures: { stickers: { messages: [m] } } });
  assert.deepEqual(messagePhotos(data({ sticker: 'falsa' }), cast), ['rafa']);
  assert.throws(() => messagePhotos(data({ image: 'thiago' }), cast), /no cast photo/);
  assert.match(kotlin([{ id: 'rafa', base64: 'AAAA' }]), /"rafa" to "AAAA",/);
});

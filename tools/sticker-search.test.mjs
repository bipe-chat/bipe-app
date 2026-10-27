// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// ADR 0205 rule 3 (bipe-chat/bipe#839): sticker search runs on the phone. The search files must not reach the network.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const files = [
  'ios/BipeKit/Sources/BipeKit/Stickers/StickerSearch.swift',
  'android/libraries/bipe/src/main/kotlin/chat/bipe/stickers/StickerSearch.kt',
];
const network = /\b(URLSession|URLRequest|HttpURLConnection|OkHttp|StickerPackClient|java\.net|Ktor|https?:\/\/)/;

for (const file of files) {
  test(`${file} has no network code`, () => {
    const source = readFileSync(new URL(`../${file}`, import.meta.url), 'utf8');
    assert.doesNotMatch(source, network);
  });
}

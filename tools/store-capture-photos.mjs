// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Store captures, Android (bipe-chat/bipe#457): writes the cast photos that a capture message shows (`image` and
// `sticker` in bipe-shared product/store-captures.json) into StorePhotos.kt as Base64 JPEG. The screenshotTest source
// set has no resource folder, and the previews load no media through the SDK. Avatars stay initials.
// Usage: node tools/store-capture-photos.mjs <bipe-shared> <cast-photos-dir>

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { isMain, REPO_ROOT } from './lib.mjs';

export const PHOTOS_PATH = 'android/app/src/screenshotTest/kotlin/chat/bipe/app/store/photos/StorePhotos.kt';

/** The cast photo ids that capture messages show, sorted. */
export function messagePhotos(data, cast) {
  const ids = new Set();
  for (const capture of Object.values(data.captures)) {
    for (const m of capture.messages ?? []) {
      for (const id of [m.image, m.sticker].filter(Boolean)) {
        const e = cast.get(id);
        if (!e?.photo) throw new Error(`store-captures.json: ${id} has no cast photo`);
        ids.add(e.photo.copyOf ?? e.id);
      }
    }
  }
  return [...ids].sort();
}

export function kotlin(photos) {
  const rows = photos.map(({ id, base64 }) => `        "${id}" to "${base64}",`);
  return `// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Written by tools/store-capture-photos.mjs from bipe-web src/cast/photos. Do not edit.

package chat.bipe.app.store.photos

/** Base64 JPEG of the cast photos that the store-capture messages show, by cast photo id. */
object StorePhotos {
    val jpeg: Map<String, String> = mapOf(
${rows.join('\n')}
    )
}
`;
}

function main([shared, dir]) {
  if (!shared || !dir) {
    console.error('usage: node tools/store-capture-photos.mjs <bipe-shared> <cast-photos-dir>');
    return 2;
  }
  const data = JSON.parse(readFileSync(join(shared, 'product/store-captures.json'), 'utf8'));
  const cast = new Map(JSON.parse(readFileSync(join(shared, 'product/cast.json'), 'utf8')).cast.map((e) => [e.id, e]));
  const photos = messagePhotos(data, cast).map((id) => ({ id, base64: readFileSync(join(dir, `${id}.jpg`)).toString('base64') }));
  for (const p of photos) if (p.base64.length > 65000) throw new Error(`${p.id}.jpg is too large for a Kotlin string`);
  writeFileSync(join(REPO_ROOT, PHOTOS_PATH), kotlin(photos));
  console.log(`store-capture-photos: ${photos.map((p) => p.id).join(', ')}`);
  return 0;
}

if (isMain(import.meta.url)) process.exitCode = main(process.argv.slice(2));

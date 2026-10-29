// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Store captures (bipe-chat/bipe#457): copies the Android capture previews that the Compose Preview
// Screenshot Testing plugin rendered (`./gradlew :app:updatePlayDebugScreenshotTest`) into the layout of the
// bipe-web store image builder: <out>/play-phone/light/<locale>/<slug>.png. The previews are in
// android/app/src/screenshotTest/kotlin/chat/bipe/app/store/StoreCaptureTests.kt, named
// Store<Slug in camel case><PtBR|EnUS>. The slugs come from the generated StoreCaptures.kt (bipe-shared
// product/appstore.md and product/store-captures.json).
// Usage: node tools/store-captures.mjs <reference-dir> <out-dir> [--partial]
//   Fails on a capture that is not 1080x1920, and on a slug with no capture unless --partial is given.

import { copyFileSync, mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { isMain, REPO_ROOT } from './lib.mjs';

export const WIDTH = 1080;
export const HEIGHT = 1920;
export const LOCALES = { PtBR: 'pt-BR', EnUS: 'en-US' };
const GENERATED = 'android/app/src/screenshotTest/kotlin/chat/bipe/app/store/generated/StoreCaptures.kt';

const camel = (slug) => slug.split('-').map((w) => w[0].toUpperCase() + w.slice(1)).join('');

/** The `slugs` and `captured` lists of the generated StoreCaptures.kt. */
export function generatedSlugs(text) {
  const list = (name) => {
    const m = text.match(new RegExp(`val ${name}: List<String> = listOf\\(([^)]*)\\)`));
    if (!m) throw new Error(`StoreCaptures.kt has no ${name} list`);
    return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
  };
  return { slugs: list('slugs'), captured: list('captured') };
}

/** Width and height of a PNG from its IHDR chunk. */
export function pngSize(buf) {
  if (buf.length < 24 || buf.toString('latin1', 1, 4) !== 'PNG') throw new Error('not a PNG');
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

/** Maps reference images to slugs and locales. Returns { copies: [{ from, slug, locale }], missing, problems }. */
export function plan(paths, slugs, { read = readFileSync } = {}) {
  const bySlug = new Map(slugs.map((s) => [camel(s), s]));
  const found = new Map();
  const problems = [];
  for (const from of paths.filter((p) => p.endsWith('.png'))) {
    const m = from.match(/\.Store([A-Za-z]+?)(PtBR|EnUS)_[^/\\]*\.png$/);
    const slug = m && bySlug.get(m[1]);
    if (!slug) continue;
    const locale = LOCALES[m[2]];
    const key = `${slug} ${locale}`;
    if (found.has(key)) problems.push(`two captures for ${key}: ${found.get(key).from} and ${from}`);
    const { width, height } = pngSize(read(from));
    if (width !== WIDTH || height !== HEIGHT) problems.push(`${from} is ${width}x${height}, the Play phone set is ${WIDTH}x${HEIGHT}`);
    found.set(key, { from, slug, locale });
  }
  const missing = slugs.flatMap((slug) => Object.values(LOCALES).filter((l) => !found.has(`${slug} ${l}`)).map((l) => `${slug} ${l}`));
  return { copies: [...found.values()], missing, problems };
}

function main([reference, out, flag]) {
  if (!reference || !out || (flag && flag !== '--partial')) {
    console.error('usage: node tools/store-captures.mjs <reference-dir> <out-dir> [--partial]');
    return 2;
  }
  const { slugs } = generatedSlugs(readFileSync(join(REPO_ROOT, GENERATED), 'utf8'));
  const { copies, missing, problems } = plan(files(reference), slugs);
  for (const p of problems) console.error(`::error::${p}`);
  for (const m of missing) console.log(`${flag ? '::warning::' : '::error::'}no capture for ${m}`);
  if (problems.length || (missing.length && !flag)) return 1;
  for (const { from, slug, locale } of copies) {
    const dir = join(out, 'play-phone', 'light', locale);
    mkdirSync(dir, { recursive: true });
    copyFileSync(from, join(dir, `${slug}.png`));
    console.log(`play-phone/light/${locale}/${slug}.png`);
  }
  return 0;
}

if (isMain(import.meta.url)) process.exit(main(process.argv.slice(2)));

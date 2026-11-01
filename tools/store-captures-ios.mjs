// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Store captures, iOS (bipe-chat/bipe#457): writes the input of the UI test BipeStoreCapturesTests, one JSON file
// per slug and locale (<out>/<locale>/<slug>.json), from bipe-shared product/store-captures.json and cast.json.
// The app decodes it in ios/Bipe/StoreCapture/StoreCapture.swift. People carry their name, handle and photo; a
// photo is an mxc URL that `files` maps to a cast photo on disk (bipe-web src/cast/photos), which the Debug app reads
// in the simulator. The BR Code comes from bipe-shared tools/lib/pix.mjs, as on Android.
// Usage: node tools/store-captures-ios.mjs <bipe-shared> <cast-photos-dir> <out-dir>

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isMain } from './lib.mjs';

export const LOCALES = ['pt-BR', 'en-US'];

/** Width and height of a JPEG from its first SOF marker, or null. */
export function jpegSize(buf) {
  if (buf[0] !== 0xff || buf[1] !== 0xd8) return null;
  for (let i = 2; i + 9 < buf.length;) {
    if (buf[i] !== 0xff) return null;
    const marker = buf[i + 1];
    const length = buf.readUInt16BE(i + 2);
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
    }
    i += 2 + length;
  }
  return null;
}

/** The inputs of every slug of store-captures.json: [{ slug, input }]. `photos` is the cast photo folder. */
export function inputs({ data, cast }, photos, buildBrCode) {
  const files = {};
  const sizes = {};
  const photoUrl = (photo) => {
    const path = resolve(photos, `${photo}.jpg`);
    if (!existsSync(path)) throw new Error(`no cast photo ${path}`);
    const url = `mxc://store-capture.invalid/${photo}`;
    files[url] = path;
    sizes[url] = jpegSize(readFileSync(path));
    return url;
  };
  const photoOf = (e) => (e.photo ? e.photo.copyOf ?? e.id : null);
  const person = (key) => {
    const e = cast.get(key === 'viewer' ? data.viewer : key);
    if (!e) throw new Error(`unknown cast id ${key}`);
    const photo = photoOf(e);
    return { id: e.id, name: e.name, short: e.short ?? e.name, handle: e.handle ?? null, isCompany: e.kind === 'institution', avatar: photo ? photoUrl(photo) : null };
  };
  const media = (id) => photoUrl(photoOf(cast.get(id)) ?? id);
  const message = (m) => {
    const image = m.image ? media(m.image) : null;
    const sticker = m.sticker ? media(m.sticker) : null;
    const size = sizes[image ?? sticker] ?? null;
    return { from: m.from === 'viewer' ? null : person(m.from), time: m.time, text: m.text ?? null, image, sticker, width: size?.width ?? null, height: size?.height ?? null };
  };
  return Object.entries(data.captures).map(([slug, c]) => {
    const input = { slug, viewer: person('viewer'), files };
    if (slug === 'aviso-golpe' || slug === 'stickers') Object.assign(input, { chat: person(c.chat), messages: c.messages.map(message) });
    else if (slug === 'pedidos') input.requests = c.requests.map((r) => ({ from: person(r.from), trust: r.trust }));
    else if (slug === 'conferir-pix') input.pixCode = buildBrCode(c.pix);
    else if (slug === 'seu-arroba') input.owner = person(c.owner);
    else if (slug === 'selo-cnpj') {
      Object.assign(input, {
        company: person(c.company),
        legalName: c.legalName,
        cnpj: cast.get(c.company).cnpj.replace(/[^0-9A-Z]/g, ''),
        checkedMonth: c.checkedMonth,
        messages: c.messages.map(message),
      });
    } else if (slug === 'grupos') {
      input.groups = c.groups.map((g) => ({ group: person(g.group), from: person(g.from), time: g.time, text: g.text, unread: g.unread ?? 0, mentions: g.mentions ?? 0, muted: g.muted === true }));
    } else throw new Error(`store-captures.json: no iOS scene for ${slug}`);
    return { slug, input };
  });
}

async function main([shared, photos, out]) {
  if (!shared || !photos || !out) {
    console.error('usage: node tools/store-captures-ios.mjs <bipe-shared> <cast-photos-dir> <out-dir>');
    return 2;
  }
  const { buildBrCode } = await import(pathToFileURL(join(resolve(shared), 'tools/lib/pix.mjs')).href);
  const data = JSON.parse(readFileSync(join(shared, 'product/store-captures.json'), 'utf8'));
  const cast = new Map(JSON.parse(readFileSync(join(shared, 'product/cast.json'), 'utf8')).cast.map((e) => [e.id, e]));
  const list = inputs({ data, cast }, photos, buildBrCode);
  for (const locale of LOCALES) {
    mkdirSync(join(out, locale), { recursive: true });
    for (const { slug, input } of list) writeFileSync(join(out, locale, `${slug}.json`), `${JSON.stringify(input, null, 2)}\n`);
  }
  console.log(`store-captures-ios: ${list.length} scenes x ${LOCALES.length} locales in ${out}`);
  return 0;
}

if (isMain(import.meta.url)) process.exitCode = await main(process.argv.slice(2));

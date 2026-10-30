// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Google Play Developer API steps of the store release (bipe-chat/bipe#604, docs/client-switch-plan.md
// step 4). Node built-ins only. Request shapes: Google Play Developer API v3 reference (edits,
// edits.bundles, edits.apks, edits.tracks).
// Usage:
//   node tools/play.mjs check <package> <version-code>   Fails unless <version-code> is higher than
//                                                        every bundle and APK uploaded for <package>
//                                                        (the fork's included). Changes nothing.
//   node tools/play.mjs upload <package> <aab> <track>   Uploads the AAB to a new edit, puts it on
//                                                        <track> (internal only) and commits the edit.
//   node tools/play.mjs listing <package> --dry-run      Prints the store listing (bipe-chat/bipe#457) for
//                                                        pt-BR and en-US: title, short and full description
//                                                        from bipe-shared product/appstore.md, the phone
//                                                        panels and the feature graphic from the bipe-web
//                                                        store images. Needs no key and sends nothing.
//   node tools/play.mjs listing <package>                In one edit: edits.listings.update per locale,
//                                                        images deleteall and upload for phoneScreenshots
//                                                        and featureGraphic, then commit. store-release.yml
//                                                        runs it behind BIPE_SWITCH_READY.
// Environment: PLAY_SERVICE_ACCOUNT_PATH (path of the service account JSON key). listing also reads
// BIPE_SHARED (bipe-shared clone, default ../bipe-shared) and STORE_IMAGES (the bipe-web output root
// out/appstore, default ../bipe-web/out/appstore).
// The script never prints the key or the token.

import { createPrivateKey, sign } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isMain, REPO_ROOT } from './lib.mjs';

export const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3';
export const UPLOAD_API = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3';
export const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
// The switch goes to the internal track first (docs/client-switch-plan.md step 5). Other tracks are
// rollout decisions (bipe-chat/bipe#605) and stay in the Play Console.
export const TRACKS = ['internal'];

const b64url = (buf) => Buffer.from(buf).toString('base64url');

// RS256 assertion for the OAuth 2.0 JWT bearer grant, valid 1 hour (Google's maximum).
export function assertion({ clientEmail, privateKey, tokenUri, now = Math.floor(Date.now() / 1000) }) {
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ iss: clientEmail, scope: SCOPE, aud: tokenUri, iat: now, exp: now + 3600 }));
  const input = `${header}.${payload}`;
  return `${input}.${b64url(sign('sha256', Buffer.from(input), createPrivateKey(privateKey)))}`;
}

export function readServiceAccount(path) {
  const key = JSON.parse(readFileSync(path, 'utf8'));
  if (key.type !== 'service_account' || !key.client_email || !key.private_key) {
    throw new Error('PLAY_SERVICE_ACCOUNT_PATH is not a service account JSON key');
  }
  return { clientEmail: key.client_email, privateKey: key.private_key, tokenUri: key.token_uri ?? 'https://oauth2.googleapis.com/token' };
}

export async function accessToken(account, fetchImpl = fetch) {
  const res = await fetchImpl(account.tokenUri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: assertion(account) }).toString(),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok || !json.access_token) throw new Error(`token request failed: ${res.status} ${json.error ?? ''}`.trim());
  return json.access_token;
}

export function client(token, fetchImpl = fetch) {
  return async function call(method, url, { body, media, type = 'application/octet-stream' } = {}) {
    const headers = { Authorization: `Bearer ${token}` };
    let payload;
    if (media) {
      headers['Content-Type'] = type;
      payload = media;
    } else if (body) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetchImpl(url, { method, headers, body: payload });
    const text = await res.text();
    const json = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const err = new Error(`${method} ${url.replace(/\?.*/, '')} failed: ${res.status} ${json.error?.message ?? ''}`.trim());
      err.status = res.status;
      throw err;
    }
    return json;
  };
}

const app = (pkg) => `${API}/applications/${encodeURIComponent(pkg)}`;

/** The highest versionCode Play holds for the package: every uploaded bundle and APK, on any track or none. */
export async function highestVersionCode(call, pkg) {
  const edit = await call('POST', `${app(pkg)}/edits`, { body: {} });
  try {
    const bundles = await call('GET', `${app(pkg)}/edits/${edit.id}/bundles`);
    const apks = await call('GET', `${app(pkg)}/edits/${edit.id}/apks`);
    const codes = [...(bundles.bundles ?? []), ...(apks.apks ?? [])].map((b) => Number(b.versionCode)).filter(Number.isInteger);
    return codes.length ? Math.max(...codes) : 0;
  } finally {
    // A read-only edit is thrown away, never committed.
    await call('DELETE', `${app(pkg)}/edits/${edit.id}`).catch(() => {});
  }
}

export async function check(call, pkg, versionCode) {
  const highest = await highestVersionCode(call, pkg);
  console.log(`${pkg}: highest uploaded versionCode ${highest}, this build ${versionCode}`);
  if (versionCode <= highest) {
    throw new Error(`versionCode ${versionCode} is not higher than ${highest}: raise the build in bipe-shared product/version.json (node tools/bump-build.mjs)`);
  }
  return highest;
}

export async function upload(call, pkg, aab, track, { releaseName } = {}) {
  if (!TRACKS.includes(track)) throw new Error(`track ${track} is not allowed here (only ${TRACKS.join(', ')})`);
  const edit = await call('POST', `${app(pkg)}/edits`, { body: {} });
  try {
    const bundle = await call('POST', `${UPLOAD_API}/applications/${encodeURIComponent(pkg)}/edits/${edit.id}/bundles?uploadType=media`, { media: aab });
    console.log(`${pkg}: uploaded versionCode ${bundle.versionCode}`);
    await call('PUT', `${app(pkg)}/edits/${edit.id}/tracks/${track}`, {
      body: { track, releases: [{ name: releaseName ?? String(bundle.versionCode), versionCodes: [String(bundle.versionCode)], status: 'completed' }] },
    });
    await call('POST', `${app(pkg)}/edits/${edit.id}:commit`);
    console.log(`${pkg}: versionCode ${bundle.versionCode} on the ${track} track`);
    return bundle.versionCode;
  } catch (err) {
    await call('DELETE', `${app(pkg)}/edits/${edit.id}`).catch(() => {});
    throw err;
  }
}

// --- Store listing (bipe-chat/bipe#457) ---------------------------------------------------------------

/** The Play listing locales, in the order of appstore.md. Play language codes are the same BCP 47 tags. */
export const LISTING_LOCALES = ['pt-BR', 'en-US'];
/** bipe-web store image targets (scripts/store-images.mjs) for each Play image type. */
export const IMAGE_SETS = { phoneScreenshots: 'play-phone', featureGraphic: 'play-feature' };
const PLAY_LIMITS = { title: 30, shortDescription: 80, fullDescription: 4000 };

/** The listing text from bipe-shared, through its own parser (tools/lib/appstore.mjs): no second copy here. */
export async function loadListing(shared) {
  const lib = await import(pathToFileURL(join(shared, 'tools/lib/appstore.mjs')).href);
  const listing = lib.loadAppstore(shared);
  return { listing, text: (locale) => lib.playListing(listing, locale) };
}

function readManifest(images, set, locale) {
  const file = join(images, set, locale, 'manifest.json');
  if (!existsSync(file)) return { file, manifest: null };
  return { file, manifest: JSON.parse(readFileSync(file, 'utf8')) };
}

/**
 * The plan of one listing run: per locale the text, the phone panels and the feature graphic, and every
 * problem that stops a real run. A panel must come from a native Android capture (play-phone/...): Play
 * screenshots show the app on Android, and the iPhone captures show the fork (bipe-chat/bipe#457 step 3).
 */
export function listingPlan({ listing, text }, images) {
  const problems = [];
  const locales = LISTING_LOCALES.map((locale) => {
    const fields = text(locale);
    for (const [field, limit] of Object.entries(PLAY_LIMITS)) {
      if (!fields[field]) problems.push(`${locale}: ${field} is empty in appstore.md`);
      else if (fields[field].length > limit) problems.push(`${locale}: ${field} has ${fields[field].length} characters, limit ${limit}`);
    }
    const rows = listing.screenshots[locale] ?? [];
    const sets = {};
    for (const [type, set] of Object.entries(IMAGE_SETS)) {
      const { file, manifest } = readManifest(images, set, locale);
      if (!manifest) {
        problems.push(`${locale}: no ${set} images (${file}); run pnpm store:images --device ${set} in bipe-web`);
        sets[type] = [];
        continue;
      }
      const exports = [...manifest.exports].sort((a, b) => a.order - b.order);
      sets[type] = exports.map((e) => ({ ...e, path: join(images, set, locale, e.file) }));
      if (type === 'phoneScreenshots') {
        const slugs = exports.map((e) => e.slug).join(',');
        if (slugs !== rows.map((r) => r.slug).join(',')) problems.push(`${locale}: ${set} panels (${slugs}) differ from the appstore.md screenshot table`);
        if (exports.length < 2 || exports.length > 8) problems.push(`${locale}: ${exports.length} phone screenshots, Play takes 2 to 8`);
      } else if (exports.length !== 1) problems.push(`${locale}: ${exports.length} feature graphics, Play takes 1`);
      for (const e of sets[type]) {
        if (!existsSync(e.path)) problems.push(`${locale}: missing ${e.path}`);
        if (e.capture && !e.capture.startsWith('play-phone/')) problems.push(`${locale}: ${e.file} uses the capture ${e.capture}, not a native Android capture (play-phone/)`);
      }
    }
    return { locale, fields, ...sets };
  });
  return { locales, problems };
}

export function printPlan(plan, pkg, log = console.log) {
  for (const { locale, fields, phoneScreenshots, featureGraphic } of plan.locales) {
    log(`${pkg} ${locale}`);
    log(`  title: ${fields.title}`);
    log(`  short description: ${fields.shortDescription}`);
    log(`  full description (${fields.fullDescription.length} characters):`);
    for (const line of fields.fullDescription.split('\n')) log(`    ${line}`);
    log(`  phone screenshots (${phoneScreenshots.length}):`);
    for (const e of phoneScreenshots) log(`    ${e.order}. ${e.slug}: ${e.title} | ${e.sub ?? ''} (${e.file}, capture ${e.capture ?? 'none'})`);
    log(`  feature graphic: ${featureGraphic.map((e) => `${e.file} (capture ${e.capture ?? 'none'})`).join(', ') || 'none'}`);
  }
  for (const p of plan.problems) log(`not ready: ${p}`);
}

/** Sends the plan in one edit and commits it. Throws, and discards the edit, on any failure. */
export async function sendListing(call, pkg, plan, { read = readFileSync } = {}) {
  if (plan.problems.length) throw new Error(`listing not sent: ${plan.problems.length} problem(s)\n  ${plan.problems.join('\n  ')}`);
  const edit = await call('POST', `${app(pkg)}/edits`, { body: {} });
  const base = `${app(pkg)}/edits/${edit.id}/listings`;
  const upload = `${UPLOAD_API}/applications/${encodeURIComponent(pkg)}/edits/${edit.id}/listings`;
  try {
    for (const { locale, fields, ...images } of plan.locales) {
      await call('PUT', `${base}/${locale}`, { body: fields });
      for (const type of Object.keys(IMAGE_SETS)) {
        await call('DELETE', `${base}/${locale}/${type}`);
        for (const e of images[type]) await call('POST', `${upload}/${locale}/${type}?uploadType=media`, { media: read(e.path), type: 'image/png' });
      }
      console.log(`${pkg} ${locale}: listing text, ${images.phoneScreenshots.length} phone screenshots and the feature graphic`);
    }
    await call('POST', `${app(pkg)}/edits/${edit.id}:commit`);
    console.log(`${pkg}: listing committed`);
  } catch (err) {
    await call('DELETE', `${app(pkg)}/edits/${edit.id}`).catch(() => {});
    throw err;
  }
}

const PACKAGE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
const USAGE = 'usage: node tools/play.mjs check <package> <version-code> | upload <package> <aab> <track> | listing <package> [--dry-run]';

async function main([cmd, pkg, a, b]) {
  if (!['check', 'upload', 'listing'].includes(cmd) || !PACKAGE.test(pkg ?? '')) {
    console.error(USAGE);
    return 2;
  }
  if (cmd === 'listing') {
    if (a !== undefined && a !== '--dry-run') {
      console.error(USAGE);
      return 2;
    }
    const shared = resolve(process.env.BIPE_SHARED ?? join(REPO_ROOT, '../bipe-shared'));
    const images = resolve(process.env.STORE_IMAGES ?? join(REPO_ROOT, '../bipe-web/out/appstore'));
    const plan = listingPlan(await loadListing(shared), images);
    printPlan(plan, pkg);
    if (a === '--dry-run') {
      console.log(plan.problems.length ? `dry run: ${plan.problems.length} problem(s) stop a real run` : 'dry run: nothing sent');
      return plan.problems.length ? 1 : 0;
    }
    if (!process.env.PLAY_SERVICE_ACCOUNT_PATH) throw new Error('PLAY_SERVICE_ACCOUNT_PATH is not set');
    await sendListing(client(await accessToken(readServiceAccount(process.env.PLAY_SERVICE_ACCOUNT_PATH))), pkg, plan);
    return 0;
  }
  if (!process.env.PLAY_SERVICE_ACCOUNT_PATH) throw new Error('PLAY_SERVICE_ACCOUNT_PATH is not set');
  const call = client(await accessToken(readServiceAccount(process.env.PLAY_SERVICE_ACCOUNT_PATH)));
  if (cmd === 'check' && /^\d+$/.test(a ?? '')) {
    await check(call, pkg, Number(a));
    return 0;
  }
  if (cmd === 'upload' && a && b) {
    await upload(call, pkg, readFileSync(a), b, { releaseName: process.env.PLAY_RELEASE_NAME });
    return 0;
  }
  console.error(USAGE);
  return 2;
}

if (isMain(import.meta.url)) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (err) => {
      console.error(`::error::${err.message}`);
      process.exit(1);
    },
  );
}

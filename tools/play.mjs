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
// Environment: PLAY_SERVICE_ACCOUNT_PATH (path of the service account JSON key).
// The script never prints the key or the token.

import { createPrivateKey, sign } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { isMain } from './lib.mjs';

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
  return async function call(method, url, { body, media } = {}) {
    const headers = { Authorization: `Bearer ${token}` };
    let payload;
    if (media) {
      headers['Content-Type'] = 'application/octet-stream';
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

const PACKAGE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;

async function main([cmd, pkg, a, b]) {
  if (!['check', 'upload'].includes(cmd) || !PACKAGE.test(pkg ?? '')) {
    console.error('usage: node tools/play.mjs check <package> <version-code> | upload <package> <aab> <track>');
    return 2;
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
  console.error('usage: node tools/play.mjs check <package> <version-code> | upload <package> <aab> <track>');
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

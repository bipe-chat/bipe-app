// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// App Store Connect API steps of the tester builds (bipe-chat/bipe#575, ADR 0147).
// Node built-ins only. Request shapes: Apple's App Store Connect API reference.
// Usage:
//   node tools/asc.mjs prepare           Registers the bundle IDs with the App Groups
//                                        capability; checks the app record.
//   node tools/asc.mjs distribute <n>    Waits until build <n> is processed and adds
//                                        it to the internal testing group.
// Environment: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (path of the .p8 key).
// The script never prints the key or the token.

import { createPrivateKey, sign } from 'node:crypto';
import { readFileSync, appendFileSync } from 'node:fs';
import { isMain } from './lib.mjs';

export const API = 'https://api.appstoreconnect.apple.com';
export const BUNDLE_ID = 'chat.bipe.app.next';
export const BUNDLE_IDS = [
  { identifier: BUNDLE_ID, name: 'Bipe Next' },
  { identifier: `${BUNDLE_ID}.nse`, name: 'Bipe Next NSE' },
  { identifier: `${BUNDLE_ID}.share`, name: 'Bipe Next Share' },
];
export const APP_NAME = 'Bipe Next';
export const APP_SKU = 'bipe-next';
export const INTERNAL_GROUP = 'Bipe team';

const b64url = (buf) => Buffer.from(buf).toString('base64url');

// ES256 token, valid 20 minutes (Apple's maximum).
export function token({ keyId, issuerId, key, now = Math.floor(Date.now() / 1000) }) {
  const header = b64url(JSON.stringify({ alg: 'ES256', kid: keyId, typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ iss: issuerId, iat: now, exp: now + 1200, aud: 'appstoreconnect-v1' }));
  const input = `${header}.${payload}`;
  const sig = sign('sha256', Buffer.from(input), { key: createPrivateKey(key), dsaEncoding: 'ieee-p1363' });
  return `${input}.${b64url(sig)}`;
}

export function client(env = process.env) {
  for (const name of ['ASC_KEY_ID', 'ASC_ISSUER_ID', 'ASC_KEY_PATH']) {
    if (!env[name]) throw new Error(`${name} is not set`);
  }
  const key = readFileSync(env.ASC_KEY_PATH, 'utf8');
  let jwt = null;
  let jwtAt = 0;
  return async function call(method, path, body) {
    const now = Math.floor(Date.now() / 1000);
    if (!jwt || now - jwtAt > 900) {
      jwt = token({ keyId: env.ASC_KEY_ID, issuerId: env.ASC_ISSUER_ID, key, now });
      jwtAt = now;
    }
    const res = await fetch(`${API}${path}`, {
      method,
      headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    const json = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const detail = (json.errors ?? []).map((e) => `${e.status} ${e.code}: ${e.detail ?? e.title}`).join('; ');
      const err = new Error(`${method} ${path.split('?')[0]} failed: ${res.status} ${detail}`);
      err.status = res.status;
      throw err;
    }
    return json;
  };
}

// The API filters match by prefix, so pick the exact identifier.
export const exact = (rows, field, value) => rows.find((r) => r.attributes?.[field] === value) ?? null;

async function ensureBundleId(call, { identifier, name }) {
  const list = await call('GET', `/v1/bundleIds?filter[identifier]=${encodeURIComponent(identifier)}&limit=200`);
  let row = exact(list.data, 'identifier', identifier);
  if (row) {
    console.log(`bundle ID ${identifier}: exists`);
  } else {
    const made = await call('POST', '/v1/bundleIds', {
      data: { type: 'bundleIds', attributes: { identifier, name, platform: 'IOS' } },
    });
    row = made.data;
    console.log(`bundle ID ${identifier}: registered`);
  }
  const caps = await call('GET', `/v1/bundleIds/${row.id}/bundleIdCapabilities`);
  if (caps.data.some((c) => c.attributes.capabilityType === 'APP_GROUPS')) {
    console.log(`bundle ID ${identifier}: App Groups on`);
  } else {
    await call('POST', '/v1/bundleIdCapabilities', {
      data: {
        type: 'bundleIdCapabilities',
        attributes: { capabilityType: 'APP_GROUPS' },
        relationships: { bundleId: { data: { type: 'bundleIds', id: row.id } } },
      },
    });
    console.log(`bundle ID ${identifier}: App Groups enabled`);
  }
  return row;
}

export async function findApp(call) {
  const list = await call('GET', `/v1/apps?filter[bundleId]=${BUNDLE_ID}`);
  return exact(list.data, 'bundleId', BUNDLE_ID);
}

// The app group itself (group.chat.bipe.next) has no API endpoint. Xcode registers
// it during the archive (-allowProvisioningUpdates) because the entitlements name it.
export async function prepare(call) {
  for (const b of BUNDLE_IDS) await ensureBundleId(call, b);
  let app = await findApp(call);
  if (!app) {
    try {
      const made = await call('POST', '/v1/apps', {
        data: {
          type: 'apps',
          attributes: { name: APP_NAME, sku: APP_SKU, primaryLocale: 'pt-BR', bundleId: BUNDLE_ID },
        },
      });
      app = made.data;
    } catch (err) {
      console.error(`::warning::No App Store Connect app for ${BUNDLE_ID}, and the API cannot create one (${err.message}). The ad hoc IPA is still built; TestFlight is skipped.`);
      console.error(`Create it once in App Store Connect: Apps, +, New App: iOS, name "${APP_NAME}", primary language Portuguese (Brazil), bundle ID ${BUNDLE_ID}, SKU ${APP_SKU}.`);
      return null;
    }
  }
  console.log(`app ${APP_NAME}: ${app.id}`);
  return app;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function distribute(call, buildNumber, { timeoutMs = 60 * 60 * 1000, pollMs = 30 * 1000 } = {}) {
  const app = await findApp(call);
  if (!app) throw new Error(`no app record for ${BUNDLE_ID}`);
  const deadline = Date.now() + timeoutMs;
  let build = null;
  for (;;) {
    const list = await call('GET', `/v1/builds?filter[app]=${app.id}&filter[version]=${buildNumber}&limit=10`);
    build = list.data[0] ?? null;
    const state = build?.attributes.processingState ?? 'NOT_YET_VISIBLE';
    console.log(`build ${buildNumber}: ${state}`);
    if (state === 'VALID') break;
    if (state === 'FAILED' || state === 'INVALID') throw new Error(`build ${buildNumber} is ${state}`);
    if (Date.now() > deadline) throw new Error(`build ${buildNumber} not processed in time`);
    await sleep(pollMs);
  }

  const groups = await call('GET', `/v1/apps/${app.id}/betaGroups?limit=200`);
  let group = groups.data.find((g) => g.attributes.isInternalGroup) ?? null;
  if (!group) {
    const made = await call('POST', '/v1/betaGroups', {
      data: {
        type: 'betaGroups',
        attributes: { name: INTERNAL_GROUP, isInternalGroup: true, hasAccessToAllBuilds: true },
        relationships: { app: { data: { type: 'apps', id: app.id } } },
      },
    });
    group = made.data;
    console.log(`internal group ${INTERNAL_GROUP}: created`);
  }
  if (group.attributes.hasAccessToAllBuilds) {
    console.log(`internal group ${group.attributes.name}: gets every build`);
  } else {
    await call('POST', `/v1/betaGroups/${group.id}/relationships/builds`, {
      data: [{ type: 'builds', id: build.id }],
    });
    console.log(`internal group ${group.attributes.name}: build ${buildNumber} added`);
  }
  return { app, build, group };
}

async function main([cmd, arg]) {
  const call = client();
  if (cmd === 'prepare') {
    // A missing app record skips only the TestFlight steps (output app=false); the ad hoc IPA needs no record.
    const app = await prepare(call);
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `app=${app ? 'true' : 'false'}\n`);
    return 0;
  }
  if (cmd === 'distribute' && /^\d+$/.test(arg ?? '')) {
    await distribute(call, arg);
    return 0;
  }
  console.error('usage: node tools/asc.mjs prepare | distribute <build-number>');
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

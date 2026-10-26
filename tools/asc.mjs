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
//   node tools/asc.mjs signing <serial>  Makes the App Store and ad hoc profiles of the
//                                        three bundle IDs for the distribution certificate
//                                        with this serial, installs them and writes the
//                                        export options (bipe-chat/bipe#585).
//   node tools/asc.mjs external <n>      Reports external testing; with BIPE_EXTERNAL_TESTING=true
//                                        adds build <n> to the external group and submits it to
//                                        beta review (bipe-chat/bipe#658).
//   node tools/asc.mjs certificates      Lists the team's development certificates. Never
//                                        revokes one.
//   node tools/asc.mjs store-ids [<n>]   Read only (bipe-chat/bipe#602, #604): the bundle IDs
//                                        of the fork's store app (chat.bipe.app and its
//                                        extensions) with their capabilities, its App Store
//                                        Connect record, its last build number and version.
//                                        With <n>: fails unless build <n> is higher.
// Environment: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (path of the .p8 key). BIPE_ID_SET=store
// (bipe-chat/bipe#604, store-release.yml) runs prepare, signing and distribute on the fork's store
// app (chat.bipe.app, record "Bipe"): there the script never registers a bundle ID, never changes a
// capability and never creates an app record, and it makes App Store profiles only.
// The script never prints the key or the token.

import { createPrivateKey, sign } from 'node:crypto';
import { readFileSync, appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
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
export const TEAM_ID = '992N457T8D';
// Capabilities of the bundle IDs. A profile carries only what its bundle ID has. The App
// Groups capability needs group.chat.bipe.next linked to it, which the API cannot do (see
// prepare). App Attest has no API capability type: automatic signing turned it on before
// bipe-chat/bipe#585, and the archive fails with a clear message if it is off.
export const CAPABILITIES = {
  [BUNDLE_ID]: ['APP_GROUPS', 'PUSH_NOTIFICATIONS', 'ASSOCIATED_DOMAINS'],
  [`${BUNDLE_ID}.nse`]: ['APP_GROUPS'],
  [`${BUNDLE_ID}.share`]: ['APP_GROUPS'],
};
export const PROFILE_TYPES = { appstore: 'IOS_APP_STORE', adhoc: 'IOS_APP_ADHOC' };

// ID sets (bipe-chat/bipe#602, ios/project.yml BIPE_APP_ID). next: Bipe Next, the tester app that
// CI may set up. store: the fork's live app (ADR 0061), read and signed for, never changed. Its
// extension IDs are the registered ones (.nse, .shareextension; ios/project.yml BIPE_SHARE_ID_SUFFIX).
export const NEXT = {
  name: 'next', appId: BUNDLE_ID, appName: APP_NAME, bundleIds: BUNDLE_IDS, profilePrefix: 'Bipe Next CI', manage: true,
  kinds: Object.keys(PROFILE_TYPES),
};
export const STORE = {
  name: 'store', appId: 'chat.bipe.app', appName: 'Bipe', profilePrefix: 'Bipe CI', manage: false, kinds: ['appstore'],
  bundleIds: [
    { identifier: 'chat.bipe.app', name: 'Bipe' },
    { identifier: 'chat.bipe.app.nse', name: 'Bipe NSE' },
    { identifier: 'chat.bipe.app.shareextension', name: 'Bipe Share' },
  ],
};
export function idSet(name = 'next') {
  if (name === 'next') return NEXT;
  if (name === 'store') return STORE;
  throw new Error(`unknown ID set ${name} (next or store)`);
}
// The build setting of each target's profile, by position: app, NSE, share (ios/project.yml).
const PROFILE_SETTING_ORDER = ['BIPE_CI_PROFILE_APP', 'BIPE_CI_PROFILE_NSE', 'BIPE_CI_PROFILE_SHARE'];

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

async function ensureBundleId(call, { identifier, name }, set = NEXT) {
  const list = await call('GET', `/v1/bundleIds?filter[identifier]=${encodeURIComponent(identifier)}&limit=200`);
  let row = exact(list.data, 'identifier', identifier);
  if (row) {
    console.log(`bundle ID ${identifier}: exists`);
    // The store app's bundle IDs and capabilities belong to the live app: read, never changed.
    if (!set.manage) return row;
  } else if (!set.manage) {
    throw new Error(`no bundle ID ${identifier} in team ${TEAM_ID}: check the store IDs (node tools/asc.mjs store-ids)`);
  } else {
    const made = await call('POST', '/v1/bundleIds', {
      data: { type: 'bundleIds', attributes: { identifier, name, platform: 'IOS' } },
    });
    row = made.data;
    console.log(`bundle ID ${identifier}: registered`);
  }
  const caps = await call('GET', `/v1/bundleIds/${row.id}/bundleIdCapabilities`);
  for (const capabilityType of CAPABILITIES[identifier] ?? []) {
    if (caps.data.some((c) => c.attributes.capabilityType === capabilityType)) {
      console.log(`bundle ID ${identifier}: ${capabilityType} on`);
      continue;
    }
    try {
      await call('POST', '/v1/bundleIdCapabilities', {
        data: {
          type: 'bundleIdCapabilities',
          attributes: { capabilityType },
          relationships: { bundleId: { data: { type: 'bundleIds', id: row.id } } },
        },
      });
      console.log(`bundle ID ${identifier}: ${capabilityType} enabled`);
    } catch (err) {
      // The profile then lacks it, and the archive names the missing entitlement.
      console.error(`::warning::bundle ID ${identifier}: cannot enable ${capabilityType} (${err.message})`);
    }
  }
  return row;
}

export async function findApp(call, set = NEXT) {
  const list = await call('GET', `/v1/apps?filter[bundleId]=${set.appId}`);
  return exact(list.data, 'bundleId', set.appId);
}

// The app group itself (group.chat.bipe.next) has no API endpoint. Xcode registered it
// and linked it to the bundle IDs in the automatic signing runs before bipe-chat/bipe#585.
// A new group needs that step once by hand (Certificates, Identifiers & Profiles).
export async function prepare(call, set = NEXT) {
  for (const b of set.bundleIds) await ensureBundleId(call, b, set);
  let app = await findApp(call, set);
  if (!app && !set.manage) throw new Error(`no App Store Connect app for ${set.appId}`);
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
  console.log(`app ${set.appName}: ${app.id}`);
  return app;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function distribute(call, buildNumber, { timeoutMs = 60 * 60 * 1000, pollMs = 30 * 1000, set = NEXT } = {}) {
  const app = await findApp(call, set);
  if (!app) throw new Error(`no app record for ${set.appId}`);
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

// Serials compare as hex numbers: the API and openssl may differ in case and leading zeros.
export const sameSerial = (a, b) =>
  String(a ?? '').toUpperCase().replace(/^0+/, '') === String(b ?? '').toUpperCase().replace(/^0+/, '') && !!a;

export const profileName = (kind, identifier, set = NEXT) =>
  `${set.profilePrefix} ${kind === 'adhoc' ? 'Ad Hoc' : 'App Store'} ${identifier}`;

// The distribution certificate whose key the runner holds. The CI never makes a certificate:
// each new one counts against the team's limit (bipe-chat/bipe#585).
export async function findCertificate(call, serial) {
  const list = await call('GET', '/v1/certificates?filter[certificateType]=DISTRIBUTION,IOS_DISTRIBUTION&limit=200');
  const cert = list.data.find((c) => sameSerial(c.attributes.serialNumber, serial)) ?? null;
  if (!cert) throw new Error(`no distribution certificate with serial ${serial} in team ${TEAM_ID}`);
  return cert;
}

// Makes the profile again on every run, so it carries the current devices and capabilities.
// Only profiles with the CI's own names are deleted.
export async function makeProfile(call, { kind, bundleId, certificate, devices, set = NEXT }) {
  const name = profileName(kind, bundleId.attributes.identifier, set);
  const old = await call('GET', `/v1/profiles?filter[name]=${encodeURIComponent(name)}&limit=200`);
  for (const row of old.data.filter((r) => r.attributes?.name === name)) {
    await call('DELETE', `/v1/profiles/${row.id}`);
  }
  const relationships = {
    bundleId: { data: { type: 'bundleIds', id: bundleId.id } },
    certificates: { data: [{ type: 'certificates', id: certificate.id }] },
  };
  if (kind === 'adhoc') relationships.devices = { data: devices.map((d) => ({ type: 'devices', id: d.id })) };
  const made = await call('POST', '/v1/profiles', {
    data: { type: 'profiles', attributes: { name, profileType: PROFILE_TYPES[kind] }, relationships },
  });
  console.log(`profile ${name}: made`);
  return made.data;
}

const xml = (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// Export options for xcodebuild -exportArchive: manual signing with the CI profiles.
export function exportOptions(kind, teamId = TEAM_ID, set = NEXT) {
  const entries = [
    ['method', kind === 'adhoc' ? 'release-testing' : 'app-store-connect'],
    ['destination', kind === 'adhoc' ? 'export' : 'upload'],
    ['teamID', teamId],
    ['signingStyle', 'manual'],
    ['signingCertificate', 'Apple Distribution'],
  ];
  const tail = kind === 'adhoc'
    ? '  <key>thinning</key><string>&lt;none&gt;</string>\n'
    : '  <key>manageAppVersionAndBuildNumber</key><false/>\n  <key>uploadSymbols</key><true/>\n';
  const profiles = set.bundleIds.map(({ identifier }) =>
    `    <key>${xml(identifier)}</key><string>${xml(profileName(kind, identifier, set))}</string>\n`).join('');
  return '<?xml version="1.0" encoding="UTF-8"?>\n'
    + '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
    + '<plist version="1.0"><dict>\n'
    + entries.map(([k, v]) => `  <key>${k}</key><string>${xml(v)}</string>\n`).join('')
    + `  <key>provisioningProfiles</key><dict>\n${profiles}  </dict>\n`
    + tail
    + '</dict></plist>\n';
}

// Profiles go where Xcode 16 and later look, and where older tools look.
function installProfile(home, profile) {
  const body = Buffer.from(profile.attributes.profileContent, 'base64');
  for (const dir of ['Library/Developer/Xcode/UserData/Provisioning Profiles', 'Library/MobileDevice/Provisioning Profiles']) {
    mkdirSync(join(home, dir), { recursive: true });
    writeFileSync(join(home, dir, `${profile.attributes.uuid}.mobileprovision`), body);
  }
}

export async function signing(call, serial, { home = process.env.HOME, outDir = process.env.RUNNER_TEMP, env = process.env, set = NEXT } = {}) {
  const certificate = await findCertificate(call, serial);
  console.log(`certificate ${certificate.attributes.name ?? certificate.attributes.displayName}: expires ${certificate.attributes.expirationDate}`);
  let devices = [];
  if (set.kinds.includes('adhoc')) {
    devices = (await call('GET', '/v1/devices?filter[platform]=IOS&filter[status]=ENABLED&limit=200')).data;
    if (devices.length === 0) throw new Error('no enabled iOS devices for the ad hoc profiles');
    console.log(`devices: ${devices.length}`);
  }
  for (const b of set.bundleIds) {
    const bundleId = await ensureBundleId(call, b, set);
    for (const kind of set.kinds) {
      installProfile(home, await makeProfile(call, { kind, bundleId, certificate, devices, set }));
    }
  }
  for (const kind of set.kinds) {
    writeFileSync(join(outDir, `${kind === 'adhoc' ? 'adhoc' : 'upload'}.plist`), exportOptions(kind, TEAM_ID, set));
  }
  // The archive signs with the App Store profiles; the exports sign again with their own.
  if (env.GITHUB_ENV) {
    set.bundleIds.forEach(({ identifier }, i) => {
      appendFileSync(env.GITHUB_ENV, `${PROFILE_SETTING_ORDER[i]}=${profileName('appstore', identifier, set)}\n`);
    });
  }
}

// Report only, for MZ to revoke by hand (bipe-chat/bipe#585).
export async function certificates(call) {
  const list = await call('GET', '/v1/certificates?limit=200');
  const dev = list.data.filter((c) => ['DEVELOPMENT', 'IOS_DEVELOPMENT'].includes(c.attributes.certificateType));
  console.log(`development certificates: ${dev.length}`);
  for (const c of dev) {
    console.log(`- ${c.attributes.displayName ?? ''} | ${c.attributes.name ?? ''} | expires ${c.attributes.expirationDate} | serial ${c.attributes.serialNumber}`);
  }
  const byExpiry = {};
  for (const c of dev) {
    const day = String(c.attributes.expirationDate ?? '').slice(0, 10);
    byExpiry[day] = (byExpiry[day] ?? 0) + 1;
  }
  console.log(`by expiry day: ${JSON.stringify(byExpiry)}`);
  return dev;
}

// The fork's store app (ADR 0061). Its extension IDs must come from App Store Connect or MZ,
// never from the fork's project files (clean room, bipe-chat/bipe#602).
export const STORE_BUNDLE_ID = STORE.appId;

// Read only: GET requests, nothing registered or changed. The report goes to the log and the job summary.
export async function storeIds(call, { summary = process.env.GITHUB_STEP_SUMMARY } = {}) {
  const list = await call('GET', `/v1/bundleIds?filter[identifier]=${STORE_BUNDLE_ID}&limit=200`);
  // The filter matches by prefix: keep the store ID and its own children, never the Bipe Next IDs.
  const ids = list.data
    .filter((r) => r.attributes?.identifier === STORE_BUNDLE_ID
      || (r.attributes?.identifier?.startsWith(`${STORE_BUNDLE_ID}.`) && !r.attributes.identifier.startsWith(`${BUNDLE_ID}`)))
    .sort((a, b) => a.attributes.identifier.localeCompare(b.attributes.identifier));
  const bundleIds = [];
  for (const row of ids) {
    const caps = await call('GET', `/v1/bundleIds/${row.id}/bundleIdCapabilities`);
    bundleIds.push({
      identifier: row.attributes.identifier,
      name: row.attributes.name,
      capabilities: caps.data.map((c) => c.attributes.capabilityType).sort(),
    });
  }
  const apps = await call('GET', `/v1/apps?filter[bundleId]=${STORE_BUNDLE_ID}`);
  const app = exact(apps.data, 'bundleId', STORE_BUNDLE_ID);
  let lastBuild = null;
  let versions = [];
  if (app) {
    const builds = await call('GET', `/v1/builds?filter[app]=${app.id}&sort=-uploadedDate&limit=200`);
    const numbers = builds.data.map((b) => b.attributes?.version).filter((v) => /^\d+$/.test(v ?? '')).map(Number);
    lastBuild = numbers.length ? Math.max(...numbers) : null;
    const v = await call('GET', `/v1/apps/${app.id}/appStoreVersions?limit=10`);
    versions = v.data.map((r) => `${r.attributes.versionString} (${r.attributes.appStoreState ?? r.attributes.appVersionState ?? 'unknown'})`);
  }
  const report = {
    bundleIds,
    app: app ? { id: app.id, name: app.attributes.name, sku: app.attributes.sku } : null,
    lastBuild,
    versions,
  };
  const lines = [
    `## Store IDs (${STORE_BUNDLE_ID})`,
    '',
    ...(bundleIds.length
      ? bundleIds.map((b) => `- \`${b.identifier}\` (${b.name}): ${b.capabilities.join(', ') || 'no capabilities'}`)
      : [`- No bundle ID ${STORE_BUNDLE_ID} in team ${TEAM_ID}`]),
    '',
    report.app ? `App Store Connect record: ${report.app.name} (${report.app.id}, SKU ${report.app.sku})` : 'App Store Connect record: none',
    `Last build number: ${lastBuild ?? 'none'}. The first new-client build must be higher (docs/client-switch-plan.md step 4).`,
    `Versions: ${versions.join(', ') || 'none'}`,
    '',
    'App groups and keychain groups have no App Store Connect API: confirm them in Certificates, Identifiers & Profiles.',
  ];
  console.log(lines.join('\n'));
  if (summary) appendFileSync(summary, `${lines.join('\n')}\n`);
  return report;
}

// External testing of Bipe Next (bipe-chat/bipe#658, M1 Public TestFlight). Every run writes a
// report. With enable (BIPE_EXTERNAL_TESTING=true) it fills missing beta app information and
// review details from the fork's "Bipe" record (read only there), adds the build to the external
// group and submits it to beta app review. With publicLink (BIPE_PUBLIC_LINK=true) it turns the
// group's public link on; it never turns a link off. It never prints the review contact or the
// demo account.
export const EXTERNAL_GROUP = 'Testers';
export const BETA_LOCALE = 'pt-BR';
export const BETA_TEXT = {
  description: 'O Bipe é um mensageiro brasileiro. Suas conversas e seus dados ficam no Brasil. Esta é a versão de testes do novo app.',
  whatToTest: 'Crie sua conta, converse com alguém que já usa o Bipe e passe a conta para outro celular pelo QR. Se algo der errado, tire uma captura de tela e envie pelo TestFlight.',
  privacyPolicyUrl: 'https://bipe.chat/privacidade/',
};
const LOCALIZATION_FIELDS = ['description', 'feedbackEmail', 'marketingUrl', 'privacyPolicyUrl'];
const REVIEW_FIELDS = ['contactFirstName', 'contactLastName', 'contactPhone', 'contactEmail', 'demoAccountName', 'demoAccountPassword', 'demoAccountRequired', 'notes'];
const REVIEW_REQUIRED = ['contactFirstName', 'contactLastName', 'contactPhone', 'contactEmail'];
const empty = (v) => v === null || v === undefined || v === '';
// The fork's record can lack a resource; a 404 there means "nothing to copy".
const orNull = (p) => p.catch((err) => { if (err.status === 404) return { data: null }; throw err; });

export async function external(call, buildNumber, { enable = false, publicLink = false, summary = process.env.GITHUB_STEP_SUMMARY } = {}) {
  const app = await findApp(call, NEXT);
  if (!app) throw new Error(`no app record for ${NEXT.appId}`);
  const missing = [];
  const lines = [`## External TestFlight (${NEXT.appName})`, ''];

  const groups = await call('GET', `/v1/apps/${app.id}/betaGroups?limit=200`);
  let group = groups.data.find((g) => !g.attributes.isInternalGroup) ?? null;
  let locs = (await call('GET', `/v1/apps/${app.id}/betaAppLocalizations?limit=50`)).data;
  let review = (await call('GET', `/v1/apps/${app.id}/betaAppReviewDetail`)).data;
  const list = await call('GET', `/v1/builds?filter[app]=${app.id}&filter[version]=${buildNumber}&limit=10`);
  const build = list.data[0] ?? null;

  if (enable) {
    const store = await findApp(call, STORE);
    const storeLocs = store ? (await orNull(call('GET', `/v1/apps/${store.id}/betaAppLocalizations?limit=50`))).data ?? [] : [];
    const storeReview = store ? (await orNull(call('GET', `/v1/apps/${store.id}/betaAppReviewDetail`))).data : null;

    // Beta app information: Bipe Next's own values first, then the fork's, then BETA_TEXT.
    const loc = locs.find((l) => l.attributes.locale === BETA_LOCALE) ?? null;
    const from = storeLocs.find((l) => l.attributes.locale === BETA_LOCALE)?.attributes ?? {};
    const want = {};
    for (const f of LOCALIZATION_FIELDS) {
      if (!empty(loc?.attributes[f])) continue;
      const v = empty(from[f]) ? BETA_TEXT[f] : from[f];
      if (!empty(v)) want[f] = v;
    }
    if (!loc) {
      const made = await call('POST', '/v1/betaAppLocalizations', {
        data: { type: 'betaAppLocalizations', attributes: { locale: BETA_LOCALE, ...want }, relationships: { app: { data: { type: 'apps', id: app.id } } } },
      });
      locs = [...locs, made.data];
    } else if (Object.keys(want).length) {
      const made = await call('PATCH', `/v1/betaAppLocalizations/${loc.id}`, { data: { type: 'betaAppLocalizations', id: loc.id, attributes: want } });
      locs = locs.map((l) => (l.id === loc.id ? made.data : l));
    }

    // Review contact and demo account: copied from the fork's record where Bipe Next has none.
    const copy = {};
    for (const f of REVIEW_FIELDS) {
      if (empty(review.attributes[f]) && !empty(storeReview?.attributes?.[f])) copy[f] = storeReview.attributes[f];
    }
    if (Object.keys(copy).length) {
      review = (await call('PATCH', `/v1/betaAppReviewDetails/${review.id}`, { data: { type: 'betaAppReviewDetails', id: review.id, attributes: copy } })).data;
    }

    if (!group) {
      group = (await call('POST', '/v1/betaGroups', {
        data: {
          type: 'betaGroups',
          attributes: { name: EXTERNAL_GROUP, isInternalGroup: false, publicLinkEnabled: publicLink, publicLinkLimitEnabled: false },
          relationships: { app: { data: { type: 'apps', id: app.id } } },
        },
      })).data;
    } else if (publicLink && !group.attributes.publicLinkEnabled) {
      group = (await call('PATCH', `/v1/betaGroups/${group.id}`, { data: { type: 'betaGroups', id: group.id, attributes: { publicLinkEnabled: true, publicLinkLimitEnabled: false } } })).data;
    }
  }

  const loc = locs.find((l) => l.attributes.locale === BETA_LOCALE) ?? null;
  for (const f of ['description', 'feedbackEmail', 'privacyPolicyUrl']) if (empty(loc?.attributes[f])) missing.push(`beta app ${f} (${BETA_LOCALE})`);
  for (const f of REVIEW_REQUIRED) if (empty(review?.attributes[f])) missing.push(`review ${f}`);
  if (review?.attributes.demoAccountRequired && empty(review.attributes.demoAccountName)) missing.push('review demo account');

  let state = null;
  if (build) {
    state = (await call('GET', `/v1/builds/${build.id}/buildBetaDetail`)).data?.attributes?.externalBuildState ?? null;
    if (enable && group) {
      const bl = (await call('GET', `/v1/builds/${build.id}/betaBuildLocalizations?limit=50`)).data.find((l) => l.attributes.locale === BETA_LOCALE);
      if (!bl) {
        await call('POST', '/v1/betaBuildLocalizations', {
          data: { type: 'betaBuildLocalizations', attributes: { locale: BETA_LOCALE, whatToTest: BETA_TEXT.whatToTest }, relationships: { build: { data: { type: 'builds', id: build.id } } } },
        });
      }
      await call('POST', `/v1/betaGroups/${group.id}/relationships/builds`, { data: [{ type: 'builds', id: build.id }] });
      if (state === 'READY_FOR_BETA_SUBMISSION' && !missing.length) {
        try {
          await call('POST', '/v1/betaAppReviewSubmissions', { data: { type: 'betaAppReviewSubmissions', relationships: { build: { data: { type: 'builds', id: build.id } } } } });
          state = 'WAITING_FOR_BETA_REVIEW';
        } catch (err) {
          console.error(`::warning::beta review submission of build ${buildNumber} failed: ${err.message}`);
        }
      }
    }
  }

  lines.push(
    `External testing: ${enable ? 'on' : 'off'} (BIPE_EXTERNAL_TESTING). Public link: ${publicLink ? 'on' : 'off'} (BIPE_PUBLIC_LINK).`,
    group
      ? `Group: ${group.attributes.name}, public link ${group.attributes.publicLinkEnabled ? (group.attributes.publicLink ?? 'on') : 'off'}`
      : 'Group: none',
    `Build ${buildNumber}: ${build ? (state ?? 'no beta detail') : 'not found'}`,
    missing.length ? `Missing for beta review: ${missing.join(', ')}` : 'Beta review information: complete',
  );
  if (enable && missing.length) console.error(`::warning::beta review needs: ${missing.join(', ')}`);
  console.log(lines.join('\n'));
  if (summary) appendFileSync(summary, `${lines.join('\n')}\n`);
  return { group, state, missing };
}

async function main([cmd, arg]) {
  const call = client();
  const set = idSet(process.env.BIPE_ID_SET || 'next');
  if (cmd === 'prepare') {
    // A missing app record skips only the TestFlight steps (output app=false); the ad hoc IPA needs no record.
    const app = await prepare(call, set);
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `app=${app ? 'true' : 'false'}\n`);
    return 0;
  }
  if (cmd === 'distribute' && /^\d+$/.test(arg ?? '')) {
    await distribute(call, arg, { set });
    return 0;
  }
  if (cmd === 'signing' && /^[0-9A-Fa-f]+$/.test(arg ?? '')) {
    await signing(call, arg, { set });
    return 0;
  }
  if (cmd === 'external' && /^\d+$/.test(arg ?? '')) {
    await external(call, arg, {
      enable: process.env.BIPE_EXTERNAL_TESTING === 'true',
      publicLink: process.env.BIPE_PUBLIC_LINK === 'true',
    });
    return 0;
  }
  if (cmd === 'certificates') {
    await certificates(call);
    return 0;
  }
  if (cmd === 'store-ids') {
    // With a build number: fail unless it is higher than the store app's last build (docs/client-switch-plan.md step 4).
    const report = await storeIds(call);
    if (/^\d+$/.test(arg ?? '') && report.lastBuild !== null && Number(arg) <= report.lastBuild) {
      throw new Error(`build number ${arg} is not higher than the store app's last build ${report.lastBuild}: raise the build in bipe-shared product/version.json (node tools/bump-build.mjs)`);
    }
    return 0;
  }
  console.error('usage: [BIPE_ID_SET=store] node tools/asc.mjs prepare | distribute <build-number> | external <build-number> | signing <serial> | certificates | store-ids [<build-number>]');
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

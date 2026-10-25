// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the licence gates. Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { checkDenyList } from './deny-list.mjs';
import { checkLicences, catalogLibraries, swiftPins, loadTable } from './licences.mjs';
import { checkHeaders, HEADER } from './headers.mjs';
import { checkStrings, findLiterals, isUserText } from './strings.mjs';
import { provenanceLine } from './provenance.mjs';
import { evaluate, flatNames, matchedTokens, parseCsv, tokenCounts } from './similarity.mjs';
import { checkSdkVersion, projectPin, resolvedPin, catalogPin } from './sdk-version.mjs';
import { exact, token, sameSerial, profileName, exportOptions, signing, certificates, storeIds, prepare, idSet, STORE } from './asc.mjs';
import { generateKeyPairSync, verify } from 'node:crypto';

function tree(files) {
  const root = mkdtempSync(join(tmpdir(), 'bipe-gates-'));
  for (const [path, body] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), body);
  }
  return root;
}

const H = HEADER.join('\n') + '\n';

// Deny-list

test('deny-list passes an empty tree', () => {
  assert.deepEqual(checkDenyList(tree({})).errors, []);
});

test('deny-list passes allowed manifests', () => {
  const root = tree({
    'ios/project.yml': 'packages:\n  MatrixRustSDK:\n    url: https://github.com/matrix-org/matrix-rust-components-swift\n',
    'android/gradle/libs.versions.toml': '[libraries]\nsdk = { module = "org.matrix.rustcomponents:sdk-android", version = "1" }\n',
  });
  assert.deepEqual(checkDenyList(root).errors, []);
});

test('deny-list fails on compound-ios in project.yml', () => {
  const root = tree({ 'ios/project.yml': 'packages:\n  Compound:\n    url: https://github.com/element-hq/compound-ios\n' });
  const { errors } = checkDenyList(root);
  assert.ok(errors.some((e) => e.includes('compound-ios')));
  assert.ok(errors.some((e) => e.includes('element-hq/')));
});

test('deny-list fails on io.element in a Gradle file and on Package.resolved', () => {
  const root = tree({
    'android/app/build.gradle.kts': 'dependencies { implementation("io.element.android:foo:1.0") }\n',
    'ios/Bipe.xcworkspace/xcshareddata/swiftpm/Package.resolved': '{"pins":[{"identity":"matrix-rich-text-editor-swift"}]}',
  });
  const { errors } = checkDenyList(root);
  assert.equal(errors.length, 2);
});

// Licences

test('licences pass with no dependencies', () => {
  assert.deepEqual(checkLicences(tree({})).notes, ['no dependencies yet']);
});

test('licences pass the seeded SDKs and warn on the unverified one', () => {
  const root = tree({
    'ios/Bipe.xcworkspace/xcshareddata/swiftpm/Package.resolved': JSON.stringify({
      pins: [{ identity: 'matrix-rust-components-swift', location: 'https://github.com/matrix-org/matrix-rust-components-swift' }],
      version: 2,
    }),
    'android/gradle/libs.versions.toml':
      '[versions]\nsdk = "1"\n\n[libraries]\nmatrix-sdk = { module = "org.matrix.rustcomponents:sdk-android", version.ref = "sdk" }\n',
  });
  const result = checkLicences(root, loadTable());
  assert.deepEqual(result.errors, []);
  assert.equal(result.warnings.length, 1);
});

test('licences fail on a missing package and on a disallowed licence', () => {
  const table = { allowed: ['MIT'], packages: { 'gpl-lib': { licence: 'GPL-3.0-only' } } };
  const root = tree({
    'ios/Package.resolved': JSON.stringify({ object: { pins: [{ package: 'GPL-Lib' }, { package: 'unknown' }] }, version: 1 }),
  });
  const { errors } = checkLicences(root, table);
  assert.equal(errors.length, 2);
  assert.ok(errors[0].includes('GPL-3.0-only'));
  assert.ok(errors[1].includes('not in tools/licences.json'));
});

test('licences allow an EPL test dependency only on testImplementation lines (ADR 0148)', () => {
  const table = { allowed: ['MIT'], testOnly: ['EPL-1.0'], packages: { 'junit:junit': { licence: 'EPL-1.0', scope: 'test' } } };
  const catalog = '[libraries]\njunit = { module = "junit:junit", version = "4" }\n';
  const ok = tree({
    'android/gradle/libs.versions.toml': catalog,
    'android/lib/build.gradle.kts': 'dependencies {\n    testImplementation(libs.junit)\n}\n',
  });
  assert.deepEqual(checkLicences(ok, table).errors, []);
  const leak = tree({
    'android/gradle/libs.versions.toml': catalog,
    'android/lib/build.gradle.kts': 'dependencies {\n    implementation(libs.junit)\n}\n',
  });
  const { errors } = checkLicences(leak, table);
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes('outside testImplementation'));
  const noScope = { ...table, packages: { 'junit:junit': { licence: 'EPL-1.0' } } };
  assert.ok(checkLicences(ok, noScope).errors[0].includes('not allowed'));
});

test('licences allow a Play services dependency only on playImplementation lines (ADR 0150)', () => {
  const name = 'com.google.android.gms:play-services-auth-blockstore';
  const table = { allowed: ['MIT'], playOnly: ['LicenseRef-Android-SDK-License'], packages: { [name]: { licence: 'LicenseRef-Android-SDK-License', scope: 'play' } } };
  const catalog = `[libraries]\nblockstore = { module = "${name}", version = "16" }\n`;
  const ok = tree({
    'android/gradle/libs.versions.toml': catalog,
    'android/app/build.gradle.kts': 'dependencies {\n    "playImplementation"(libs.blockstore)\n}\n',
  });
  assert.deepEqual(checkLicences(ok, table).errors, []);
  const leak = tree({
    'android/gradle/libs.versions.toml': catalog,
    'android/app/build.gradle.kts': 'dependencies {\n    implementation(libs.blockstore)\n}\n',
  });
  const { errors } = checkLicences(leak, table);
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes('outside playImplementation'));
  // A test scope does not open the Play licence, and a Play scope does not open the test licences.
  const asTest = { ...table, packages: { [name]: { licence: 'LicenseRef-Android-SDK-License', scope: 'test' } } };
  assert.ok(checkLicences(tree({ 'android/gradle/libs.versions.toml': catalog }), asTest).errors[0].includes('not allowed'));
  const eplAsPlay = { ...table, testOnly: ['EPL-1.0'], packages: { [name]: { licence: 'EPL-1.0', scope: 'play' } } };
  assert.ok(checkLicences(ok, eplAsPlay).errors[0].includes('not allowed'));
});

test('catalog parser reads the three library forms and skips plugins', () => {
  const libs = catalogLibraries(
    '[libraries]\na = "g.a:x:1"\nb = { module = "g.b:y", version.ref = "v" } # c\nc = { group = "g.c", name = "z", version = "2" }\n[plugins]\nd = { id = "p", version = "1" }\n',
  );
  assert.deepEqual(libs, ['g.a:x', 'g.b:y', 'g.c:z']);
  assert.deepEqual(swiftPins('{"pins":[{"identity":"A"}],"version":3}'), ['a']);
});

// Headers

test('headers pass a file with the header and skip generated folders', () => {
  const root = tree({
    'ios/Bipe/App.swift': H + 'import SwiftUI\n',
    'android/app/src/main/kotlin/Main.kt': H + 'package bipe\n',
    'ios/Bipe/Generated/Strings.swift': '// generated by bipe-shared\n',
  });
  assert.deepEqual(checkHeaders(root).errors, []);
});

test('headers fail a file without the header', () => {
  const root = tree({ 'ios/Bipe/App.swift': 'import SwiftUI\n', 'android/Main.kt': HEADER.slice(0, 2).join('\n') + '\n' });
  assert.equal(checkHeaders(root).errors.length, 2);
});

test('headers accept the header after the swift-tools-version line of a Package.swift', () => {
  const root = tree({
    'ios/BipeKit/Package.swift': '// swift-tools-version:5.9\n' + H + 'import PackageDescription\n',
    'ios/Other/Package.swift': '// swift-tools-version:5.9\nimport PackageDescription\n',
  });
  const { errors } = checkHeaders(root);
  assert.equal(errors.length, 1);
  assert.ok(errors[0].startsWith('ios/Other/Package.swift'));
});

// Strings

test('strings pass generated strings, symbols and generated folders', () => {
  const root = tree({
    'ios/Bipe/View.swift': H + 'Text(Strings.chatsTitle)\nText("")\nText("•")\nText("\\(count)")\n// Text("Hello")\n.navigationTitle(L10n.title)\n',
    'android/View.kt': H + 'Text(stringResource(R.string.title))\nText(text = "$count")\nText("${a} · ${b}")\n',
    'ios/Bipe/Generated/Strings.swift': 'Text("Hello")\n',
  });
  assert.deepEqual(checkStrings(root).errors, []);
});

test('strings fail literals in SwiftUI and Compose', () => {
  const root = tree({
    'ios/Bipe/View.swift': H + 'Text("Olá")\nButton("Send") { send() }\nLabel("Chats", image: "x")\n.navigationTitle("Chats")\n',
    'android/View.kt': H + 'Text("Olá")\nText(text = "Enviar")\n',
  });
  assert.equal(checkStrings(root).errors.length, 6);
  assert.equal(isUserText('\\(n) itens'), true);
  assert.deepEqual(findLiterals('MyText("x")\nfoo.Text("y")\n', 'swift'), []);
});

// Provenance

test('provenance finds the line', () => {
  assert.equal(provenanceLine('Fixes x\n\nProvenance: ADR 0144\n'), 'ADR 0144');
});

test('provenance fails without the line or with an empty line', () => {
  assert.equal(provenanceLine('Fixes x\n'), null);
  assert.equal(provenanceLine('Provenance:   \n'), null);
  assert.equal(provenanceLine(' Provenance: indented\n'), null);
  assert.equal(provenanceLine(undefined), null);
});

// Similarity (CSV evaluation; the JPlag run itself is covered by the CI self-test)

const ours = flatNames(['ios/Bipe/A.swift', 'ios/Bipe/B.swift', 'ios/Bipe/Tiny.swift'], 'o');
const theirs = flatNames(['ElementX/Sources/X.swift'], 't');
const csv = (rows) =>
  parseCsv(['submissionName1,submissionName2,averageSimilarity,maxSimilarity', ...rows].join('\r\n') + '\r\n');

test('similarity passes pairs under the threshold', () => {
  const r = evaluate(csv(['ours/o00001_A.swift,theirs/t00001_X.swift,0.1,0.2', 'ours/o00002_B.swift,theirs/t00001_X.swift,0.3,0.4']), ours, theirs);
  assert.deepEqual(r.failures, []);
  assert.deepEqual(r.warnings, []);
  assert.deepEqual(r.notCompared, ['ios/Bipe/Tiny.swift']);
});

test('similarity fails a pair above the threshold and warns on max', () => {
  const r = evaluate(
    csv([
      'theirs/t00001_X.swift,ours/o00001_A.swift,0.75,0.9',
      'ours/o00002_B.swift,theirs/t00001_X.swift,0.3,0.6',
      'ours/o00001_A.swift,ours/o00002_B.swift,0.99,0.99',
      '"ours/o00003_Tiny.swift",theirs/t00001_X.swift,0.0,0.0',
    ]),
    ours,
    theirs,
  );
  // No token counts: a pair above the threshold fails.
  assert.deepEqual(r.failures, [{ ours: 'ios/Bipe/A.swift', theirs: 'ElementX/Sources/X.swift', avg: 0.75, max: 0.9, matched: Infinity }]);
  assert.equal(r.warnings.length, 1);
  assert.deepEqual(r.notCompared, []);
});

test('similarity warns, but does not fail, a pair above the threshold with few matched tokens', () => {
  const rows = csv(['ours/o00001_A.swift,theirs/t00001_X.swift,0.55,0.7', 'ours/o00002_B.swift,theirs/t00001_X.swift,0.6,0.8']);
  const tokens = new Map([['o00001_A.swift', 49], ['o00002_B.swift', 300], ['t00001_X.swift', 120]]);
  const r = evaluate(rows, ours, theirs, undefined, tokens);
  // A: 0.55 * (49 + 120) / 2 = 46 matched tokens. B: 0.6 * (300 + 120) / 2 = 126.
  assert.deepEqual(r.failures.map((p) => [p.ours, p.matched]), [['ios/Bipe/B.swift', 126]]);
  assert.deepEqual(r.warnings.map((p) => [p.ours, p.matched]), [['ios/Bipe/A.swift', 46]]);
});

test('similarity reads token counts from the JPlag file index', () => {
  const index = {
    fileIndexes: {
      'ours_o00001_A.swift': { 'ours_o00001_A.swift/ours_o00001_A.swift': { tokenCount: 40 } },
      't00002_X.swift': { 'x/a': { tokenCount: 10 }, 'x/b': { tokenCount: 5 } },
    },
  };
  assert.deepEqual([...tokenCounts(index)], [['o00001_A.swift', 40], ['t00002_X.swift', 15]]);
  assert.equal(matchedTokens(1, 100, 100), 100);
  assert.equal(matchedTokens(0.9, undefined, 100), Infinity);
});

test('similarity rejects an unknown CSV header', () => {
  assert.throws(() => evaluate([['a', 'b']], ours, theirs));
});

// SDK version

const SDK_FILES = {
  'sdk-version': '26.08.11\n',
  'ios/project.yml':
    'packages:\n  MatrixRustSDK:\n    url: https://github.com/matrix-org/matrix-rust-components-swift\n    exactVersion: 26.08.11\n  BipeKit:\n    path: BipeKit\n',
  'ios/Package.resolved': JSON.stringify({
    pins: [{ identity: 'matrix-rust-components-swift', state: { revision: 'abc', version: '26.08.11' } }],
    version: 2,
  }),
  'android/gradle/libs.versions.toml': '[versions]\nmatrix-rust-sdk = "26.08.11"\n\n[libraries]\nsdk = { module = "org.matrix.rustcomponents:sdk-android", version.ref = "matrix-rust-sdk" }\n',
};

test('sdk-version passes when every pin equals sdk-version', () => {
  assert.deepEqual(checkSdkVersion(tree(SDK_FILES)).errors, []);
});

test('sdk-version fails on a different pin and on a missing file', () => {
  const { 'ios/Package.resolved': _resolved, ...rest } = SDK_FILES;
  const root = tree({ ...rest, 'android/gradle/libs.versions.toml': '[versions]\nmatrix-rust-sdk = "26.10.20"\n' });
  const { errors } = checkSdkVersion(root);
  assert.equal(errors.length, 2);
  assert.ok(errors.some((e) => e.includes('26.10.20')));
  assert.ok(errors.some((e) => e.startsWith('ios/Package.resolved is missing')));
});

test('sdk-version fails without the sdk-version file', () => {
  assert.equal(checkSdkVersion(tree({})).errors.length, 1);
});

test('sdk-version parsers read each pin form', () => {
  assert.equal(projectPin(SDK_FILES['ios/project.yml']), '26.08.11');
  assert.equal(projectPin('packages:\n  Other:\n    exactVersion: 1.0\n'), null);
  assert.equal(resolvedPin(SDK_FILES['ios/Package.resolved']), '26.08.11');
  assert.equal(catalogPin('[libraries]\nmatrix-rust-sdk = "1"\n[versions]\nmatrix-rust-sdk = "2" # pin\n'), '2');
});

test('asc token is an ES256 JWT that the public key verifies', () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const key = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const jwt = token({ keyId: 'KEY1', issuerId: 'issuer', key, now: 1000 });
  const [h, p, s] = jwt.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(h, 'base64url')), { alg: 'ES256', kid: 'KEY1', typ: 'JWT' });
  assert.deepEqual(JSON.parse(Buffer.from(p, 'base64url')), { iss: 'issuer', iat: 1000, exp: 2200, aud: 'appstoreconnect-v1' });
  const ok = verify('sha256', Buffer.from(`${h}.${p}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url'));
  assert.ok(ok);
});

test('asc exact ignores prefix matches', () => {
  const rows = [{ attributes: { identifier: 'chat.bipe.app.next.nse' } }, { attributes: { identifier: 'chat.bipe.app.next' } }];
  assert.equal(exact(rows, 'identifier', 'chat.bipe.app.next'), rows[1]);
  assert.equal(exact(rows, 'identifier', 'chat.bipe.app'), null);
});

test('asc sameSerial ignores case and leading zeros', () => {
  assert.ok(sameSerial('105a9943', '00105A9943'));
  assert.ok(!sameSerial('105A9943', '105A9944'));
  assert.ok(!sameSerial('', ''));
  assert.ok(!sameSerial(undefined, '0'));
});

test('asc export options sign manually with the CI profiles', () => {
  const adhoc = exportOptions('adhoc');
  assert.match(adhoc, /<key>method<\/key><string>release-testing<\/string>/);
  assert.match(adhoc, /<key>signingStyle<\/key><string>manual<\/string>/);
  assert.match(adhoc, /<key>chat\.bipe\.app\.next\.share<\/key><string>Bipe Next CI Ad Hoc chat\.bipe\.app\.next\.share<\/string>/);
  assert.doesNotMatch(adhoc, /destination<\/key><string>upload/);
  const upload = exportOptions('appstore');
  assert.match(upload, /<key>destination<\/key><string>upload<\/string>/);
  assert.match(upload, new RegExp(profileName('appstore', 'chat.bipe.app.next.nse').replace(/\./g, '\\.')));
});

// A fake App Store Connect API: records the calls, answers from fixed rows.
function fakeAsc({ certs, devices = [{ id: 'D1' }], oldProfiles = [] }) {
  const calls = [];
  let n = 0;
  const call = async (method, path, body) => {
    calls.push({ method, path, body });
    const p = path.split('?')[0];
    if (method === 'GET' && p === '/v1/certificates') return { data: certs };
    if (method === 'GET' && p === '/v1/devices') return { data: devices };
    if (method === 'GET' && p === '/v1/bundleIds') {
      const identifier = decodeURIComponent(path.match(/identifier\]=([^&]+)/)[1]);
      return { data: [{ id: `B-${identifier}`, attributes: { identifier } }] };
    }
    if (method === 'GET' && p.endsWith('/bundleIdCapabilities')) {
      return { data: ['APP_GROUPS', 'PUSH_NOTIFICATIONS', 'ASSOCIATED_DOMAINS'].map((t) => ({ attributes: { capabilityType: t } })) };
    }
    if (method === 'GET' && p === '/v1/profiles') {
      const name = decodeURIComponent(path.match(/name\]=([^&]+)/)[1]);
      return { data: oldProfiles.filter((r) => r.attributes.name.startsWith(name)) };
    }
    if (method === 'DELETE') return {};
    if (method === 'POST' && p === '/v1/profiles') {
      n += 1;
      return { data: { id: `P${n}`, attributes: { uuid: `uuid-${n}`, profileContent: Buffer.from(`profile ${n}`).toString('base64') } } };
    }
    throw new Error(`unexpected ${method} ${path}`);
  };
  return { call, calls };
}

test('asc signing makes six profiles for the given certificate and never makes a certificate', async () => {
  const certs = [
    { id: 'C-old', attributes: { serialNumber: '0AAA', certificateType: 'DISTRIBUTION' } },
    { id: 'C-dist', attributes: { serialNumber: '105A9943', certificateType: 'DISTRIBUTION', name: 'Apple Distribution' } },
  ];
  const oldProfiles = [
    { id: 'old-1', attributes: { name: 'Bipe Next CI Ad Hoc chat.bipe.app.next' } },
    // A prefix match of the app's name: only the NSE pass deletes it.
    { id: 'old-nse', attributes: { name: 'Bipe Next CI Ad Hoc chat.bipe.app.next.nse' } },
  ];
  const { call, calls } = fakeAsc({ certs, oldProfiles });
  const home = mkdtempSync(join(tmpdir(), 'bipe-asc-home-'));
  const outDir = mkdtempSync(join(tmpdir(), 'bipe-asc-out-'));
  const envFile = join(outDir, 'env');
  writeFileSync(envFile, '');
  await signing(call, '0105a9943', { home, outDir, env: { GITHUB_ENV: envFile } });

  assert.ok(!calls.some((c) => c.method === 'POST' && c.path.startsWith('/v1/certificates')));
  const made = calls.filter((c) => c.method === 'POST' && c.path === '/v1/profiles').map((c) => c.body.data);
  assert.equal(made.length, 6);
  assert.ok(made.every((d) => d.relationships.certificates.data[0].id === 'C-dist'));
  assert.deepEqual(made.filter((d) => d.attributes.profileType === 'IOS_APP_ADHOC').map((d) => d.relationships.devices.data), [[{ type: 'devices', id: 'D1' }], [{ type: 'devices', id: 'D1' }], [{ type: 'devices', id: 'D1' }]]);
  assert.ok(made.filter((d) => d.attributes.profileType === 'IOS_APP_STORE').every((d) => !d.relationships.devices));
  const order = calls.map((c) => `${c.method} ${c.method === 'POST' ? c.body.data.attributes.name : c.path}`);
  assert.deepEqual(order.filter((c) => c.startsWith('DELETE')), ['DELETE /v1/profiles/old-1', 'DELETE /v1/profiles/old-nse']);
  assert.ok(order.indexOf('DELETE /v1/profiles/old-nse') > order.indexOf('POST Bipe Next CI Ad Hoc chat.bipe.app.next'));
  assert.ok(order.indexOf('DELETE /v1/profiles/old-nse') < order.indexOf('POST Bipe Next CI Ad Hoc chat.bipe.app.next.nse'));

  assert.equal(readdirSync(join(home, 'Library/Developer/Xcode/UserData/Provisioning Profiles')).length, 6);
  assert.equal(readdirSync(join(home, 'Library/MobileDevice/Provisioning Profiles')).length, 6);
  assert.match(readFileSync(join(outDir, 'adhoc.plist'), 'utf8'), /release-testing/);
  assert.match(readFileSync(join(outDir, 'upload.plist'), 'utf8'), /app-store-connect/);
  assert.equal(readFileSync(envFile, 'utf8'), [
    'BIPE_CI_PROFILE_APP=Bipe Next CI App Store chat.bipe.app.next',
    'BIPE_CI_PROFILE_NSE=Bipe Next CI App Store chat.bipe.app.next.nse',
    'BIPE_CI_PROFILE_SHARE=Bipe Next CI App Store chat.bipe.app.next.share',
    '',
  ].join('\n'));
});

test('asc signing fails without the certificate or without devices', async () => {
  const cert = { id: 'C', attributes: { serialNumber: '1', certificateType: 'DISTRIBUTION' } };
  const home = mkdtempSync(join(tmpdir(), 'bipe-asc-home-'));
  await assert.rejects(signing(fakeAsc({ certs: [cert] }).call, '2', { home, outDir: home, env: {} }), /no distribution certificate/);
  await assert.rejects(signing(fakeAsc({ certs: [cert], devices: [] }).call, '1', { home, outDir: home, env: {} }), /no enabled iOS devices/);
});

test('asc certificates lists development certificates only', async () => {
  const rows = [
    { attributes: { certificateType: 'DEVELOPMENT', name: 'Created via API', expirationDate: '2027-10-22T23:00:00.000+00:00' } },
    { attributes: { certificateType: 'DISTRIBUTION', name: 'Apple Distribution' } },
  ];
  const log = console.log;
  console.log = () => {};
  try {
    const dev = await certificates(async () => ({ data: rows }));
    assert.equal(dev.length, 1);
  } finally {
    console.log = log;
  }
});

test('asc store-ids reads the fork store app only and changes nothing', async () => {
  const calls = [];
  const rows = {
    '/v1/bundleIds?filter[identifier]=chat.bipe.app&limit=200': [
      { id: 'B1', attributes: { identifier: 'chat.bipe.app', name: 'Bipe' } },
      { id: 'B2', attributes: { identifier: 'chat.bipe.app.nse', name: 'Bipe NSE' } },
      { id: 'B3', attributes: { identifier: 'chat.bipe.app.next', name: 'Bipe Next' } },
      { id: 'B4', attributes: { identifier: 'chat.bipe.app.next.nse', name: 'Bipe Next NSE' } },
      { id: 'B5', attributes: { identifier: 'chat.bipe.appother', name: 'Other' } },
    ],
    '/v1/bundleIds/B1/bundleIdCapabilities': [{ attributes: { capabilityType: 'PUSH_NOTIFICATIONS' } }, { attributes: { capabilityType: 'APP_GROUPS' } }],
    '/v1/bundleIds/B2/bundleIdCapabilities': [{ attributes: { capabilityType: 'APP_GROUPS' } }],
    '/v1/apps?filter[bundleId]=chat.bipe.app': [
      { id: 'A0', attributes: { bundleId: 'chat.bipe.app.next', name: 'Bipe Next', sku: 'bipe-next' } },
      { id: 'A1', attributes: { bundleId: 'chat.bipe.app', name: 'Bipe', sku: 'bipe' } },
    ],
    '/v1/builds?filter[app]=A1&sort=-uploadedDate&limit=200': [{ attributes: { version: '207' } }, { attributes: { version: '1024' } }, { attributes: { version: 'x' } }],
    '/v1/apps/A1/appStoreVersions?limit=10': [{ attributes: { versionString: '1.4', appStoreState: 'READY_FOR_SALE' } }],
  };
  const call = async (method, path) => {
    calls.push(`${method} ${path}`);
    if (!(path in rows)) throw new Error(`unexpected ${method} ${path}`);
    return { data: rows[path] };
  };
  const dir = mkdtempSync(join(tmpdir(), 'bipe-asc-summary-'));
  const summary = join(dir, 'summary.md');
  const log = console.log;
  console.log = () => {};
  try {
    const report = await storeIds(call, { summary });
    assert.deepEqual(report.bundleIds.map((b) => b.identifier), ['chat.bipe.app', 'chat.bipe.app.nse']);
    assert.deepEqual(report.bundleIds[0].capabilities, ['APP_GROUPS', 'PUSH_NOTIFICATIONS']);
    assert.deepEqual(report.app, { id: 'A1', name: 'Bipe', sku: 'bipe' });
    assert.equal(report.lastBuild, 1024);
    assert.deepEqual(report.versions, ['1.4 (READY_FOR_SALE)']);
    assert.ok(calls.every((c) => c.startsWith('GET ')));
    assert.match(readFileSync(summary, 'utf8'), /Last build number: 1024/);
  } finally {
    console.log = log;
  }
});

test('asc store set signs the fork app with App Store profiles only and changes no bundle ID', async () => {
  const cert = { id: 'C', attributes: { serialNumber: '105A9943', certificateType: 'DISTRIBUTION' } };
  const { call, calls } = fakeAsc({ certs: [cert], devices: [] });
  const home = mkdtempSync(join(tmpdir(), 'bipe-asc-home-'));
  const outDir = mkdtempSync(join(tmpdir(), 'bipe-asc-out-'));
  const envFile = join(outDir, 'env');
  writeFileSync(envFile, '');
  const log = console.log;
  console.log = () => {};
  try {
    await signing(call, '105A9943', { home, outDir, env: { GITHUB_ENV: envFile }, set: idSet('store') });
  } finally {
    console.log = log;
  }
  const writes = calls.filter((c) => c.method !== 'GET');
  assert.ok(writes.every((c) => c.path.startsWith('/v1/profiles')), 'only profiles change');
  assert.ok(!calls.some((c) => c.path.startsWith('/v1/devices')));
  const made = writes.filter((c) => c.method === 'POST').map((c) => c.body.data.attributes);
  assert.deepEqual(made.map((a) => a.profileType), ['IOS_APP_STORE', 'IOS_APP_STORE', 'IOS_APP_STORE']);
  assert.deepEqual(made.map((a) => a.name), STORE.bundleIds.map((b) => `Bipe CI App Store ${b.identifier}`));
  assert.match(readFileSync(join(outDir, 'upload.plist'), 'utf8'), /<key>chat\.bipe\.app\.nse<\/key><string>Bipe CI App Store chat\.bipe\.app\.nse<\/string>/);
  assert.throws(() => readFileSync(join(outDir, 'adhoc.plist')));
  assert.equal(readFileSync(envFile, 'utf8'), [
    'BIPE_CI_PROFILE_APP=Bipe CI App Store chat.bipe.app',
    'BIPE_CI_PROFILE_NSE=Bipe CI App Store chat.bipe.app.nse',
    'BIPE_CI_PROFILE_SHARE=Bipe CI App Store chat.bipe.app.shareextension',
    '',
  ].join('\n'));
});

test('asc store set never registers a bundle ID or creates the app record', async () => {
  const calls = [];
  const call = async (method, path) => {
    calls.push(`${method} ${path}`);
    if (path.startsWith('/v1/bundleIds?')) return { data: [] };
    throw new Error(`unexpected ${method} ${path}`);
  };
  await assert.rejects(prepare(call, idSet('store')), /no bundle ID chat\.bipe\.app in team/);
  assert.ok(calls.every((c) => c.startsWith('GET ')));
  const withIds = async (method, path) => {
    calls.push(`${method} ${path}`);
    if (path.startsWith('/v1/bundleIds?')) {
      const identifier = decodeURIComponent(path.match(/identifier\]=([^&]+)/)[1]);
      return { data: [{ id: `B-${identifier}`, attributes: { identifier } }] };
    }
    if (path.startsWith('/v1/apps?')) return { data: [] };
    throw new Error(`unexpected ${method} ${path}`);
  };
  const log = console.log;
  console.log = () => {};
  try {
    await assert.rejects(prepare(withIds, idSet('store')), /no App Store Connect app for chat\.bipe\.app/);
  } finally {
    console.log = log;
  }
  assert.ok(calls.every((c) => c.startsWith('GET ')));
  assert.throws(() => idSet('production'), /unknown ID set/);
});

test('version: build number is the base plus the commit count, above the highest build tag', async () => {
  const { parseVersionFile, highestTag, buildNumber } = await import('./version.mjs');
  assert.deepEqual(parseVersionFile('{"version": "26.10.2", "build": 12}'), { version: '26.10.2', base: 12 });
  assert.throws(() => parseVersionFile('{"version": "26.10", "build": 12}'));
  assert.throws(() => parseVersionFile('{"version": "26.10.2", "build": 0}'));
  assert.equal(highestTag(['builds/next/32', 'builds/next/132', 'v1', 'builds/next/x']), 132);
  assert.equal(highestTag([]), 0);
  assert.equal(buildNumber({ base: 12, commits: 129, tags: ['builds/next/132'] }), 141);
  assert.throws(() => buildNumber({ base: 12, commits: 120, tags: ['builds/next/132'] }), /not above/);
});

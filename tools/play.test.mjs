// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { accessToken, assertion, check, client, highestVersionCode, listingPlan, printPlan, sendListing, upload, API, SCOPE, UPLOAD_API } from './play.mjs';

const PKG = 'chat.bipe.app';
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const account = {
  clientEmail: 'ci@synthetic.iam.gserviceaccount.com',
  privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  tokenUri: 'https://oauth2.example.test/token',
};

test('play assertion is an RS256 JWT for the androidpublisher scope that the public key verifies', () => {
  const jwt = assertion({ ...account, now: 1000 });
  const [h, p, s] = jwt.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(h, 'base64url')), { alg: 'RS256', typ: 'JWT' });
  assert.deepEqual(JSON.parse(Buffer.from(p, 'base64url')), { iss: account.clientEmail, scope: SCOPE, aud: account.tokenUri, iat: 1000, exp: 4600 });
  assert.ok(verify('sha256', Buffer.from(`${h}.${p}`), publicKey, Buffer.from(s, 'base64url')));
});

test('play access token uses the JWT bearer grant and fails closed', async () => {
  let sent;
  const ok = async (url, init) => {
    sent = { url, init };
    return new Response(JSON.stringify({ access_token: 'synthetic-token' }), { status: 200 });
  };
  assert.equal(await accessToken(account, ok), 'synthetic-token');
  assert.equal(sent.url, account.tokenUri);
  assert.equal(new URLSearchParams(sent.init.body).get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  const refused = async () => new Response(JSON.stringify({ error: 'invalid_grant' }), { status: 400 });
  await assert.rejects(accessToken(account, refused), /token request failed: 400 invalid_grant/);
});

// A fake Play API: records calls, answers from a table, fails on anything else.
function fakePlay(answers) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ method: init.method, url, headers: init.headers, body: init.body });
    const key = `${init.method} ${url}`;
    if (!(key in answers)) return new Response(JSON.stringify({ error: { message: `unexpected ${key}` } }), { status: 500 });
    const answer = answers[key];
    return new Response(answer === null ? '' : JSON.stringify(answer), { status: 200 });
  };
  return { call: client('synthetic-token', fetchImpl), calls };
}

const base = `${API}/applications/${PKG}`;

test('play check reads bundles and APKs of a throwaway edit and needs a higher versionCode', async () => {
  const answers = {
    [`POST ${base}/edits`]: { id: 'E1' },
    [`GET ${base}/edits/E1/bundles`]: { bundles: [{ versionCode: 410 }, { versionCode: 702 }] },
    [`GET ${base}/edits/E1/apks`]: { apks: [{ versionCode: 9 }] },
    [`DELETE ${base}/edits/E1`]: null,
  };
  const log = console.log;
  console.log = () => {};
  try {
    const { call, calls } = fakePlay(answers);
    assert.equal(await highestVersionCode(call, PKG), 702);
    assert.ok(!calls.some((c) => c.url.endsWith(':commit')));
    assert.equal(calls.at(-1).method, 'DELETE');
    assert.equal(calls[0].headers.Authorization, 'Bearer synthetic-token');
    assert.equal(await check(fakePlay(answers).call, PKG, 703), 702);
    await assert.rejects(check(fakePlay(answers).call, PKG, 702), /not higher than 702/);
  } finally {
    console.log = log;
  }
});

test('play check of a new app with nothing uploaded accepts any versionCode', async () => {
  const { call } = fakePlay({
    [`POST ${base}/edits`]: { id: 'E2' },
    [`GET ${base}/edits/E2/bundles`]: {},
    [`GET ${base}/edits/E2/apks`]: {},
    [`DELETE ${base}/edits/E2`]: null,
  });
  assert.equal(await highestVersionCode(call, PKG), 0);
});

test('play upload sends the AAB, sets the internal track and commits', async () => {
  const aab = Buffer.from('synthetic aab');
  const { call, calls } = fakePlay({
    [`POST ${base}/edits`]: { id: 'E3' },
    [`POST ${UPLOAD_API}/applications/${PKG}/edits/E3/bundles?uploadType=media`]: { versionCode: 703, sha256: 'x' },
    [`PUT ${base}/edits/E3/tracks/internal`]: {},
    [`POST ${base}/edits/E3:commit`]: { id: 'E3' },
  });
  const log = console.log;
  console.log = () => {};
  try {
    assert.equal(await upload(call, PKG, aab, 'internal', { releaseName: '26.10.2 (703)' }), 703);
  } finally {
    console.log = log;
  }
  assert.deepEqual(calls.map((c) => c.method), ['POST', 'POST', 'PUT', 'POST']);
  assert.equal(calls[1].headers['Content-Type'], 'application/octet-stream');
  assert.equal(calls[1].body, aab);
  assert.deepEqual(JSON.parse(calls[2].body), {
    track: 'internal',
    releases: [{ name: '26.10.2 (703)', versionCodes: ['703'], status: 'completed' }],
  });
});

test('play upload refuses tracks other than internal and discards a failed edit', async () => {
  await assert.rejects(upload(fakePlay({}).call, PKG, Buffer.alloc(1), 'production'), /track production is not allowed/);
  const { call, calls } = fakePlay({
    [`POST ${base}/edits`]: { id: 'E4' },
    [`DELETE ${base}/edits/E4`]: null,
  });
  await assert.rejects(upload(call, PKG, Buffer.alloc(1), 'internal'), /failed: 500/);
  assert.equal(calls.at(-1).method, 'DELETE');
  assert.ok(!calls.some((c) => c.url.endsWith(':commit')));
});

// Store listing (bipe-chat/bipe#457). Synthetic listing and images; the real text is checked in bipe-shared.
const ROWS = [{ slug: 'aviso-golpe' }, { slug: 'pedidos' }];
const synthetic = {
  listing: { screenshots: { 'pt-BR': ROWS, 'en-US': ROWS } },
  text: (locale) => ({ language: locale, title: 'Bipe Chat', shortDescription: `short ${locale}`, fullDescription: `full ${locale}` }),
};

function images({ capture = 'play-phone' } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'play-listing-'));
  for (const locale of ['pt-BR', 'en-US']) {
    const write = (set, exports) => {
      mkdirSync(join(dir, set, locale), { recursive: true });
      for (const e of exports) writeFileSync(join(dir, set, locale, e.file), 'png');
      writeFileSync(join(dir, set, locale, 'manifest.json'), JSON.stringify({ exports }));
    };
    write('play-phone', ROWS.map((r, i) => ({ order: i + 1, slug: r.slug, file: `0${i + 1}-${r.slug}.png`, title: r.slug, sub: null, capture: `${capture}/light/${locale}/${r.slug}.png` })));
    write('play-feature', [{ order: 1, slug: 'feature-graphic', file: '01-feature-graphic.png', capture: `${capture}/light/${locale}/aviso-golpe.png` }]);
  }
  return dir;
}

test('play listing plan has the text, the panels in table order and the feature graphic per locale', () => {
  const plan = listingPlan(synthetic, images());
  assert.deepEqual(plan.problems, []);
  assert.deepEqual(plan.locales.map((l) => l.locale), ['pt-BR', 'en-US']);
  assert.deepEqual(plan.locales[0].phoneScreenshots.map((e) => e.slug), ['aviso-golpe', 'pedidos']);
  assert.equal(plan.locales[1].featureGraphic.length, 1);
  const lines = [];
  printPlan(plan, PKG, (l) => lines.push(l));
  assert.ok(lines.includes('  title: Bipe Chat'));
  assert.ok(lines.some((l) => l.startsWith('    2. pedidos')));
});

test('play listing plan refuses iPhone captures and missing images', () => {
  const plan = listingPlan(synthetic, images({ capture: 'iphone69' }));
  assert.ok(plan.problems.some((p) => p.includes('not a native Android capture')));
  const empty = listingPlan(synthetic, mkdtempSync(join(tmpdir(), 'play-empty-')));
  assert.ok(empty.problems.some((p) => p.includes('no play-phone images')));
  const long = listingPlan({ ...synthetic, text: (locale) => ({ ...synthetic.text(locale), title: 'x'.repeat(31) }) }, images());
  assert.ok(long.problems.some((p) => p.includes('title has 31 characters, limit 30')));
});

test('play listing sends text and images in one edit and commits', async () => {
  const plan = listingPlan(synthetic, images());
  const answers = { [`POST ${base}/edits`]: { id: 'E5' }, [`POST ${base}/edits/E5:commit`]: { id: 'E5' } };
  for (const locale of ['pt-BR', 'en-US']) {
    answers[`PUT ${base}/edits/E5/listings/${locale}`] = {};
    for (const type of ['phoneScreenshots', 'featureGraphic']) {
      answers[`DELETE ${base}/edits/E5/listings/${locale}/${type}`] = {};
      answers[`POST ${UPLOAD_API}/applications/${PKG}/edits/E5/listings/${locale}/${type}?uploadType=media`] = { image: {} };
    }
  }
  const { call, calls } = fakePlay(answers);
  const log = console.log;
  console.log = () => {};
  try {
    await sendListing(call, PKG, plan, { read: () => Buffer.from('png') });
  } finally {
    console.log = log;
  }
  assert.equal(calls.at(-1).url, `${base}/edits/E5:commit`);
  assert.deepEqual(JSON.parse(calls[1].body), synthetic.text('pt-BR'));
  const uploads = calls.filter((c) => c.url.includes('/upload/'));
  assert.equal(uploads.length, 6);
  assert.equal(uploads[0].headers['Content-Type'], 'image/png');
});

test('play listing never sends a plan with problems and discards a failed edit', async () => {
  const bad = listingPlan(synthetic, images({ capture: 'iphone69' }));
  const none = fakePlay({});
  await assert.rejects(sendListing(none.call, PKG, bad), /listing not sent/);
  assert.equal(none.calls.length, 0);
  const { call, calls } = fakePlay({ [`POST ${base}/edits`]: { id: 'E6' }, [`DELETE ${base}/edits/E6`]: null });
  await assert.rejects(sendListing(call, PKG, listingPlan(synthetic, images()), { read: () => Buffer.alloc(1) }), /failed: 500/);
  assert.equal(calls.at(-1).method, 'DELETE');
  assert.ok(!calls.some((c) => c.url.endsWith(':commit')));
});

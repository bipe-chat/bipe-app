// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadWindowsOAuth, knownMacClientIds, validateWindowsOAuth, writeWindowsOAuthKotlin } from './windows-oauth.mjs';

const base = () => structuredClone(loadWindowsOAuth());
const pending = () => ({ ...base(), status: 'pending', clientId: null, registeredOn: null, verifiedOn: null });
const registered = () => ({ ...base(), status: 'registered', clientId: '01K00000000000000000000001', registeredOn: '2026-11-10', verifiedOn: null });
const verified = () => ({ ...registered(), status: 'verified', verifiedOn: '2026-11-11' });

test('pending metadata is source-valid but cannot produce a usable distribution', () => {
  const record = pending();
  assert.equal(record.status, 'pending');
  assert.deepEqual(validateWindowsOAuth(record), []);
  assert.ok(validateWindowsOAuth(record, { requireVerified: true }).length);
  assert.ok(validateWindowsOAuth(record, { requestedId: '01K00000000000000000000001' }).length);
});

test('a verified server registration enables source QR sign-in while distribution still requires its native callback', () => {
  const record = registered();
  assert.deepEqual(validateWindowsOAuth(record, { requestedId: record.clientId }), []);
  assert.ok(validateWindowsOAuth(record, { requireVerified: true }).length);
  assert.ok(validateWindowsOAuth({ ...record, verifiedOn: '2026-11-11' }).length);
  assert.ok(validateWindowsOAuth({ ...record, registeredOn: '2026-02-30' }).length);
  assert.ok(validateWindowsOAuth(record, { requestedId: '01K00000000000000000000002' }).length);
  for (const clientId of knownMacClientIds()) assert.ok(validateWindowsOAuth({ ...record, clientId }, { forbiddenIds: knownMacClientIds() }).length);
});

test('a verified public record must match the requested release id and remain separate from Mac', () => {
  const record = verified();
  assert.deepEqual(validateWindowsOAuth(record, { requireVerified: true, requestedId: record.clientId }), []);
  assert.ok(validateWindowsOAuth(record, { requestedId: '01K00000000000000000000002' }).length);
  for (const clientId of knownMacClientIds()) assert.ok(validateWindowsOAuth({ ...record, clientId }, { forbiddenIds: knownMacClientIds() }).length);
});

test('private clients, wrong callbacks/grants and unexpected fields are refused', () => {
  for (const mutate of [
    r => { r.metadata.client_secret = 'synthetic-secret'; },
    r => { r.metadata.token_endpoint_auth_method = 'client_secret_post'; },
    r => { r.metadata.redirect_uris = ['chat.bipe.app.mac:/callback']; },
    r => { r.metadata.redirect_uris.push('https://example.test/callback'); },
    r => { r.metadata.application_type = 'web'; },
    r => { r.metadata.grant_types.push('password'); },
    r => { r.issuer = 'https://example.test/'; },
    r => { r.status = 'ready'; },
    r => { r.clientId = 'invalid'; },
    r => { r.verifiedOn = '2026-02-30'; },
  ]) {
    const record = verified(); mutate(record);
    assert.ok(validateWindowsOAuth(record, { requireVerified: true }).length);
  }
});

test('SDK constants are generated from the public registry without a client secret or id fallback', () => {
  const output = mkdtempSync(join(tmpdir(), 'bipe-windows-oauth-'));
  try {
    writeWindowsOAuthKotlin(output);
    const source = readFileSync(join(output, 'WindowsOAuthMetadata.kt'), 'utf8');
    const record = base();
    for (const value of [record.metadata.redirect_uris[0], record.metadata.client_name, record.issuer, record.homeserver]) assert.ok(source.includes(value));
    assert.doesNotMatch(source, /CLIENT_ID|CLIENT_SECRET/);
  } finally { rmSync(output, { recursive: true }); }
});

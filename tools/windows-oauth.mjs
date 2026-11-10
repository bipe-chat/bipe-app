// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Public metadata validation/code generation only. Never registers a client, calls MAS, builds or deploys.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { REPO_ROOT, isMain } from './lib.mjs';

export function loadWindowsOAuth(root = REPO_ROOT) {
  return JSON.parse(readFileSync(join(root, 'windows/oauth-client.json'), 'utf8'));
}

export function knownMacClientIds(root = REPO_ROOT) {
  const source = readFileSync(join(root, 'macos/BipeMac/MacAccount.swift'), 'utf8');
  const ids = [...source.matchAll(/let clientID = "([A-Z0-9]+)"/g)].map(m => m[1]);
  if (!ids.length) throw new Error('Cannot verify the separate Mac registration; review its public metadata source');
  return ids;
}

export function validateWindowsOAuth(record, { forbiddenIds = [], requireVerified = false, requestedId } = {}) {
  const failures = [];
  const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const keys = (value, allowed, label) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) { failures.push(`${label}: object required`); return; }
    for (const key of Object.keys(value)) if (!allowed.includes(key)) failures.push(`${label}: unexpected field ${key}`);
  };
  keys(record, ['$comment', 'schema', 'status', 'clientId', 'verifiedOn', 'issuer', 'homeserver', 'metadata'], 'registration');
  if (record?.schema !== 1) failures.push('Unsupported registration schema');
  if (!['pending', 'verified'].includes(record?.status)) failures.push('Registration status must be pending or verified');
  if (record?.issuer !== 'https://account.bipe.net.br/' || record?.homeserver !== 'https://matrix.bipe.net.br') failures.push('Unexpected Bipe issuer/homeserver');
  const m = record?.metadata;
  keys(m, ['client_name', 'client_uri', 'application_type', 'token_endpoint_auth_method', 'redirect_uris', 'grant_types', 'response_types'], 'public metadata');
  if (m?.client_name !== 'Bipe (Windows)' || m?.client_uri !== 'https://bipe.chat') failures.push('Unexpected Windows client identity');
  if (m?.application_type !== 'native' || m?.token_endpoint_auth_method !== 'none') failures.push('Windows must be a native public client without a secret');
  if (!equal(m?.redirect_uris, ['chat.bipe.app.windows:/callback'])) failures.push('Windows callback must be exclusive to its native scheme');
  if (!equal(m?.grant_types, ['authorization_code', 'refresh_token', 'urn:ietf:params:oauth:grant-type:device_code']) ||
      !equal(m?.response_types, ['code'])) failures.push('Unexpected native grant/response types');
  if (record?.status === 'pending') {
    if (record.clientId !== null || record.verifiedOn !== null) failures.push('Pending registration cannot claim an id or verification');
    if (requireVerified || requestedId) failures.push('Windows MAS registration/callback has not been verified');
  }
  if (record?.status === 'verified') {
    if (!/^[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(record.clientId ?? '')) failures.push('Invalid MAS public client id');
    const date = Date.parse(record.verifiedOn);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(record.verifiedOn ?? '') || Number.isNaN(date) ||
        new Date(date).toISOString().slice(0, 10) !== record.verifiedOn) failures.push('Verification date required');
    if (forbiddenIds.includes(record.clientId)) failures.push('A Mac/web client id cannot be reused for Windows');
    if (requestedId && requestedId !== record.clientId) failures.push('Environment and verified Windows registration disagree');
  }
  return failures;
}

export function windowsOAuthConfig({ root = REPO_ROOT, requireVerified = false,
                                    requestedId = process.env.BIPE_WINDOWS_OAUTH_CLIENT_ID } = {}) {
  const record = loadWindowsOAuth(root);
  const failures = validateWindowsOAuth(record, { requireVerified, requestedId, forbiddenIds: knownMacClientIds(root) });
  if (failures.length) throw new Error(failures.join('; '));
  return record;
}

export function writeWindowsOAuthKotlin(output, root = REPO_ROOT) {
  const record = windowsOAuthConfig({ root, requestedId: undefined });
  const literal = value => JSON.stringify(value).replaceAll('$', '\\$');
  const values = { CLIENT_NAME: record.metadata.client_name, CLIENT_URI: record.metadata.client_uri,
    REDIRECT_URI: record.metadata.redirect_uris[0], ISSUER: record.issuer, HOMESERVER: record.homeserver };
  mkdirSync(output, { recursive: true });
  writeFileSync(join(output, 'WindowsOAuthMetadata.kt'),
    '// SPDX-License-Identifier: AGPL-3.0-only\n// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)\n// Copyright 2026 Bipe contributors\n\n' +
    '// Generated from windows/oauth-client.json. Do not edit.\npackage chat.bipe.desktop.link\n\ninternal object WindowsOAuthMetadata {\n' +
    Object.entries(values).map(([key, value]) => `    const val ${key} = ${literal(value)}`).join('\n') + '\n}\n');
}

if (isMain(import.meta.url)) {
  if (process.argv.includes('--kotlin')) writeWindowsOAuthKotlin(resolve(process.argv[process.argv.indexOf('--kotlin') + 1]));
  else {
    const record = windowsOAuthConfig({ requireVerified: process.argv.includes('--release') });
    console.log(`windows-oauth: public metadata valid; registration ${record.status}`);
  }
}

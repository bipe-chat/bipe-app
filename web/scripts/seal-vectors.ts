// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The web client's seal path against bipe-shared vectors (bipe-chat/bipe#1457): the pinned bipe-core web bundle
// (scripts/fetch-core.sh) through src/seal-rules.ts, the code the page runs. web-vectors.yml builds it with
// `vite build --ssr` and runs it in Node.
// Usage: node build/vectors/seal-vectors.js <bipe-shared/test-vectors> <src/core/bipe_core.wasm>
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { uniffiInitAsync } from '../src/core';
import { ListType, newestList, personProofsOf, releaseTrust, schemeName, verifyBadge } from '../src/seal-rules';

const [dir, wasm] = process.argv.slice(2);
if (!dir || !wasm) throw new Error('usage: seal-vectors <test-vectors dir> <bipe_core.wasm>');
await uniffiInitAsync(pathToFileURL(wasm));
const read = (file: string) => JSON.parse(readFileSync(join(dir, file), 'utf8'));
let checked = 0;
const expect = (name: string, got: unknown, want: unknown) => {
  if (got !== want) throw new Error(`${name}: ${String(got)}, expected ${String(want)}`);
  checked++;
};

// badge-issuers.json: every badge through the issuer registry, then the flat keys of a verifier before ADR 0272
// (the test badge key of badges.json), then the zz badges against the issuer registry this build bundles.
const badges = read('badges.json');
const issuers = read('badge-issuers.json');
const registry = JSON.stringify({ issuers: issuers.trustedIssuers, schemes: issuers.schemes, regions: issuers.regions });
const now = BigInt(issuers.now);
for (const vector of issuers.vectors) {
  const check = verifyBadge(JSON.stringify(vector.envelope), vector.sender, vector.senderMasterKey ?? undefined, { registry }, now);
  expect(`issued ${vector.name}`, check.code, vector.expect);
  // A valid person badge gives a proof with a scheme name, or none when the scheme has no name (fail closed).
  if (check.badge && check.badge.claims.scheme && issuers.schemes.some((s: { id: string; class?: string }) => s.id === check.badge.claims.scheme && s.class === 'person')) {
    const proofs = personProofsOf([check.badge.claims], registry, 'pt-BR');
    const named = schemeName(check.badge.claims.scheme, registry, 'pt-BR') !== undefined;
    expect(`person proof ${vector.name}`, proofs.length, named ? 1 : 0);
  }
}
for (const vector of issuers.verifierWithoutSchemes) {
  const check = verifyBadge(JSON.stringify(vector.envelope), vector.sender, vector.senderMasterKey ?? undefined, { keys: badges.publicKeys }, now);
  expect(`flat ${vector.name}`, check.code, vector.expect);
}

const bundled = newestList(ListType.Issuers, undefined);
for (const vector of issuers.releaseRefuses) {
  const check = verifyBadge(JSON.stringify(vector.envelope), vector.sender, vector.senderMasterKey ?? undefined, releaseTrust(bundled), now);
  expect(`release refuses ${vector.name}`, check.code, vector.expect);
}

// badges.json: the flat key path of the build's fallback.
for (const vector of badges.vectors) {
  const check = verifyBadge(JSON.stringify(vector.envelope), vector.sender, vector.senderMasterKey ?? undefined, { keys: badges.publicKeys }, BigInt(badges.now));
  expect(`badge ${vector.name}`, check.code, vector.expect);
}

// The bundled lists of this build verify against its signer set.
for (const type of [ListType.Operators, ListType.Issuers]) {
  expect(`bundled list ${type}`, newestList(type, undefined) !== undefined, true);
}

console.log(`BIPE_WEB_SEAL_VECTORS ${checked} checks`);

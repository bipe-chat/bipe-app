// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// bipe-shared test-vectors/badges.json through the packaged WASM (bipe-chat/bipe#1172).
// Usage: node build/run.mjs <badges.json> <module.wasm>
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import {
  boundBadgeResultCode,
  publicStatementResultCode,
  uniffiInitAsync,
  verifyBoundSenderBadge,
  verifyPublicIssuerStatement,
} from './generated/index';

const file = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const now = BigInt(file.now);
const keys = Object.entries<string>(file.publicKeys).map(([kid, key]) => {
  const bytes = Buffer.from(key, 'base64url');
  return { kid, publicKey: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) };
});
const bench = file.vectors.find((vector: any) => vector.name === 'cnpj: valid');
const benchEnvelope = JSON.stringify(bench.envelope);

// The first call, with the module load.
const coldStart = performance.now();
await uniffiInitAsync(pathToFileURL(process.argv[3]));
const first = verifyBoundSenderBadge(benchEnvelope, bench.sender, bench.senderMasterKey, keys, now);
const coldMicros = (performance.now() - coldStart) * 1000;
if (boundBadgeResultCode(first) !== 'valid') throw new Error('bench vector');

for (const vector of file.vectors) {
  const envelope = JSON.stringify(vector.envelope);
  const bound = boundBadgeResultCode(verifyBoundSenderBadge(envelope, vector.sender, vector.senderMasterKey ?? undefined, keys, now));
  if (bound !== vector.expect) throw new Error(`bound ${vector.name}: ${bound}, expected ${vector.expect}`);
  const publicExpected = ['subject_mismatch', 'master_key_mismatch'].includes(vector.expect) ? 'valid' : vector.expect;
  const statement = publicStatementResultCode(verifyPublicIssuerStatement(envelope, 'bipe.net.br', keys, now));
  if (statement !== publicExpected) throw new Error(`public ${vector.name}: ${statement}, expected ${publicExpected}`);
}

const samples: number[] = [];
for (let i = 0; i < 1000; i++) {
  const start = performance.now();
  verifyBoundSenderBadge(benchEnvelope, bench.sender, bench.senderMasterKey, keys, now);
  samples.push((performance.now() - start) * 1000);
}
samples.sort((a, b) => a - b);
console.log(`BIPE_CORE_METRICS ${JSON.stringify({ vectors: file.vectors.length, cold_us: coldMicros, p95_us: samples[949] })}`);

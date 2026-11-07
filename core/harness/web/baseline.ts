// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The current website verifier on the same vector, for comparison (bipe-chat/bipe#1172).
// Usage: node build/baseline.mjs <badges.json>
import { readFileSync } from 'node:fs';
import { verifyCompanyBadge } from '@web/badge';

const file = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const bench = file.vectors.find((vector: any) => vector.name === 'cnpj: valid');

const coldStart = performance.now();
const first = await verifyCompanyBadge(bench.envelope, file.publicKeys, file.now);
const coldMicros = (performance.now() - coldStart) * 1000;
if (!first) throw new Error('bench vector');

const samples: number[] = [];
for (let i = 0; i < 1000; i++) {
  const start = performance.now();
  await verifyCompanyBadge(bench.envelope, file.publicKeys, file.now);
  samples.push((performance.now() - start) * 1000);
}
samples.sort((a, b) => a - b);
console.log(`BIPE_BASELINE_METRICS ${JSON.stringify({ cold_us: coldMicros, p95_us: samples[949] })}`);

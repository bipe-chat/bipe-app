// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The page of the browser run (bipe-chat/bipe#1277 item 6): bipe-core and matrix-sdk-ffi, both ubrn wasm2 modules,
// on one page. Runs bipe-shared test-vectors/badges.json through bipe-core after both modules load, and puts the
// result in window.bipeCoreResult for browsers.mjs.
import * as core from './generated/index';
import * as sdk from './sdk-generated/index';

type Result = { ok: true; metrics: Record<string, unknown> } | { ok: false; error: string };

async function run(): Promise<Record<string, unknown>> {
  const file = await (await fetch('badges.json')).json();
  const now = BigInt(file.now);
  const keys = Object.entries<string>(file.publicKeys).map(([kid, key]) => {
    const text = atob(key.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (key.length % 4)) % 4));
    return { kid, publicKey: Uint8Array.from(text, (c) => c.charCodeAt(0)).buffer };
  });
  const bench = file.vectors.find((vector: any) => vector.name === 'cnpj: valid');
  const benchEnvelope = JSON.stringify(bench.envelope);

  const sdkStart = performance.now();
  await sdk.uniffiInitAsync(new URL('sdk.wasm', location.href));
  const sdkMillis = performance.now() - sdkStart;
  const sha = sdk.sdkGitSha();
  if (typeof sha !== 'string' || sha.length === 0) throw new Error('matrix-sdk-ffi: no sdkGitSha');

  const coldStart = performance.now();
  await core.uniffiInitAsync(new URL('core.wasm', location.href));
  const first = core.verifyBoundSenderBadge(benchEnvelope, bench.sender, bench.senderMasterKey, keys, now);
  const coldMicros = (performance.now() - coldStart) * 1000;
  if (core.boundBadgeResultCode(first) !== 'valid') throw new Error('bench vector');

  for (const vector of file.vectors) {
    const envelope = JSON.stringify(vector.envelope);
    const bound = core.boundBadgeResultCode(core.verifyBoundSenderBadge(envelope, vector.sender, vector.senderMasterKey ?? undefined, keys, now));
    if (bound !== vector.expect) throw new Error(`bound ${vector.name}: ${bound}, expected ${vector.expect}`);
    const publicExpected = ['subject_mismatch', 'master_key_mismatch'].includes(vector.expect) ? 'valid' : vector.expect;
    const statement = core.publicStatementResultCode(core.verifyPublicIssuerStatement(envelope, 'bipe.net.br', keys, now));
    if (statement !== publicExpected) throw new Error(`public ${vector.name}: ${statement}, expected ${publicExpected}`);
  }

  // The SDK again after the core: both modules stay usable.
  if (sdk.sdkGitSha() !== sha) throw new Error('matrix-sdk-ffi changed after bipe-core');

  const samples: number[] = [];
  for (let i = 0; i < 1000; i++) {
    const start = performance.now();
    core.verifyBoundSenderBadge(benchEnvelope, bench.sender, bench.senderMasterKey, keys, now);
    samples.push((performance.now() - start) * 1000);
  }
  samples.sort((a, b) => a - b);
  return { vectors: file.vectors.length, sdk_sha: sha, sdk_load_ms: sdkMillis, cold_us: coldMicros, p95_us: samples[949] };
}

run().then(
  (metrics): void => void ((window as any).bipeCoreResult = { ok: true, metrics } satisfies Result),
  (error): void => void ((window as any).bipeCoreResult = { ok: false, error: String(error?.stack ?? error) } satisfies Result),
);

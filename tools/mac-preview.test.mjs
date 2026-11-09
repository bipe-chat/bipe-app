// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./mac-preview.sh', import.meta.url));
const source = 'a'.repeat(40);
const target = 'b'.repeat(40);

function publish({ relation = 'ahead', body = `Source: ${source}. Passed.`, exists = true, badZip = false, tagFails = false } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'bipe-preview-'));
  try {
    const bytes = Buffer.from('synthetic CI archive');
    writeFileSync(join(dir, 'Bipe-Mac.zip'), bytes);
    writeFileSync(join(dir, 'Bipe-Mac.zip.sha256'), `${badZip ? '0'.repeat(64) : createHash('sha256').update(bytes).digest('hex')}  Bipe-Mac.zip\n`);
    // Exercise the real publication shell, with only the network boundary replaced.
    writeFileSync(join(dir, 'gh'), `#!/bin/bash
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  'release view '*) [ "$EXISTS" = true ] || exit 1; printf '%s\\n' "$BODY" ;;
  *'/compare/'*) printf '%s\\n' "$RELATION" ;;
  'api --method PATCH '*) [ "$TAG_FAILS" = false ] || exit 1 ;;
esac
`, { mode: 0o755 });
    const result = spawnSync('bash', [script], { cwd: dir, encoding: 'utf8', env: {
      ...process.env, PATH: `${dir}:${process.env.PATH}`, GH_REPO: 'bipe-chat/bipe-app', GITHUB_SHA: target,
      CALLS: join(dir, 'calls'), EXISTS: String(exists), BODY: body, RELATION: relation, TAG_FAILS: String(tagFails),
    } });
    let calls = '';
    try { calls = readFileSync(join(dir, 'calls'), 'utf8'); } catch { /* checksum can fail first */ }
    return { ...result, calls };
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

test('main preview runs finish while superseded PR runs cancel', () => {
  const workflow = readFileSync(new URL('../.github/workflows/macos.yml', import.meta.url), 'utf8');
  assert.match(workflow, /cancel-in-progress: \$\{\{ github.event_name == 'pull_request' \}\}/);
  assert.match(workflow, /needs: build/);
  assert.match(workflow, /run: bash \.\.\/tools\/mac-preview.sh/);
});

test('a newer or identical tested preview advances the tag before replacing ZIP/checksum and notes', () => {
  for (const relation of ['ahead', 'identical']) {
    const result = publish({ relation });
    assert.equal(result.status, 0, result.stderr);
    const calls = result.calls.trim().split('\n');
    assert.match(calls[2], /PATCH .*tags\/mac-preview.*force=false/);
    assert.match(calls[3], /release upload .*Bipe-Mac.zip Bipe-Mac.zip.sha256/);
    assert.match(calls[4], /release edit/);
  }
});

test('an old rerun or divergent source never mutates the release or tag', () => {
  for (const relation of ['behind', 'diverged']) {
    const result = publish({ relation });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /Skipping superseded/);
    assert.doesNotMatch(result.calls, /PATCH|release (upload|edit|create)/);
  }
});

test('bad checksum, ambiguous source, comparison errors and a rejected tag update fail before asset replacement', () => {
  for (const options of [{ badZip: true }, { body: 'No source' }, { relation: 'unknown' }, { tagFails: true }]) {
    const result = publish(options);
    assert.notEqual(result.status, 0);
    assert.doesNotMatch(result.calls, /release (upload|edit|create)/);
  }
});

test('first publication creates the preview with both assets and the exact tested source', () => {
  const result = publish({ exists: false });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.calls, new RegExp(`release create .*Bipe-Mac.zip Bipe-Mac.zip.sha256 --target ${target}`));
  assert.doesNotMatch(result.calls, /PATCH|release (upload|edit)/);
});

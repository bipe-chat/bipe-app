// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Provenance: the PR body has a line that starts with "Provenance:" and names
// at least one source.
// Usage: PR_BODY="..." node tools/provenance.mjs

import { isMain } from './lib.mjs';

export function provenanceLine(body) {
  for (const line of (body ?? '').split(/\r?\n/)) {
    const m = line.match(/^Provenance:\s*(.*)$/);
    if (m && m[1].trim()) return m[1].trim();
  }
  return null;
}

if (isMain(import.meta.url)) {
  const found = provenanceLine(process.env.PR_BODY);
  if (!found) {
    console.log(
      '::error title=provenance::The PR body has no line that starts with "Provenance:". Name every source of the change: a bipe-shared spec id, a Matrix spec section, a matrix-sdk-ffi API, an ADR or a Bipe file (AGENTS.md, "Pull requests").',
    );
    process.exit(1);
  }
  console.log(`provenance: OK (${found})`);
}

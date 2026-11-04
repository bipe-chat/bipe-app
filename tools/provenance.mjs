// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Provenance: the PR body has a line that starts with "Provenance:" and names
// at least one source, and a line that names its tracker issue: "Fixes
// bipe-chat/bipe#N" (or Closes/Resolves) when the PR finishes the issue,
// "Part of bipe-chat/bipe#N" when it does not, or "No issue: <reason>".
// A closing keyword closes the issue on merge; without one, 18 issues stayed
// open after their work merged (28 Oct 2026).
// Generated "Regenerate from bipe-shared" PRs need no issue line.
// Usage: PR_TITLE="..." PR_BODY="..." node tools/provenance.mjs

import { isMain } from './lib.mjs';

export function provenanceLine(body) {
  for (const line of (body ?? '').split(/\r?\n/)) {
    const m = line.match(/^Provenance:\s*(.*)$/);
    if (m && m[1].trim()) return m[1].trim();
  }
  return null;
}

const ISSUE = /\b(?:(fix(?:es|ed)?|close[sd]?|resolve[sd]?)|(part of))\s+bipe-chat\/bipe#(\d+)\b/i;

// Returns { kind: 'fixes' | 'part' | 'none' | 'generated', issue?, reason? } or null.
export function issueLine(body, title = '') {
  if (/^Regenerate from bipe-shared#\d+/.test(title ?? '')) return { kind: 'generated' };
  for (const line of (body ?? '').split(/\r?\n/)) {
    const m = line.match(ISSUE);
    if (m) return { kind: m[1] ? 'fixes' : 'part', issue: Number(m[3]) };
  }
  for (const line of (body ?? '').split(/\r?\n/)) {
    const m = line.match(/^No issue:\s*(.*)$/);
    if (m && m[1].trim()) return { kind: 'none', reason: m[1].trim() };
  }
  return null;
}

if (isMain(import.meta.url)) {
  const found = provenanceLine(process.env.PR_BODY);
  const issue = issueLine(process.env.PR_BODY, process.env.PR_TITLE);
  if (!found) {
    console.log(
      '::error title=provenance::The PR body has no line that starts with "Provenance:". Name every source of the change: a bipe-shared spec id, a Matrix spec section, a matrix-sdk-ffi API, an ADR or a Bipe file (AGENTS.md, "Pull requests").',
    );
  }
  if (!issue) {
    console.log(
      '::error title=provenance::The PR body names no tracker issue. Add "Fixes bipe-chat/bipe#N" when this PR finishes the issue (it closes on merge), "Part of bipe-chat/bipe#N" when work is left, or "No issue: <reason>" (AGENTS.md, "Pull requests").',
    );
  }
  if (!found || !issue) process.exit(1);
  const what = issue.kind === 'generated' ? 'generated' : issue.kind === 'none' ? `no issue (${issue.reason})` : `${issue.kind} bipe#${issue.issue}`;
  console.log(`provenance: OK (${found}; ${what})`);
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Puts the pinned bipe-core web artifact into web/src/bipe-core (bipe-chat/bipe#1458, ADR 0267): the release named in
// core/release/release.json, checked against its SHA-256 there. The web client imports the core through
// web/src/core.ts. The folder is generated; git ignores it. Needs the GitHub CLI (`gh`), signed in or with GH_TOKEN.
// Usage: node tools/web-core.mjs
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const release = JSON.parse(readFileSync(join(root, "core/release/release.json"), "utf8"));
const name = `bipe-core-web-${release.version}.tgz`;
const expected = release.assets?.[name];
if (!/^[0-9a-f]{64}$/.test(expected ?? "")) throw new Error(`web-core: core/release/release.json has no SHA-256 for ${name}`);

const work = mkdtempSync(join(tmpdir(), "bipe-core-web-"));
try {
  execFileSync("gh", ["release", "download", release.tag, "--repo", "bipe-chat/bipe-app", "--pattern", name, "--dir", work], { stdio: "inherit" });
  const file = join(work, name);
  const actual = createHash("sha256").update(readFileSync(file)).digest("hex");
  if (actual !== expected) throw new Error(`web-core: ${name} has SHA-256 ${actual}, release.json pins ${expected}`);
  const out = join(root, "web/src/bipe-core");
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  execFileSync("tar", ["-xzf", file, "-C", out], { stdio: "inherit" });
  console.log(`web-core: ${name} in web/src/bipe-core`);
} finally {
  rmSync(work, { recursive: true, force: true });
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Version of a build (ADR 0088, ADR 0163, bipe-chat/bipe#584), the same on every machine and in CI:
// - version name: `version` of version.json at the repo root. Bipe Next is a new app with its own line (1.0.0),
//   not the fork's bipe-shared product/version.json;
// - build number: its `build` plus the number of commits in HEAD, so one commit has one number;
// - commit: the short SHA of HEAD, with "-dirty" when the tree has changes.
// Builds before this tool took `build` plus the testflight run number. Tags builds/next/<number> mark the
// commit of each of those builds; a new number must be above the highest tag.
// Usage: node tools/version.mjs              prints {"version", "build", "commit"}
//        node tools/version.mjs --github     writes version, number and commit to $GITHUB_OUTPUT
//        node tools/version.mjs --xcodebuild prints the xcodebuild build settings
//        node tools/version.mjs --gradle     prints the Gradle properties

import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMain } from './lib.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const TAG_PREFIX = 'builds/next/';

const git = (root, ...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();

export function parseVersionFile(text) {
  const file = JSON.parse(text);
  if (!/^\d+\.\d+\.\d+$/.test(file.version ?? '')) throw new Error(`version.json: bad version ${file.version}`);
  if (!Number.isInteger(file.build) || file.build < 1) throw new Error(`version.json: bad build ${file.build}`);
  return { version: file.version, base: file.build };
}

function loadVersionFile(root) {
  return parseVersionFile(readFileSync(join(root, 'version.json'), 'utf8'));
}

/** The highest build number among the builds/next/<number> tags, or 0. */
export function highestTag(tags) {
  return tags
    .filter((tag) => tag.startsWith(TAG_PREFIX))
    .map((tag) => Number(tag.slice(TAG_PREFIX.length)))
    .filter(Number.isInteger)
    .reduce((max, n) => Math.max(max, n), 0);
}

export function buildNumber({ base, commits, tags }) {
  const number = base + commits;
  const floor = highestTag(tags);
  if (number <= floor) {
    throw new Error(`build number ${number} is not above the tag ${TAG_PREFIX}${floor}`);
  }
  return number;
}

export async function version(root = ROOT) {
  if (git(root, 'rev-parse', '--is-shallow-repository') === 'true') {
    throw new Error('shallow clone: the build number counts commits (actions/checkout needs fetch-depth: 0)');
  }
  const { version: name, base } = loadVersionFile(root);
  const commits = Number(git(root, 'rev-list', '--count', 'HEAD'));
  const tags = git(root, 'tag', '--list', `${TAG_PREFIX}*`).split('\n').filter(Boolean);
  const dirty = git(root, 'status', '--porcelain', '--untracked-files=no') !== '';
  const commit = git(root, 'rev-parse', '--short=7', 'HEAD') + (dirty ? '-dirty' : '');
  return { version: name, build: buildNumber({ base, commits, tags }), commit };
}

if (isMain(import.meta.url)) {
  const v = await version();
  const mode = process.argv[2];
  if (mode === '--github') {
    appendFileSync(process.env.GITHUB_OUTPUT, `version=${v.version}\nnumber=${v.build}\ncommit=${v.commit}\n`);
    console.log(`version ${v.version}, build number: ${v.build}, commit ${v.commit}`);
  } else if (mode === '--xcodebuild') {
    console.log(`MARKETING_VERSION=${v.version} CURRENT_PROJECT_VERSION=${v.build} BIPE_COMMIT=${v.commit}`);
  } else if (mode === '--gradle') {
    console.log(`-PbipeVersionName=${v.version} -PbipeBuildNumber=${v.build} -PbipeCommit=${v.commit}`);
  } else {
    console.log(JSON.stringify(v));
  }
}

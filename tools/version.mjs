// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Version of a build (ADR 0088, ADR 0163, ADR 0227, bipe-chat/bipe#584), the same on every machine and in CI:
// - version name: an odometer that rolls once per public store release (ADR 0227). A release is a tag
//   releases/<version> on the commit of the build that went public. HEAD on such a tag has that version; any other
//   commit has the next one after the highest release in its history (1.0.9 -> 1.1.0, 1.9.9 -> 2.0.0). Before the
//   first release it is `version` of version.json, the first version of the line. Nobody edits it to bump;
// - build number: `build` of version.json plus the number of commits in HEAD, so one commit has one number;
// - commit: the short SHA of HEAD, with "-dirty" when the tree has changes.
// Builds before this tool took `build` plus the testflight run number. Tags builds/next/<number> mark the
// commit of each of those builds; a new number must be above the highest tag.
// Usage: node tools/version.mjs              prints {"version", "build", "commit"}
//        node tools/version.mjs --release <build> [--ref <rev>]  tags releases/<version> on the commit of <build>
//                                            on the first-parent line of <rev> (default origin/main); does not push
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
export const RELEASE_PREFIX = 'releases/';

const git = (root, ...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();

export function parseVersionFile(text) {
  const file = JSON.parse(text);
  if (!ODOMETER.test(file.version ?? '')) throw new Error(`version.json: bad version ${file.version} (major.d.d)`);
  if (!Number.isInteger(file.build) || file.build < 1) throw new Error(`version.json: bad build ${file.build}`);
  return { version: file.version, base: file.build };
}

function loadVersionFile(root) {
  return parseVersionFile(readFileSync(join(root, 'version.json'), 'utf8'));
}

// --- the odometer (ADR 0227) ------------------------------------------------------------------------------------

// Major has any number of digits; minor and patch are one digit each, so 1.0.9 rolls to 1.1.0.
const ODOMETER = /^(\d+)\.(\d)\.(\d)$/;

export function parseVersion(text) {
  const m = ODOMETER.exec(text ?? '');
  if (!m) throw new Error(`bad version ${text} (major.d.d)`);
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

/** The version after `text`: 1.0.1 -> 1.0.2, 1.0.9 -> 1.1.0, 1.9.9 -> 2.0.0. */
export function nextVersion(text) {
  let [major, minor, patch] = parseVersion(text);
  patch += 1;
  if (patch > 9) [patch, minor] = [0, minor + 1];
  if (minor > 9) [minor, major] = [0, major + 1];
  return `${major}.${minor}.${patch}`;
}

export function compareVersions(a, b) {
  const [x, y] = [parseVersion(a), parseVersion(b)];
  return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
}

/** The release versions among tag names. */
export function releaseVersions(tags) {
  return tags.filter((t) => t.startsWith(RELEASE_PREFIX)).map((t) => t.slice(RELEASE_PREFIX.length)).filter((v) => ODOMETER.test(v));
}

/**
 * The version name of a commit: `atCommit` when the commit is a release, else the version after the highest of
 * `released` (the releases in its history), else `base` (version.json) before the first release.
 */
export function versionName({ base, released, atCommit }) {
  if (atCommit) return atCommit;
  if (released.length === 0) return base;
  return nextVersion(released.reduce((max, v) => (compareVersions(v, max) > 0 ? v : max)));
}

/**
 * The first-parent line of `ref`, oldest first, as { commit, date, count }: `count` is the number of commits in
 * that commit's history (all parents), the same count that makes its build number. A merge adds the commits it brings.
 */
export function readHistory(root, ref = 'HEAD') {
  const rows = git(root, 'log', '--first-parent', '--reverse', '--format=%H %cs %P', ref).split('\n').filter(Boolean);
  let count = 0;
  let previous = null;
  return rows.map((row) => {
    const [commit, date, ...parents] = row.split(' ');
    count += parents.length > 1 && previous ? Number(git(root, 'rev-list', '--count', `${previous}..${commit}`)) : 1;
    previous = commit;
    return { commit, date, count };
  });
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
  const { version: first, base } = loadVersionFile(root);
  const commits = Number(git(root, 'rev-list', '--count', 'HEAD'));
  const tags = git(root, 'tag', '--list', `${TAG_PREFIX}*`).split('\n').filter(Boolean);
  const released = releaseVersions(git(root, 'tag', '--list', `${RELEASE_PREFIX}*`, '--merged', 'HEAD').split('\n'));
  const atHead = releaseVersions(git(root, 'tag', '--list', `${RELEASE_PREFIX}*`, '--points-at', 'HEAD').split('\n'));
  const name = versionName({ base: first, released, atCommit: atHead[0] });
  const dirty = git(root, 'status', '--porcelain', '--untracked-files=no') !== '';
  const commit = git(root, 'rev-parse', '--short=7', 'HEAD') + (dirty ? '-dirty' : '');
  return { version: name, build: buildNumber({ base, commits, tags }), commit };
}

/**
 * The release of build `build`: the commit on the first-parent line of `history` whose build number it is, and the
 * version that commit has. Fails when no commit has that number or the build is not above the last release.
 */
export function releaseTarget({ build, base, history, released }) {
  const at = history.find((h) => base + h.count === build);
  if (!at) throw new Error(`no commit on the line has build number ${build}`);
  const name = versionName({ base: released.base, released: released.before(at.commit), atCommit: released.at(at.commit) });
  if (released.at(at.commit)) throw new Error(`build ${build} is already release ${name}`);
  const highest = released.all.reduce((max, v) => (max === null || compareVersions(v, max) > 0 ? v : max), null);
  if (highest && compareVersions(name, highest) <= 0) throw new Error(`build ${build} has ${name}, not above release ${highest}`);
  return { commit: at.commit, version: name };
}

function release(root, build, ref) {
  const { version: first, base } = loadVersionFile(root);
  const history = readHistory(root, ref);
  const tagsOf = (args) => releaseVersions(git(root, 'tag', '--list', `${RELEASE_PREFIX}*`, ...args).split('\n'));
  const released = {
    base: first,
    all: tagsOf([]),
    before: (commit) => tagsOf(['--merged', commit]),
    at: (commit) => tagsOf(['--points-at', commit])[0],
  };
  const target = releaseTarget({ build, base, history, released });
  const tag = `${RELEASE_PREFIX}${target.version}`;
  git(root, 'tag', '-a', tag, target.commit, '-m', `Bipe ${target.version} (${build})`);
  return { ...target, tag, build };
}

if (isMain(import.meta.url)) {
  const mode = process.argv[2];
  if (mode === '--release') {
    const build = Number(process.argv[3]);
    if (!Number.isInteger(build)) throw new Error('--release takes a build number');
    const ref = process.argv[4] === '--ref' ? process.argv[5] : 'origin/main';
    const r = release(ROOT, build, ref);
    console.log(`${r.tag} on ${r.commit.slice(0, 8)} (build ${r.build}); push it: git push origin ${r.tag}`);
    process.exit(0);
  }
  const v = await version();
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

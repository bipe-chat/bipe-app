// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The changelog: one file per change in changelog/updates/, grouped by build (changelog/README.md).
// A build is a commit that CI built: a tag builds/next/<number> from before tools/version.mjs, or any main commit
// since version.json (its build plus the commit count, tools/version.mjs). A change belongs to the first build at or
// after its commit. An update without `build` takes the build of the commit that added its file. A build has the
// version tools/version.mjs gives its commit; the builds of one version ship in its release, a tag
// releases/<version> (ADR 0227). The builds after the last release wait for the next one.
// Usage: node tools/changelog.mjs                    prints the changelog, newest build first
//        node tools/changelog.mjs --since <build>    prints only the builds after <build>
//        node tools/changelog.mjs --json [--ref <rev>] prints the builds as JSON for bipe-web, from the commit <rev>
//                                                    (default HEAD), newest first
//        node tools/changelog.mjs --check            checks every update file
//        node tools/changelog.mjs --require <base>   fails when the commits since <base> change what a tester sees and
//                                                    add no update file (CI on each pull request; PR_TITLE, PR_BODY)
//        node tools/changelog.mjs --backfill <range> drafts update files from the commits in a git range
//                                                    (for example builds/next/140..origin/main); review them
// Node built-ins only.

import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { REPO_ROOT, isMain } from './lib.mjs';
import { RELEASE_PREFIX, TAG_PREFIX, readHistory, releaseVersions, versionName } from './version.mjs';

export const KINDS = ['new', 'improved', 'fixed', 'removed'];
// web/ is a spike: its changes stay out of the changelog.
export const PLATFORMS = ['ios', 'android'];
const UPDATES = 'changelog/updates';

const git = (root, ...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', maxBuffer: 64 << 20 }).trim();
const lines = (text) => text.split('\n').filter(Boolean);

// --- update files -----------------------------------------------------------------------------------------------

/** Parses an update file: a `---` block of `key: value` lines, then the body. */
export function parseUpdate(text) {
  const match = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(text.replace(/\r\n/g, '\n'));
  if (!match) throw new Error('no front matter');
  const fields = {};
  for (const line of lines(match[1])) {
    const at = line.indexOf(':');
    if (at < 1) throw new Error(`bad line "${line}"`);
    const key = line.slice(0, at).trim();
    const raw = line.slice(at + 1).trim();
    fields[key] = raw.startsWith('[')
      ? raw.slice(1, -1).split(',').map((s) => s.trim()).filter(Boolean)
      : raw;
  }
  return { ...fields, build: fields.build === undefined ? undefined : Number(fields.build), body: match[2].trim() };
}

export function updateProblems(file, update) {
  const problems = [];
  const id = file.replace(/\.md$/, '');
  if (update.id !== id) problems.push(`${file}: id must be "${id}"`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(update.date ?? '')) problems.push(`${file}: date must be YYYY-MM-DD`);
  if (update.build !== undefined && !(Number.isInteger(update.build) && update.build > 0)) {
    problems.push(`${file}: build must be a whole number`);
  }
  if (!KINDS.includes(update.kind)) problems.push(`${file}: kind must be one of ${KINDS.join(', ')}`);
  const platforms = update.platforms ?? [];
  if (!Array.isArray(platforms) || platforms.length === 0 || platforms.some((p) => !PLATFORMS.includes(p))) {
    problems.push(`${file}: platforms must list one or more of ${PLATFORMS.join(', ')}`);
  }
  if (!Array.isArray(update.refs ?? [])) problems.push(`${file}: refs must be a list`);
  if (!/^\*\*[^*]+\*\*/.test(update.body)) problems.push(`${file}: the body starts with a bold headline`);
  if (update.body.startsWith('- ')) problems.push(`${file}: the body has no "- " list marker`);
  return problems;
}

export function readUpdates(root) {
  const dir = join(root, UPDATES);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => f.endsWith('.md'))
    .sort()
    .map((file) => {
      try {
        return { file, ...parseUpdate(readFileSync(join(dir, file), 'utf8')) };
      } catch (error) {
        return { file, error: `${file}: ${error.message}` };
      }
    });
}

/** The update files at a commit, read through git, so a checkout on another branch still gives main's changelog. */
function readUpdatesAt(root, ref) {
  return lines(git(root, 'ls-tree', '--name-only', ref, `${UPDATES}/`))
    .filter((f) => f.endsWith('.md'))
    .map((path) => ({ file: path.slice(UPDATES.length + 1), ...parseUpdate(git(root, 'show', `${ref}:${path}`)) }));
}

export function checkUpdates(root) {
  return readUpdates(root).flatMap((u) => (u.error ? [u.error] : updateProblems(u.file, u)));
}

// --- builds -----------------------------------------------------------------------------------------------------

/**
 * The builds in HEAD's history, oldest first: { build, commit, date, version, released, releaseDate }.
 * `history` is HEAD's first-parent commits oldest first as { commit, date, count };
 * `tags` maps commit to tag number; `versionAt(commit)` returns version.json at that commit or null;
 * `releases` maps commit to the version released there (tags releases/<version>); `first` is the version before the
 * first release (version.json). `released` tells whether the build's version has gone public, on `releaseDate`.
 */
export function builds({ history, tags, versionAt, releases = new Map(), first }) {
  const out = [];
  const before = [];
  const releaseDates = new Map();
  for (const { commit, date } of history) if (releases.has(commit)) releaseDates.set(releases.get(commit), date);
  for (const { commit, date, count } of history) {
    const file = versionAt(commit);
    const version = versionName({ base: first, released: before, atCommit: releases.get(commit) });
    if (releases.has(commit)) before.push(releases.get(commit));
    const build = file ? file.build + count : tags.get(commit);
    if (build === undefined) continue;
    out.push({ build, commit, date, version, released: releaseDates.has(version), releaseDate: releaseDates.get(version) ?? null });
  }
  return out;
}

/** The first build at or after the commit at `index` in history, or null when no build has it yet. */
export function buildFor(index, history, byCommit) {
  for (let i = index; i < history.length; i++) {
    const b = byCommit.get(history[i].commit);
    if (b) return b;
  }
  return null;
}


function readTags(root) {
  const tags = new Map();
  for (const row of lines(git(root, 'tag', '--list', `${TAG_PREFIX}*`, '--format=%(refname:short) %(*objectname)%(objectname)'))) {
    const [tag, sha] = row.split(' ');
    // An annotated tag prints the commit and then the tag object; the commit is the first 40 characters.
    tags.set(sha.slice(0, 40), Number(tag.slice(TAG_PREFIX.length)));
  }
  return tags;
}

/** version.json at each commit, read once per change of the file. */
function versionReader(root, history, ref) {
  const changes = lines(git(root, 'log', '--first-parent', '--format=%H', ref, '--', 'version.json'));
  const order = new Map(history.map((h, i) => [h.commit, i]));
  const points = changes
    .map((c) => ({ at: order.get(c), file: JSON.parse(git(root, 'show', `${c}:version.json`)) }))
    .filter((p) => p.at !== undefined)
    .sort((a, b) => a.at - b.at);
  return (commit) => {
    const at = order.get(commit);
    let file = null;
    for (const p of points) if (p.at <= at) file = p.file;
    return file;
  };
}

function readReleases(root) {
  const releases = new Map();
  for (const row of lines(git(root, 'tag', '--list', `${RELEASE_PREFIX}*`, '--format=%(refname:short) %(*objectname)%(objectname)'))) {
    const [tag, sha] = row.split(' ');
    const [version] = releaseVersions([tag]);
    if (version) releases.set(sha.slice(0, 40), version);
  }
  return releases;
}

function loadBuilds(root, ref = 'HEAD') {
  const history = readHistory(root, ref);
  const first = JSON.parse(git(root, 'show', `${ref}:version.json`)).version;
  const list = builds({ history, tags: readTags(root), versionAt: versionReader(root, history, ref), releases: readReleases(root), first });
  return { history, list, byCommit: new Map(list.map((b) => [b.commit, b])) };
}

// --- rendering --------------------------------------------------------------------------------------------------

const PLATFORM_NAMES = { ios: 'iOS', android: 'Android' };

export function updateBullet(update) {
  const where = update.platforms.map((p) => PLATFORM_NAMES[p]).join(', ');
  const body = update.body.replace(/\n+/g, ' ');
  return `- ${body} _(${where}; ${update.kind})_`;
}

/**
 * Markdown, newest version and build first. `updates` carry `build`; those without one wait under "Next build".
 * A version heading says when it went public, or that it has not yet.
 */
export function renderChangelog(updates, buildList, { since = 0 } = {}) {
  const byBuild = new Map();
  for (const u of updates) {
    const key = u.build ?? 'next';
    if (key !== 'next' && key <= since) continue;
    if (!byBuild.has(key)) byBuild.set(key, []);
    byBuild.get(key).push(u);
  }
  const info = new Map(buildList.map((b) => [b.build, b]));
  const keys = [...byBuild.keys()].sort((a, b) => (a === 'next' ? -1 : b === 'next' ? 1 : b - a));
  const out = ['# Bipe app changelog', ''];
  let version;
  for (const key of keys) {
    const b = info.get(key);
    if (b && b.version !== version) {
      version = b.version;
      out.push(`## ${version} — ${b.released ? `released ${b.releaseDate}` : 'not released yet'}`, '');
    }
    if (key === 'next') out.push('### Next build');
    else out.push(`### Build ${key}${b ? ` — ${b.date}` : ''}`);
    out.push('');
    const entries = byBuild.get(key).sort((x, y) => x.date.localeCompare(y.date) || x.id.localeCompare(y.id));
    for (const u of entries) out.push(updateBullet(u));
    out.push('');
  }
  return out.join('\n');
}

/** The builds with their updates, newest first, for bipe-web. The headline is the bold text; detail is the rest. */
export function changelogJson(updates, buildList) {
  const plain = (text) => text.replace(/\*\*|`/g, '').replace(/\s+/g, ' ').trim();
  const info = new Map(buildList.map((b) => [b.build, b]));
  const byBuild = new Map();
  for (const u of updates) {
    if (u.build === undefined) continue;
    if (!byBuild.has(u.build)) byBuild.set(u.build, []);
    byBuild.get(u.build).push(u);
  }
  return [...byBuild.keys()]
    .sort((a, b) => b - a)
    .map((build) => ({
      build,
      version: info.get(build)?.version ?? null,
      released: info.get(build)?.released ?? false,
      releaseDate: info.get(build)?.releaseDate ?? null,
      date: info.get(build)?.date ?? byBuild.get(build)[0].date,
      updates: byBuild
        .get(build)
        .sort((x, y) => x.id.localeCompare(y.id))
        .map((u) => {
          const headline = /^\*\*([^*]+)\*\*/.exec(u.body)[1];
          return {
            id: u.id,
            kind: u.kind,
            platforms: u.platforms,
            refs: u.refs ?? [],
            headline: plain(headline),
            detail: plain(u.body.slice(headline.length + 4)),
          };
        }),
    }));
}

/** Gives each update without `build` the build of the commit that added its file. */
function resolveBuilds(root, updates, { history, byCommit }, ref = 'HEAD') {
  const order = new Map(history.map((h, i) => [h.commit, i]));
  return updates.map((u) => {
    if (u.build !== undefined) return u;
    const added = lines(git(root, 'log', '--first-parent', '--diff-filter=A', '--format=%H', ref, '--', `${UPDATES}/${u.file}`)).pop();
    const at = added === undefined ? undefined : order.get(added);
    return { ...u, build: at === undefined ? undefined : buildFor(at, history, byCommit)?.build };
  });
}

// --- backfill ---------------------------------------------------------------------------------------------------

// Commits a tester does not see: generated code, builds, store listings, code moves and compile fixes.
const SKIP_SUBJECT =
  /^(Regenerate|Generated|CI|AGENTS|Store captures|Store ID|Play listing|Tester builds|Licen[cs]e|similarity|deny-list|provenance|Revert|Move |Version from|Bipe Next's own version|Android API models|Android side-load|Android: package|Isolate |Keep the fork|Carry-over)\b|compile error|\backs before\b/i;

/** The platforms a change touches, from its paths; empty when it touches no app code. */
export function platformsOf(paths) {
  const app = paths.filter((p) => !/(^|\/)(Generated|generated|test|tests|Tests|androidTest|UITests)\//.test(p));
  const found = [];
  if (app.some((p) => p.startsWith('ios/') && /\.(swift|xcstrings|strings|json|png|svg)$/.test(p))) found.push('ios');
  if (app.some((p) => p.startsWith('android/') && /\.(kt|xml|json|png|svg)$/.test(p) && !p.endsWith('.gradle.kts'))) found.push('android');
  return found;
}

export function kindOf(subject) {
  if (/\b(fix|fixed|no longer|again|works|crash|stays)\b/i.test(subject)) return 'fixed';
  if (/^(remove|drop)\b|: remove\b/i.test(subject)) return 'removed';
  if (/\b(new|add|adds|added)\b/i.test(subject)) return 'new';
  return 'improved';
}

/** A draft update for one commit, or null when the commit changes nothing a tester sees. */
export function draftUpdate({ subject, date, short, paths, build }) {
  if (SKIP_SUBJECT.test(subject)) return null;
  const platforms = platformsOf(paths);
  if (platforms.length === 0) return null;
  const pr = /\(#(\d+)\)\s*$/.exec(subject)?.[1];
  // Issue and ADR numbers move from the headline to refs: (bipe-chat/bipe#9), (bipe#9, bipe#10), (ADR 0222).
  const notes = [...subject.matchAll(/\s*\(((?:bipe-chat\/)?bipe#\d+|ADR \d+)[^)]*\)/g)];
  const issues = notes.flatMap((m) => [...m[0].matchAll(/(ADR )?(\d+)/g)].map((n) => (n[1] ? `adr-${n[2]}` : `bipe#${n[2]}`)));
  let headline = subject.replace(/\s*\(#\d+\)\s*$/, '');
  for (const m of notes) headline = headline.replace(m[0], '');
  headline = headline.replace(/\.$/, '');
  const id = `${date}-${pr ? `pr${pr}` : short}`;
  const refs = [...(pr ? [`bipe-app#${pr}`] : []), ...issues];
  const front = [`id: ${id}`, `date: ${date}`];
  if (build) front.push(`build: ${build}`);
  front.push(`kind: ${kindOf(subject)}`, `platforms: [${platforms.join(', ')}]`, `refs: [${refs.join(', ')}]`, `commit: ${short}`);
  return { id, text: `---\n${front.join('\n')}\n---\n\n**${headline}.**\n` };
}

/** Why a pull request needs an update file, or null. A PR body line `Changelog: none` opts out. */
export function missingUpdate({ title = '', body = '', paths, added }) {
  if (added.some((p) => p.startsWith(`${UPDATES}/`) && p.endsWith('.md'))) return null;
  if (SKIP_SUBJECT.test(title) || /^changelog:\s*none\b/im.test(body)) return null;
  const platforms = platformsOf(paths);
  if (platforms.length === 0) return null;
  return `this pull request changes ${platforms.join(' and ')} app code and adds no file in ${UPDATES}/`;
}

function backfill(root, range) {
  const { history, byCommit } = loadBuilds(root);
  const order = new Map(history.map((h, i) => [h.commit, i]));
  const known = new Set(readUpdates(root).flatMap((u) => [u.commit, ...(u.refs ?? [])]).filter(Boolean));
  const commits = lines(git(root, 'log', '--first-parent', '--reverse', '--format=%H', range));
  let written = 0;
  for (const commit of commits) {
    const [short, date, ...rest] = git(root, 'show', '-s', '--format=%h%x00%cs%x00%s', commit).split('\0');
    const subject = rest.join('');
    const paths = lines(git(root, 'show', '--format=', '--name-only', commit));
    const at = order.get(commit);
    const build = at === undefined ? undefined : buildFor(at, history, byCommit)?.build;
    const draft = draftUpdate({ subject, date, short, paths, build });
    if (!draft) continue;
    const pr = /\(#(\d+)\)\s*$/.exec(subject)?.[1];
    if (known.has(short) || (pr && known.has(`bipe-app#${pr}`))) continue;
    const path = join(root, UPDATES, `${draft.id}.md`);
    if (existsSync(path)) continue;
    writeFileSync(path, draft.text);
    written++;
  }
  console.log(`changelog: wrote ${written} draft update(s) to ${UPDATES}/ — review the headline, kind and platforms of each`);
}

if (isMain(import.meta.url)) {
  const [mode, arg] = process.argv.slice(2);
  if (mode === '--check') {
    const problems = checkUpdates(REPO_ROOT);
    for (const p of problems) console.error(p);
    if (problems.length) {
      console.error('\nOne change is one file in changelog/updates/. See changelog/README.md.');
      process.exitCode = 1;
    } else {
      console.log(`changelog: ${readUpdates(REPO_ROOT).length} update(s) ok`);
    }
  } else if (mode === '--backfill') {
    if (!arg) throw new Error('--backfill takes a git range, for example builds/next/140..origin/main');
    backfill(REPO_ROOT, arg);
  } else if (mode === '--require') {
    if (!arg) throw new Error('--require takes the base commit, for example origin/main');
    const range = `${arg}...HEAD`;
    const why = missingUpdate({
      title: process.env.PR_TITLE,
      body: process.env.PR_BODY,
      paths: lines(git(REPO_ROOT, 'diff', '--name-only', range)),
      added: lines(git(REPO_ROOT, 'diff', '--name-only', '--diff-filter=A', range)),
    });
    if (why) {
      console.error(`changelog: ${why}.`);
      console.error('Add one (changelog/README.md), or put the line "Changelog: none" in the PR description when a tester sees nothing.');
      process.exitCode = 1;
    } else {
      console.log('changelog: ok');
    }
  } else if (mode === '--json') {
    const ref = process.argv[3] === '--ref' ? process.argv[4] : 'HEAD';
    const loaded = loadBuilds(REPO_ROOT, ref);
    const updates = resolveBuilds(REPO_ROOT, readUpdatesAt(REPO_ROOT, ref), loaded, ref);
    process.stdout.write(`${JSON.stringify(changelogJson(updates, loaded.list), null, 2)}\n`);
  } else if (mode === undefined || mode === '--since') {
    const since = mode === '--since' ? Number(arg) : 0;
    if (!Number.isInteger(since)) throw new Error('--since takes a build number');
    const loaded = loadBuilds(REPO_ROOT);
    const updates = resolveBuilds(REPO_ROOT, readUpdates(REPO_ROOT).filter((u) => !u.error), loaded);
    process.stdout.write(renderChangelog(updates, loaded.list, { since }));
  } else {
    console.error('usage: changelog.mjs [--since <build>] | --json [--ref <rev>] | --check | --require <base> | --backfill <git range>');
    process.exitCode = 2;
  }
}

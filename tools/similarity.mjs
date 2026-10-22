// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Similarity scan: compares Bipe source files with an Element X release using
// JPlag (https://github.com/jplag/JPlag). Runs in GitHub CI only; agents do
// not clone Element repositories on their machines.
//
// Usage:
//   node tools/similarity.mjs scan --lang swift --ours ios --repo element-hq/element-x-ios --jar jplag.jar
//   node tools/similarity.mjs self-test --jar jplag.jar
//
// Each source file is one JPlag submission, so a result names a file pair.
// Bipe files are "new" submissions and Element files are "old" submissions:
// JPlag does not compare Element files with each other.
// A pair fails when its average similarity (JPlag's default metric) is above
// THRESHOLD and the two files share at least MIN_MATCHED_TOKENS tokens in
// matches. A small file of plain SwiftUI or Compose setup reaches 0.5 against
// many unrelated files with a few dozen shared tokens; a copy shares more.
// A pair above THRESHOLD under that minimum, or whose maximum similarity is
// above THRESHOLD, prints a warning: maximum similarity is high when a small
// file is mostly contained in a large one.
//
// Matched tokens: JPlag's average similarity is 2 * matched / (tokens of the
// first file + tokens of the second file), so matched = average * (sum) / 2.
// The token counts come from submissionFileIndex.json in the JPlag report.

import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { walk, isMain, REPO_ROOT } from './lib.mjs';

export const THRESHOLD = 0.5;
export const MIN_MATCHED_TOKENS = 100;

export const LANGS = {
  swift: { jplag: 'swift', ext: '.swift' },
  kotlin: { jplag: 'kotlin', ext: '.kt' },
};

// Maps each file to a flat submission name: o00001_Name.swift (ours) or
// t00001_Name.swift (theirs).
export function flatNames(files, side) {
  const map = new Map();
  files.forEach((file, i) => map.set(`${side}${String(i + 1).padStart(5, '0')}_${basename(file)}`, file));
  return map;
}

// Parses RFC 4180 CSV (JPlag --csv-export writes results.csv this way).
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') {
      row.push(cell);
      cell = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else cell += c;
  }
  if (cell || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rows.filter((r) => r.length > 1 || r[0] !== '');
}

function flatKey(submissionName) {
  const m = submissionName.match(/(?:^|[/\\])([ot]\d{5}_[^/\\]+)$/);
  return m ? m[1] : null;
}

// The shared tokens of a pair. Unknown when a token count is missing.
export function matchedTokens(avg, tokensA, tokensB) {
  if (!Number.isFinite(tokensA) || !Number.isFinite(tokensB)) return Infinity;
  return Math.round((avg * (tokensA + tokensB)) / 2);
}

// Token count per flat submission name, from JPlag's submissionFileIndex.json:
// { fileIndexes: { "<dir>_<flat>": { "<path>": { tokenCount } } } }.
export function tokenCounts(index) {
  const out = new Map();
  for (const [submission, files] of Object.entries(index.fileIndexes ?? {})) {
    const key = flatKey(submission.replace(/^[^_]*_(?=[ot]\d{5}_)/, ''));
    if (!key) continue;
    out.set(key, Object.values(files).reduce((sum, f) => sum + (f.tokenCount ?? 0), 0));
  }
  return out;
}

// rows: parsed results.csv (header first). ours/theirs: maps from flatNames.
// tokens: map from flat name to token count (a missing count counts as a
// copy, so the pair fails).
// Returns { failures, warnings, notCompared } with original file paths.
export function evaluate(rows, ours, theirs, threshold = THRESHOLD, tokens = new Map(), minTokens = MIN_MATCHED_TOKENS) {
  const [header, ...data] = rows;
  const col = (name) => header.indexOf(name);
  const [c1, c2, cAvg, cMax] = ['submissionName1', 'submissionName2', 'averageSimilarity', 'maxSimilarity'].map(col);
  if ([c1, c2, cAvg, cMax].some((c) => c < 0)) throw new Error(`unexpected JPlag CSV header: ${header.join(',')}`);
  const failures = [];
  const warnings = [];
  const compared = new Set();
  for (const r of data) {
    const a = flatKey(r[c1]);
    const b = flatKey(r[c2]);
    for (const k of [a, b]) if (ours.has(k)) compared.add(k);
    let mine;
    let other;
    if (ours.has(a) && theirs.has(b)) [mine, other] = [a, b];
    else if (ours.has(b) && theirs.has(a)) [mine, other] = [b, a];
    else continue;
    const avg = Number(r[cAvg]);
    const max = Number(r[cMax]);
    const matched = matchedTokens(avg, tokens.get(mine), tokens.get(other));
    const pair = { ours: ours.get(mine), theirs: theirs.get(other), avg, max, matched };
    if (avg > threshold && matched >= minTokens) failures.push(pair);
    else if (avg > threshold || max > threshold) warnings.push(pair);
  }
  const byAvg = (x, y) => y.avg - x.avg || y.max - x.max;
  const notCompared = [...ours.keys()].filter((k) => !compared.has(k)).map((k) => ours.get(k));
  return { failures: failures.sort(byAvg), warnings: warnings.sort(byAvg), notCompared };
}

function stage(map, srcRoot, dir) {
  mkdirSync(dir, { recursive: true });
  for (const [flat, file] of map) copyFileSync(join(srcRoot, file), join(dir, flat));
}

// Runs JPlag and returns the parsed results.csv and the token count per file.
export function runJplag({ jar, lang, ours, theirs, oursRoot, theirsRoot, work }) {
  const oursDir = join(work, 'ours');
  const theirsDir = join(work, 'theirs');
  stage(ours, oursRoot, oursDir);
  stage(theirs, theirsRoot, theirsDir);
  const result = join(work, 'report');
  const args = ['-Xmx6g', '-jar', jar, '-M', 'RUN', '-l', LANGS[lang].jplag, '--csv-export', '--cluster-skip', '--overwrite'];
  args.push('-new', oursDir, '-old', theirsDir, '-r', result);
  try {
    execFileSync('java', args, { stdio: 'inherit' });
  } catch (e) {
    // JPlag can exit non-zero after it logged errors for single files. The
    // CSV decides; it is missing when the run itself failed.
    console.log(`similarity: JPlag exited with status ${e.status}`);
  }
  const csv = join(result, 'results.csv');
  if (!existsSync(csv)) throw new Error(`JPlag wrote no ${csv}`);
  // The report archive holds the token count of each file.
  const index = join(work, 'index');
  execFileSync('unzip', ['-o', '-q', `${result}.jplag`, 'submissionFileIndex.json', '-d', index], { stdio: 'inherit' });
  const tokens = tokenCounts(JSON.parse(readFileSync(join(index, 'submissionFileIndex.json'), 'utf8')));
  return { rows: parseCsv(readFileSync(csv, 'utf8')), tokens };
}

function report(result, label, threshold) {
  for (const w of result.notCompared) {
    console.log(`::warning title=similarity::${w} was not compared (JPlag could not parse it, or it is under the minimum token count)`);
  }
  for (const p of result.warnings) {
    console.log(
      `::warning title=similarity::${p.ours} ~ ${label}:${p.theirs} average similarity ${p.avg.toFixed(2)}, max ${p.max.toFixed(2)}, ${p.matched} matched tokens. Review it.`,
    );
  }
  for (const p of result.failures) {
    console.log(
      `::error title=similarity::${p.ours} ~ ${label}:${p.theirs} average similarity ${p.avg.toFixed(2)} > ${threshold} with ${p.matched} matched tokens (max ${p.max.toFixed(2)})`,
    );
  }
}

async function latestTag(repo) {
  const headers = { Accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, { headers });
  if (!res.ok) throw new Error(`GitHub API ${res.status} for ${repo} latest release`);
  return (await res.json()).tag_name;
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) out[argv[i].slice(2)] = argv[++i];
    else out._.push(argv[i]);
  }
  return out;
}

async function scan(opts) {
  const lang = opts.lang;
  if (!LANGS[lang]) throw new Error(`--lang must be one of ${Object.keys(LANGS).join(', ')}`);
  const threshold = opts.threshold ? Number(opts.threshold) : THRESHOLD;
  // Generated folders hold bipe-shared output (Bipe code), so they are not compared.
  const oursFiles = walk(REPO_ROOT, opts.ours, { skipGenerated: true }).filter((f) => f.endsWith(LANGS[lang].ext));
  if (!oursFiles.length) {
    console.log(`::notice title=similarity::${opts.ours}/ holds no ${LANGS[lang].ext} files yet. Nothing to compare.`);
    return 0;
  }
  const tag = await latestTag(opts.repo);
  const work = mkdtempSync(join(process.env.RUNNER_TEMP ?? tmpdir(), 'similarity-'));
  const clone = join(work, 'upstream');
  console.log(`similarity: cloning ${opts.repo} at ${tag}`);
  execFileSync('git', ['clone', '--quiet', '--depth', '1', '--branch', tag, `https://github.com/${opts.repo}.git`, clone], {
    stdio: 'inherit',
  });
  const theirsFiles = walk(clone).filter((f) => f.endsWith(LANGS[lang].ext));
  const ours = flatNames(oursFiles, 'o');
  const theirs = flatNames(theirsFiles, 't');
  console.log(`similarity: ${oursFiles.length} Bipe file(s) against ${theirsFiles.length} file(s) of ${opts.repo}@${tag}`);
  const { rows, tokens } = runJplag({ jar: opts.jar, lang, ours, theirs, oursRoot: REPO_ROOT, theirsRoot: clone, work: join(work, 'jplag') });
  const result = evaluate(rows, ours, theirs, threshold, tokens);
  report(result, `${opts.repo}@${tag}`, threshold);
  console.log(`similarity: ${result.failures.length} pair(s) above ${threshold} with ${MIN_MATCHED_TOKENS} or more matched tokens`);
  return result.failures.length ? 1 : 0;
}

// Proves the JPlag call, the CSV parsing and the token counts work: a copied
// file (more than MIN_MATCHED_TOKENS tokens) must fail and an unrelated file
// must pass. Uses Bipe-written samples only.
const SAMPLES = {
  swift: [
    `struct Counter {\n  var values: [Int] = []\n  mutating func add(_ v: Int) {\n    if v > 10 {\n      values.append(v * 2)\n    } else {\n      values.append(v)\n    }\n  }\n  func total() -> Int {\n    var sum = 0\n    for v in values {\n      sum += v\n    }\n    return sum\n  }\n  func largest() -> Int? {\n    var best: Int? = nil\n    for v in values {\n      if best == nil || v > best! {\n        best = v\n      }\n    }\n    return best\n  }\n  func smallest() -> Int? {\n    var best: Int? = nil\n    for v in values {\n      if best == nil || v < best! {\n        best = v\n      }\n    }\n    return best\n  }\n  func average() -> Double {\n    if values.isEmpty {\n      return 0\n    }\n    return Double(total()) / Double(values.count)\n  }\n  mutating func removeAbove(_ limit: Int) {\n    var kept: [Int] = []\n    for v in values {\n      if v <= limit {\n        kept.append(v)\n      }\n    }\n    values = kept\n  }\n  func count(of target: Int) -> Int {\n    var n = 0\n    for v in values {\n      if v == target {\n        n += 1\n      }\n    }\n    return n\n  }\n  func describe() -> String {\n    var parts: [String] = []\n    for v in values {\n      parts.append(String(v))\n    }\n    return parts.joined(separator: ",")\n  }\n  func doubled() -> [Int] {\n    var out: [Int] = []\n    for v in values {\n      out.append(v * 2)\n    }\n    return out\n  }\n}\n`,
    `enum Shape {\n  case circle(Double)\n  case square(Double)\n}\nfunc area(_ s: Shape) -> Double {\n  switch s {\n  case .circle(let r):\n    return 3.14159 * r * r\n  case .square(let a):\n    return a * a\n  }\n}\nfunc describe(_ list: [Shape]) -> String {\n  let parts = list.map { String(area($0)) }\n  return parts.joined(separator: ";")\n}\nclass Store {\n  private var items: [String: Double] = [:]\n  func put(_ k: String, _ v: Double) { items[k] = v }\n  func get(_ k: String) -> Double { items[k] ?? 0 }\n}\n`,
  ],
  kotlin: [
    `class Counter {\n  private val values = mutableListOf<Int>()\n  fun add(v: Int) {\n    if (v > 10) {\n      values.add(v * 2)\n    } else {\n      values.add(v)\n    }\n  }\n  fun total(): Int {\n    var sum = 0\n    for (v in values) {\n      sum += v\n    }\n    return sum\n  }\n  fun largest(): Int? {\n    var best: Int? = null\n    for (v in values) {\n      if (best == null || v > best) {\n        best = v\n      }\n    }\n    return best\n  }\n  fun smallest(): Int? {\n    var best: Int? = null\n    for (v in values) {\n      if (best == null || v < best) {\n        best = v\n      }\n    }\n    return best\n  }\n  fun average(): Double {\n    if (values.isEmpty()) {\n      return 0.0\n    }\n    return total().toDouble() / values.size\n  }\n  fun removeAbove(limit: Int) {\n    val kept = mutableListOf<Int>()\n    for (v in values) {\n      if (v <= limit) {\n        kept.add(v)\n      }\n    }\n    values.clear()\n    values.addAll(kept)\n  }\n  fun count(target: Int): Int {\n    var n = 0\n    for (v in values) {\n      if (v == target) {\n        n += 1\n      }\n    }\n    return n\n  }\n  fun describe(): String {\n    val parts = mutableListOf<String>()\n    for (v in values) {\n      parts.add(v.toString())\n    }\n    return parts.joinToString(",")\n  }\n}\n`,
    `sealed class Shape {\n  data class Circle(val r: Double) : Shape()\n  data class Square(val a: Double) : Shape()\n}\nfun area(s: Shape): Double = when (s) {\n  is Shape.Circle -> 3.14159 * s.r * s.r\n  is Shape.Square -> s.a * s.a\n}\nfun describe(list: List<Shape>): String {\n  return list.map { area(it).toString() }.joinToString(";")\n}\nclass Store {\n  private val items = mutableMapOf<String, Double>()\n  fun put(k: String, v: Double) { items[k] = v }\n  fun get(k: String): Double = items[k] ?: 0.0\n}\n`,
  ],
};

function selfTest(opts) {
  let failed = 0;
  for (const lang of Object.keys(LANGS)) {
    const ext = LANGS[lang].ext;
    const work = mkdtempSync(join(process.env.RUNNER_TEMP ?? tmpdir(), `similarity-self-test-${lang}-`));
    const src = join(work, 'src');
    mkdirSync(src);
    writeFileSync(join(src, `Copied${ext}`), SAMPLES[lang][0]);
    writeFileSync(join(src, `Unrelated${ext}`), SAMPLES[lang][1]);
    const ours = flatNames([`Copied${ext}`], 'o');
    const theirs = flatNames([`Copied${ext}`, `Unrelated${ext}`], 't');
    const { rows, tokens } = runJplag({ jar: opts.jar, lang, ours, theirs, oursRoot: src, theirsRoot: src, work: join(work, 'jplag') });
    const result = evaluate(rows, ours, theirs, THRESHOLD, tokens);
    const ok =
      result.failures.length === 1 && result.failures[0].theirs === `Copied${ext}` && result.notCompared.length === 0;
    console.log(`similarity self-test ${lang}: ${ok ? 'OK' : 'FAIL'} ${JSON.stringify(result)}`);
    if (!ok) {
      console.log(`::error title=similarity::self-test for ${lang} failed: JPlag did not flag a copied file`);
      failed++;
    }
  }
  return failed ? 1 : 0;
}

if (isMain(import.meta.url)) {
  const opts = parseArgs(process.argv.slice(2));
  const command = opts._[0];
  if (!opts.jar) throw new Error('--jar is required');
  const code = command === 'self-test' ? selfTest(opts) : command === 'scan' ? await scan(opts) : 2;
  if (code === 2) console.log('usage: node tools/similarity.mjs scan|self-test --jar <jplag.jar> ...');
  process.exit(code);
}

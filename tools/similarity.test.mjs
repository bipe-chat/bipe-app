// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  blockMatches,
  blockSelfTest,
  checkTokenIndex,
  codeLines,
  LANGS,
  evaluate,
  failedSubmissions,
  flatKey,
  flatNames,
  matchedTokens,
  parseCsv,
  tokenCounts,
} from './similarity.mjs';

// The shapes JPlag 6.3.0 writes with -new ours -old theirs and one file per
// submission (ReportObjectFactory, SubmissionNameToIdMapper, FilePathUtil,
// RunInformation; bipe-chat/bipe#1305).
const INDEX = {
  fileIndexes: {
    'ours_o00001_Copied.kt': { 'ours_o00001_Copied.kt/ours_o00001_Copied.kt': { tokenCount: 300 } },
    'ours_o00002_Small.kt': { 'ours_o00002_Small.kt/ours_o00002_Small.kt': { tokenCount: 40 } },
    'theirs_t00001_Original.kt': { 'theirs_t00001_Original.kt/theirs_t00001_Original.kt': { tokenCount: 310 } },
    'theirs_t00002_Setup.kt': { 'theirs_t00002_Setup.kt/theirs_t00002_Setup.kt': { tokenCount: 50 } },
  },
};

const RUN_INFORMATION = {
  version: { major: 6, minor: 3, patch: 0 },
  failedSubmissions: [{ submissionId: 'ours/o00003_Broken.kt', submissionState: 'CANNOT_PARSE' }],
  dateOfExecution: '08/11/26',
  executionTime: 10,
  totalComparisons: 3,
};

const CSV = [
  'submissionName1,submissionName2,averageSimilarity,maxSimilarity',
  'ours/o00001_Copied.kt,theirs/t00001_Original.kt,0.95,0.97',
  'ours/o00002_Small.kt,theirs/t00002_Setup.kt,0.6,0.7',
  'ours/o00004_Unindexed.kt,theirs/t00001_Original.kt,0.55,0.6',
].join('\n');

const ours = flatNames(['android/Copied.kt', 'android/Small.kt', 'android/Broken.kt', 'android/Unindexed.kt'], 'o');
const theirs = flatNames(['Original.kt', 'Setup.kt'], 't');

test('flatKey reads a submission name and an index ID', () => {
  assert.equal(flatKey('ours/o00001_Copied.kt'), 'o00001_Copied.kt');
  assert.equal(flatKey('o00001_Copied.kt'), 'o00001_Copied.kt');
  assert.equal(flatKey('README.md'), null);
});

test('tokenCounts reads the JPlag 6.3.0 file index', () => {
  const tokens = tokenCounts(INDEX);
  assert.equal(tokens.get('o00001_Copied.kt'), 300);
  assert.equal(tokens.get('t00001_Original.kt'), 310);
  assert.equal(tokens.get('o00002_Small.kt'), 40);
  assert.equal(tokens.size, 4);
});

test('failedSubmissions reads runInformation.json', () => {
  assert.deepEqual([...failedSubmissions(RUN_INFORMATION)], ['o00003_Broken.kt']);
  assert.equal(failedSubmissions({}).size, 0);
});

test('matchedTokens is average times the token sum over two, Infinity when a count is missing', () => {
  assert.equal(matchedTokens(0.95, 300, 310), 290);
  assert.equal(matchedTokens(0.5, undefined, 310), Infinity);
});

test('evaluate fails a copy, warns on a small file, fails closed on a missing count', () => {
  const result = evaluate(parseCsv(CSV), ours, theirs, 0.5, tokenCounts(INDEX), 100, failedSubmissions(RUN_INFORMATION));
  assert.deepEqual(
    result.failures.map((p) => [p.ours, p.theirs, p.matched]),
    [
      ['android/Copied.kt', 'Original.kt', 290],
      ['android/Unindexed.kt', 'Original.kt', Infinity],
    ],
  );
  assert.deepEqual(result.warnings.map((p) => [p.ours, p.matched]), [['android/Small.kt', 27]]);
  // Only a failed submission is "not compared": with -m most files are in no row.
  assert.deepEqual(result.notCompared, ['android/Broken.kt']);
});

test('checkTokenIndex fails a report with pairs and no counts', () => {
  assert.throws(() => checkTokenIndex(parseCsv(CSV), new Map()), /token index empty/);
  assert.doesNotThrow(() => checkTokenIndex(parseCsv(CSV), tokenCounts(INDEX)));
  assert.doesNotThrow(() => checkTokenIndex(parseCsv(CSV.split('\n')[0]), new Map()));
});

// Maximum-match rule (bipe-chat/bipe#1127).

test('codeLines drops comments, whitespace, imports and short lines', () => {
  const lines = codeLines('import Foundation\n/* a\n b */\nlet url = "https://bipe.chat" // note\n}\n  values.append(v * 2)\n');
  assert.deepEqual(lines, [
    { text: 'leturl="https://bipe.chat"', line: 4 },
    { text: 'values.append(v*2)', line: 6 },
  ]);
});

test('a copied block inside a large file fails the maximum-match rule', () => {
  for (const lang of Object.keys(LANGS)) assert.ok(blockSelfTest(lang), lang);
});

test('blockMatches reports the longest run once per file pair', () => {
  const block = Array.from({ length: 14 }, (_, i) => `  let value${i} = compute(${i})`).join('\n');
  const ours = new Map([['ios/A.swift', `header line one\n${block}\ntrailer line here`]]);
  const theirs = new Map([['B.swift', `${block}\n${block}`]]);
  const result = blockMatches(ours, theirs, 12);
  assert.equal(result.length, 1);
  assert.equal(result[0].lines, 14);
  assert.equal(result[0].oursLine, 2);
  assert.deepEqual(blockMatches(ours, theirs, 15), []);
});

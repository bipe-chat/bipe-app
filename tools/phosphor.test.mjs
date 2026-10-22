// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for tools/phosphor.mjs. Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { kotlinCase, parseIcon, resourceName, swiftCase, vectorDrawable, writeIcons } from './phosphor.mjs';
import { hasHeader } from './headers.mjs';

const PATH = 'M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0,1,128,216Z';
const SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" fill="currentColor"><path d="${PATH}"/></svg>`;

test('phosphor reads one path in a 256 box and rejects other shapes', () => {
  assert.equal(parseIcon(SVG, 'circle'), PATH);
  assert.throws(() => parseIcon(SVG.replace('<path', '<rect x="1"/><path'), 'two'));
  assert.throws(() => parseIcon(SVG.replace('0 0 256 256', '0 0 24 24'), 'small'));
});

test('phosphor names follow each platform', () => {
  assert.equal(swiftCase('paper-plane-right'), 'paperPlaneRight');
  assert.equal(kotlinCase('paper-plane-right'), 'PAPER_PLANE_RIGHT');
  assert.equal(resourceName('paper-plane-right'), 'phosphor_paper_plane_right');
  assert.match(vectorDrawable(PATH), /android:viewportWidth="256"/);
});

test('phosphor writes both platforms from a package', () => {
  const root = mkdtempSync(join(tmpdir(), 'bipe-phosphor-'));
  const pkg = join(root, 'package');
  mkdirSync(join(pkg, 'assets/regular'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@phosphor-icons/core', version: '9.9.9', license: 'MIT' }));
  writeFileSync(join(pkg, 'LICENSE'), 'MIT License\n');
  writeFileSync(join(pkg, 'assets/regular/chat-circle.svg'), SVG);
  mkdirSync(join(root, 'tools'));
  writeFileSync(join(root, 'tools/phosphor.json'), JSON.stringify({ version: '9.9.9', weight: 'regular', icons: ['chat-circle'] }));

  assert.deepEqual(writeIcons(pkg, root), ['chat-circle']);
  const swift = readFileSync(join(root, 'ios/Phosphor/Sources/Phosphor/PhosphorIcon.swift'), 'utf8');
  const kotlin = readFileSync(join(root, 'android/libraries/phosphor/src/main/kotlin/chat/bipe/phosphor/PhosphorIcon.kt'), 'utf8');
  assert.ok(hasHeader(swift) && hasHeader(kotlin));
  assert.match(swift, /case chatCircle = "chat-circle"/);
  assert.match(kotlin, /CHAT_CIRCLE\("chat-circle", R\.drawable\.phosphor_chat_circle\)/);
  assert.ok(existsSync(join(root, 'ios/Phosphor/Sources/Phosphor/Resources/Phosphor.xcassets/chat-circle.imageset/chat-circle.svg')));
  assert.ok(existsSync(join(root, 'android/libraries/phosphor/src/main/res/drawable/phosphor_chat_circle.xml')));
  assert.ok(existsSync(join(root, 'ios/Phosphor/LICENSE')) && existsSync(join(root, 'android/libraries/phosphor/LICENSE')));

  writeFileSync(join(root, 'tools/phosphor.json'), JSON.stringify({ version: '1.0.0', weight: 'regular', icons: ['chat-circle'] }));
  assert.throws(() => writeIcons(pkg, root), /wants @phosphor-icons\/core@1\.0\.0/);
});

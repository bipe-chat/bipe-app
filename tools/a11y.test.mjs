// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the accessibility audit (bipe-chat/bipe#495). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditKotlin, auditSwift, blank, checkA11y } from './a11y.mjs';
import { REPO_ROOT } from './lib.mjs';

const rules = (hits) => hits.map((h) => `${h.line}:${h.rule}`);

test('blank keeps offsets and hides comments and strings', () => {
  const source = 'a("{") // }\nb /* { */ c';
  const out = blank(source);
  assert.equal(out.length, source.length);
  assert.equal(out.split('\n').length, 2);
  assert.ok(!out.includes('{') && !out.includes('}'));
});

test('swift: an icon-only button without a label fails', () => {
  const source = `Button {
    close()
} label: {
    PhosphorIcon.x.image
        .resizable()
}
.buttonStyle(.plain)`;
  assert.deepEqual(rules(auditSwift(source)), ['1:icon-only']);
});

test('swift: a label on the control, a text label or a title passes', () => {
  const labelled = `Button(action: close) {
    PhosphorIcon.caretLeft.image
}
.buttonStyle(.plain)
.accessibilityLabel(BipeStrings.a11yBack)`;
  const text = 'Button { open() } label: { IconLabel(icon: .qrCode, text: BipeStrings.appProfileLink) }';
  const titled = 'Button(BipeStrings.appCancel) { PhosphorIcon.x.image }';
  for (const source of [labelled, text, titled]) assert.deepEqual(auditSwift(source), []);
});

test('swift: a label in a comment or a string does not count', () => {
  const source = `Button {
    go()
} label: {
    // Text(BipeStrings.x)
    icon.image
}
.help(".accessibilityLabel(")`;
  assert.deepEqual(rules(auditSwift(source)), ['1:icon-only']);
});

test('swift: menus and share links follow the same rule', () => {
  assert.deepEqual(rules(auditSwift('Menu { Button(BipeStrings.a) {} } label: { PhosphorIcon.dotsThree.image }')), ['1:icon-only']);
  assert.deepEqual(rules(auditSwift('ShareLink(item: url) { PhosphorIcon.shareNetwork.image }')), ['1:icon-only']);
});

test('swift: a tap gesture needs a button trait, an action or a reason', () => {
  assert.deepEqual(rules(auditSwift('Text(a)\n    .onTapGesture { go() }')), ['2:tap']);
  assert.deepEqual(auditSwift('Text(a)\n    .onTapGesture { go() }\n    .accessibilityAddTraits(.isButton)'), []);
  assert.deepEqual(auditSwift('Text(a)\n    // a11y: the row has the action.\n    .onTapGesture { go() }'), []);
});

test('kotlin: an icon button needs a content description', () => {
  const bad = `IconButton(onClick = onBack) {
    Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = null)
}`;
  const good = `IconButton(onClick = onBack) {
    Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_a11y_back))
}`;
  assert.deepEqual(rules(auditKotlin(bad)), ['1:icon-only']);
  assert.deepEqual(auditKotlin(good), []);
});

test('kotlin: a clickable box with only an icon needs a content description', () => {
  const bad = `Box(modifier = Modifier.size(72.dp).clickable(onClick = onClick)) {
    Icon(painterResource(icon.drawable), contentDescription = null)
}`;
  const text = `Row(modifier = Modifier.clickable(onClick = onClick)) {
    Icon(painterResource(icon.drawable), contentDescription = null)
    Text(title)
}`;
  const plain = 'Box(modifier = Modifier.size(8.dp)) { Icon(painterResource(icon.drawable), contentDescription = null) }';
  assert.deepEqual(rules(auditKotlin(bad)), ['1:icon-only']);
  assert.deepEqual(auditKotlin(text), []);
  assert.deepEqual(auditKotlin(plain), []);
});

test('the app passes the audit', () => {
  assert.deepEqual(checkA11y(REPO_ROOT).errors, []);
});

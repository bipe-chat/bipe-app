// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the accessibility audit (bipe-chat/bipe#495). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditKotlin, auditSwift, blank, checkA11y } from './a11y.mjs';
import { REPO_ROOT } from './lib.mjs';

const rules = (hits) => hits.map((h) => `${h.line}:${h.rule}`);
const labels = (hits) => rules(hits.filter((h) => h.rule === 'icon-only'));

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
  assert.deepEqual(labels(auditSwift(source)), ['1:icon-only']);
});

test('swift: a label on the control, a text label or a title passes', () => {
  const labelled = `Button(action: close) {
    PhosphorIcon.caretLeft.image
}
.buttonStyle(.plain)
.accessibilityLabel(BipeStrings.a11yBack)`;
  const text = 'Button { open() } label: { IconLabel(icon: .qrCode, text: BipeStrings.appProfileLink) }';
  const titled = 'Button(BipeStrings.appCancel) { PhosphorIcon.x.image }';
  for (const source of [labelled, text, titled]) assert.deepEqual(labels(auditSwift(source)), []);
});

test('swift: a label in a comment or a string does not count', () => {
  const source = `Button {
    go()
} label: {
    // Text(BipeStrings.x)
    icon.image
}
.help(".accessibilityLabel(")`;
  assert.deepEqual(labels(auditSwift(source)), ['1:icon-only']);
});

test('swift: menus and share links follow the same rule', () => {
  assert.deepEqual(labels(auditSwift('Menu { Button(BipeStrings.a) {} } label: { PhosphorIcon.dotsThree.image }')), ['1:icon-only']);
  assert.deepEqual(labels(auditSwift('ShareLink(item: url) { PhosphorIcon.shareNetwork.image }')), ['1:icon-only']);
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

test('swift: an icon-only button needs the minimum tap target outside a toolbar', () => {
  const small = 'Button { go() } label: { PhosphorIcon.x.image.frame(width: 16, height: 16) }\n.accessibilityLabel(BipeStrings.appClose)';
  const sized = 'Button { go() } label: { PhosphorIcon.x.image.frame(width: 16, height: 16).bipeTapTarget() }\n.accessibilityLabel(BipeStrings.appClose)';
  const toolbar = `.toolbar {
    ToolbarItem(placement: .cancellationAction) {
        Button(action: close) { PhosphorIcon.caretLeft.image }
            .accessibilityLabel(BipeStrings.appCancel)
    }
}`;
  assert.deepEqual(rules(auditSwift(small)), ['1:tap-target']);
  assert.deepEqual(auditSwift(sized), []);
  assert.deepEqual(auditSwift(toolbar), []);
});

test('swift: a fixed font size fails, a scaled one passes', () => {
  assert.deepEqual(rules(auditSwift('Text(code)\n    .font(.system(size: 64, weight: .bold))')), ['2:text-size']);
  assert.deepEqual(rules(auditSwift('Button(t) {}\n    .buttonStyle(.bordered)\n    .controlSize(.small)')), ['2:button-size', '3:button-size']);
  assert.deepEqual(rules(auditSwift('Button(t) {}\n    .buttonStyle(.borderedProminent)')), ['2:button-size']);
  assert.deepEqual(rules(auditSwift('Button(t) {}\n    .buttonStyle(.bipeBordered)\n    .controlSize(.large)')), []);
  assert.deepEqual(auditSwift('Text(code)\n    .font(.system(size: codeSize, weight: .bold))'), []);
});

test('kotlin: a small clickable needs the minimum tap target', () => {
  const small = `Box(
    modifier = Modifier
        .size(40.dp)
        .clickable(onClick = onTogglePlay)
        .semantics { contentDescription = playLabel },
) {}`;
  const sized = small.replace('Modifier\n', 'Modifier\n        .bipeTapTarget()\n');
  const row = 'Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) { Text(title) }';
  assert.deepEqual(rules(auditKotlin(small)), ['4:tap-target']);
  assert.deepEqual(auditKotlin(sized), []);
  assert.deepEqual(auditKotlin(row), []);
});

test('kotlin: text sized in dp or a forced font scale fails', () => {
  assert.deepEqual(rules(auditKotlin('Text(a, fontSize = 16.dp.toSp())')), ['1:text-size']);
  assert.deepEqual(rules(auditKotlin('Density(density.density, fontScale = 1f)')), ['1:text-size']);
  assert.deepEqual(rules(auditKotlin('Button(onClick = {}) { Text(a) }\nOutlinedButton(onClick = {}) { Text(b) }')), ['1:button-size', '2:button-size']);
  assert.deepEqual(rules(auditKotlin('BipeButton(onClick = {}) { Text(a) }\nTextButton(onClick = {}) { Text(b) }\nandroidx.compose.material3.Button(onClick) {}')), []);
  assert.deepEqual(auditKotlin('Text(a, fontSize = 16.sp)'), []);
});

test('swift: a long press or swipe actions need an accessibility action', () => {
  assert.deepEqual(rules(auditSwift('Text(version)\n    .onLongPressGesture { copy() }')), ['2:gesture']);
  assert.deepEqual(auditSwift('Text(version)\n    .onLongPressGesture { copy() }\n    .accessibilityAction(named: BipeStrings.a11yCopyDeviceId) { copy() }'), []);
  assert.deepEqual(rules(auditSwift('row\n    .swipeActions(edge: .trailing) { archive }')), ['2:gesture']);
  assert.deepEqual(auditSwift('row\n    .swipeActions(edge: .trailing) { archive }\n    .accessibilityActions { menu }'), []);
});

test('kotlin: a long press needs a label, a swipe a TalkBack action', () => {
  assert.deepEqual(rules(auditKotlin('Modifier.combinedClickable(onClick = {}, onLongClick = copy)')), ['1:gesture']);
  assert.deepEqual(auditKotlin('Modifier.combinedClickable(onClick = {}, onLongClick = copy, onLongClickLabel = label)'), []);
  assert.deepEqual(auditKotlin('Modifier.combinedClickable(onClick = open, onLongClick = null)'), []);
  assert.deepEqual(rules(auditKotlin('Modifier.pointerInput(Unit) {\n    detectHorizontalDragGestures { _, _ -> }\n}')), ['2:gesture']);
  assert.deepEqual(auditKotlin('Modifier.pointerInput(Unit) {\n    detectHorizontalDragGestures { _, _ -> }\n}.semantics { customActions = actions }'), []);
  assert.deepEqual(rules(auditKotlin('detectTapGestures(onTap = { a() }, onLongPress = { b() })')), ['1:gesture']);
  assert.deepEqual(auditKotlin('import androidx.compose.foundation.gestures.detectHorizontalDragGestures'), []);
});

test('swift: an animation needs a Reduce Motion check', () => {
  assert.deepEqual(rules(auditSwift('Button(BipeStrings.a) {\n    withAnimation { open.toggle() }\n}')), ['2:motion']);
  assert.deepEqual(rules(auditSwift('toast\n    .transition(.move(edge: .bottom))')), ['2:motion']);
  assert.deepEqual(auditSwift('Button(BipeStrings.a) {\n    withBipeAnimation { open.toggle() }\n}'), []);
  assert.deepEqual(auditSwift('guard !reduceMotion else { return }\nwithAnimation(.linear.repeatForever()) { phase = 1 }'), []);
});

test('kotlin: an animation needs a Reduce Motion check', () => {
  assert.deepEqual(rules(auditKotlin('val t = rememberInfiniteTransition(label = "typing")')), ['1:motion']);
  assert.deepEqual(rules(auditKotlin('listState.animateScrollToItem(0)')), ['1:motion']);
  assert.deepEqual(auditKotlin('val shown by animateFloatAsState(swipe, animationSpec = bipeMotion(spring<Float>()))'), []);
  assert.deepEqual(auditKotlin('val reduceMotion = LocalReduceMotion.current\nif (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)'), []);
});

test('swift: a material needs a solid fallback', () => {
  assert.deepEqual(rules(auditSwift('bar\n    .background(.ultraThinMaterial)')), ['2:transparency']);
  assert.deepEqual(auditSwift('bar\n    .bipeMaterial(in: Rectangle())'), []);
});

test('the app passes the audit', () => {
  assert.deepEqual(checkA11y(REPO_ROOT).errors, []);
});

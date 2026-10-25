// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Accessibility audit (bipe-chat/bipe#495): every control in the Bipe screens says what it does
// to VoiceOver and TalkBack. A static check of the source; nothing runs on a simulator or a device.
// Usage: node tools/a11y.mjs [repo-root]
//
// Swift (ios/):
// - icon-only: a Button, Menu, NavigationLink, ShareLink or Link whose label shows an icon
//   (PhosphorIcon, .image, Image, AvatarView) and no Text or Label, with no title argument, needs
//   .accessibilityLabel(...) on the control or on its label (or .accessibilityHidden(true)).
//   PhosphorIcon.image is decorative (tools/phosphor.mjs), so the icon itself says nothing.
// - tap: a view with .onTapGesture needs .accessibilityAddTraits(.isButton), .accessibilityAction,
//   .accessibilityHidden(true) or .accessibilityElement(...) within 8 lines before or after it, or a
//   comment "// a11y: <why>" on its line or the line before (the gesture has another accessible path).
// - tap-target: the same controls, outside a toolbar, put .bipeTapTarget() on their label (44 x 44 pt).
// - text-size: no .font(.system(size: <number>)); use a text style or a @ScaledMetric size.
// - gesture: .onLongPressGesture and .swipeActions need an .accessibilityAction(s) within 8 lines.
// - motion: withAnimation, .animation(, repeatForever, .move(edge:) and .symbolEffect( need a Reduce Motion
//   check within 8 lines; withBipeAnimation, .bipeAnimation and .bipeMove (Common/Motion.swift) have it.
// Kotlin (android/):
// - icon-only: IconButton, FilledIconButton, FilledTonalIconButton, OutlinedIconButton,
//   IconToggleButton and the FloatingActionButton family need a non-null contentDescription or a Text
//   inside the call. A Box, Row or Surface made clickable
//   (clickable, combinedClickable, toggleable, selectable, onClick =) that shows an Icon or Image has
//   the same rule.
// - tap-target: a click modifier (clickable, combinedClickable, toggleable, selectable) in a chain with
//   size, width or height below 48.dp needs bipeTapTarget() or minimumInteractiveComponentSize().
// - text-size: no .dp.toSp() and no forced fontScale = 1f.
// - gesture: a combinedClickable with onLongClick needs onLongClickLabel; detectHorizontalDragGestures,
//   SwipeToDismissBox and detectTapGestures(onLongPress =) need customActions, setProgress or onLongClick { }
//   semantics within 20 lines (or a "// a11y: <why>" comment).
// - motion: rememberInfiniteTransition, animateScrollTo(Item), animate*AsState and the slide, expand and
//   shrink transitions need LocalReduceMotion, bipeMotion() or reduceMotion within 12 lines.
//
// Limits: this reads the source, it does not build the views.
// - It does not follow values: a label built in another function or view counts only when that
//   view is in the call (Text, Label, accessibilityLabel).
// - A Swift label view that is not one of the icon views above (a custom row view) is not checked.
// - The Swift tap rule is a line window: a trait more than 8 lines away from the gesture is not seen.
// - Raw strings (#"..."#) are not parsed; a brace inside one can end a call too early.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { sourceFiles, runCheck, isMain } from './lib.mjs';

// Replaces comments and string contents with spaces, so that braces and words in them do not count.
// Keeps the offsets and the line breaks of the text.
export function blank(text) {
  let out = '';
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    const next = text[i + 1];
    if (c === '/' && next === '/') {
      while (i < n && text[i] !== '\n') { out += ' '; i++; }
    } else if (c === '/' && next === '*') {
      while (i < n && !(text[i] === '*' && text[i + 1] === '/')) { out += text[i] === '\n' ? '\n' : ' '; i++; }
      if (i < n) { out += '  '; i += 2; }
    } else if (text.startsWith('"""', i)) {
      out += '"""';
      i += 3;
      while (i < n && !text.startsWith('"""', i)) { out += text[i] === '\n' ? '\n' : ' '; i++; }
      if (i < n) { out += '"""'; i += 3; }
    } else if (c === '"') {
      out += '"';
      i++;
      while (i < n && text[i] !== '"' && text[i] !== '\n') {
        if (text[i] === '\\') { out += ' '; i++; }
        out += ' ';
        i++;
      }
      if (i < n && text[i] === '"') { out += '"'; i++; }
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

const OPEN = { '(': ')', '{': '}', '[': ']' };

// Index just after the bracket group that starts at `i`, or -1 when it does not close.
function matchGroup(text, i) {
  const stack = [];
  for (let j = i; j < text.length; j++) {
    const c = text[j];
    if (OPEN[c]) stack.push(OPEN[c]);
    else if (c === ')' || c === '}' || c === ']') {
      if (stack.pop() !== c) return -1;
      if (!stack.length) return j + 1;
    }
  }
  return -1;
}

const skipSpace = (text, i) => {
  while (i < text.length && /\s/.test(text[i])) i++;
  return i;
};

// The call that starts at `start` (the name): its arguments, its trailing closures (also labelled
// ones such as `label: { ... }`) and, for Swift, the modifier chain after it.
export function callExtent(text, start, nameLength, { chain = false } = {}) {
  let i = start + nameLength;
  let args = '';
  if (text[i] === '(') {
    const end = matchGroup(text, i);
    if (end < 0) return null;
    args = text.slice(i + 1, end - 1);
    i = end;
  }
  let body = '';
  for (;;) {
    const j = skipSpace(text, i);
    if (text[j] === '{') {
      const end = matchGroup(text, j);
      if (end < 0) return null;
      body += text.slice(j, end);
      i = end;
      continue;
    }
    const label = /^[A-Za-z_]\w*:\s*\{/.exec(text.slice(j, j + 80));
    if (label && body) {
      const open = j + label[0].length - 1;
      const end = matchGroup(text, open);
      if (end < 0) return null;
      body += text.slice(open, end);
      i = end;
      continue;
    }
    break;
  }
  let modifiers = '';
  if (chain) {
    for (;;) {
      const j = skipSpace(text, i);
      const m = /^\.[A-Za-z_]\w*/.exec(text.slice(j, j + 80));
      if (!m) break;
      let k = j + m[0].length;
      if (text[k] === '(') {
        const end = matchGroup(text, k);
        if (end < 0) break;
        k = end;
      }
      for (;;) {
        const l = skipSpace(text, k);
        if (text[l] !== '{') break;
        const end = matchGroup(text, l);
        if (end < 0) break;
        k = end;
      }
      modifiers += text.slice(j, k);
      i = k;
    }
  }
  return { args, body, modifiers, end: i };
}

const lineOf = (text, index) => text.slice(0, index).split('\n').length;

const SWIFT_ICON = /PhosphorIcon\.|\.image\b|\bImage\(|\bAvatarView\(/;
// Text, Label and the Bipe views whose name ends in Text or Label (IconLabel, SettingsRowLabel, MessageText).
const SWIFT_TEXT = /(?:Text|Label)\(/;
const SWIFT_LABELLED = /\.accessibilityLabel\(|\.accessibilityHidden\(\s*true\s*\)/;
// Arguments that are not a title: Button(action:), Button(role:), Menu(content:) and the like.
const NO_TITLE = /^\s*(action|role|intent|primaryAction|content|label|item|items|destination|value)\s*:/;

// Swift: icon-only Button and Menu without a label, and tap gestures without a button trait.
export function auditSwift(source) {
  const text = blank(source);
  const hits = [];
  for (const m of text.matchAll(/(?<![\w.])(Button|Menu|NavigationLink|ShareLink|Link)(?=\s*[({])/g)) {
    const call = callExtent(text, m.index, m[1].length, { chain: true });
    if (!call) continue;
    const titled = call.args.trim() !== '' && !NO_TITLE.test(call.args);
    if (titled) continue;
    const content = call.args + call.body;
    if (!SWIFT_ICON.test(content) || SWIFT_TEXT.test(content)) continue;
    const line = lineOf(text, m.index);
    if (!SWIFT_LABELLED.test(content) && !SWIFT_LABELLED.test(call.modifiers)) hits.push({ line, rule: 'icon-only', call: m[1] });
    // The system sizes toolbar items itself.
    const inToolbar = /ToolbarItem|\.toolbar\b/.test(text.slice(Math.max(0, m.index - 300), m.index));
    if (!inToolbar && !/\.bipeTapTarget\(\)/.test(content)) hits.push({ line, rule: 'tap-target', call: m[1] });
  }
  // Text that does not grow with the system text size (bipe-chat/bipe#498).
  for (const m of text.matchAll(/\.font\(\s*\.system\(\s*size:\s*\d/g)) hits.push({ line: lineOf(text, m.index), rule: 'text-size', call: '.font(.system(size:)' });
  // Reduce Motion (bipe-chat/bipe#496): withBipeAnimation and .bipeAnimation, or a check of the setting.
  hits.push(
    ...windowRule(source, text, /\bwithAnimation\b|\.animation\(|\brepeatForever\b|\.move\(edge:|\.symbolEffect\(/, /accessibilityReduceMotion|isReduceMotionEnabled|\breduceMotion\b/, 8, 'motion'),
  );
  // A long press or a swipe needs an accessibility action next to it (bipe-chat/bipe#499).
  hits.push(...windowRule(source, text, /\.onLongPressGesture\b|\.swipeActions\(/, /\.accessibilityActions?\b/, 8, 'gesture'));
  hits.push(
    ...windowRule(source, text, /\.onTapGesture\b/, /\.accessibilityAddTraits\(\s*\.isButton|\.accessibilityAction\b|\.accessibilityHidden\(\s*true|\.accessibilityElement\(/, 8, 'tap'),
  );
  return hits;
}

// Lines that match `trigger` need a line that matches `answer` within `size` lines before or after,
// or a comment "// a11y: <why>" on the line or the line before.
function windowRule(source, text, trigger, answer, size, rule) {
  const lines = text.split('\n');
  const raw = source.split('\n');
  const hits = [];
  lines.forEach((line, i) => {
    const m = trigger.exec(line);
    if (!m || /^\s*import\s/.test(line)) return;
    if (/\/\/ a11y:/.test(raw[i]) || /\/\/ a11y:/.test(raw[i - 1] ?? '')) return;
    if (answer.test(lines.slice(Math.max(0, i - size), i + size + 1).join('\n'))) return;
    hits.push({ line: i + 1, rule, call: m[0].replace(/^\./, '').replace(/\($/, '') });
  });
  return hits;
}

const KOTLIN_ICON_BUTTONS = [
  'IconButton',
  'FilledIconButton',
  'FilledTonalIconButton',
  'OutlinedIconButton',
  'IconToggleButton',
  'FloatingActionButton',
  'SmallFloatingActionButton',
  'LargeFloatingActionButton',
];
const KOTLIN_LABELLED = /contentDescription\s*=\s*(?!null\b)\S|\bText\(/;

// Kotlin: icon buttons without a content description.
export function auditKotlin(source) {
  const text = blank(source);
  const hits = [];
  const names = KOTLIN_ICON_BUTTONS.join('|');
  for (const m of text.matchAll(new RegExp(`(?<![\\w.])(${names})(?=\\s*[({])`, 'g'))) {
    const call = callExtent(text, m.index, m[1].length);
    if (!call) continue;
    if (KOTLIN_LABELLED.test(call.args + call.body)) continue;
    hits.push({ line: lineOf(text, m.index), rule: 'icon-only', call: m[1] });
  }
  // A Box, Row or Surface made clickable that shows an icon and no text: the same rule.
  for (const m of text.matchAll(/(?<![\w.])(Box|Row|Surface)(?=\s*\()/g)) {
    const call = callExtent(text, m.index, m[1].length);
    if (!call || !/\.(clickable|combinedClickable|toggleable|selectable)\(|\bonClick\s*=/.test(call.args)) continue;
    if (!/\b(Icon|Image)\(/.test(call.body)) continue;
    if (KOTLIN_LABELLED.test(call.args + call.body) || /\bsemantics\b|clearAndSetSemantics/.test(call.args)) continue;
    hits.push({ line: lineOf(text, m.index), rule: 'icon-only', call: m[1] });
  }
  // A clickable element smaller than 48 dp needs the minimum tap target (bipe-chat/bipe#498). The chain
  // runs from the Modifier before the click modifier to the last line that starts with a dot.
  for (const m of text.matchAll(/\.(clickable|combinedClickable|toggleable|selectable)\b(?=\s*[({])/g)) {
    const start = text.lastIndexOf('Modifier', m.index);
    if (start < 0) continue;
    const after = text.slice(m.index).split('\n');
    let chain = text.slice(start, m.index) + after[0];
    for (const l of after.slice(1)) {
      if (!/^\s*\./.test(l)) break;
      chain += l;
    }
    const small = [...chain.matchAll(/\.(?:size|height|width)\(\s*(\d+)\.dp\s*\)/g)].some((s) => Number(s[1]) < 48);
    if (small && !/bipeTapTarget\(\)|minimumInteractiveComponentSize\(\)/.test(chain)) {
      hits.push({ line: lineOf(text, m.index), rule: 'tap-target', call: m[1] });
    }
  }
  // A long press needs a label, and a swipe or a long press gesture a TalkBack action (bipe-chat/bipe#499).
  for (const m of text.matchAll(/(?<!\w)combinedClickable(?=\s*\()/g)) {
    const call = callExtent(text, m.index, 'combinedClickable'.length);
    if (call && /\bonLongClick\s*=\s*(?!null\b)\S/.test(call.args) && !/\bonLongClickLabel\s*=/.test(call.args)) {
      hits.push({ line: lineOf(text, m.index), rule: 'gesture', call: 'combinedClickable' });
    }
  }
  hits.push(...windowRule(source, text, /\bdetectHorizontalDragGestures\b|\bSwipeToDismissBox\(|\bdetectTapGestures\(.*\bonLongPress\s*=/, /\bcustomActions\b|\bsetProgress\b|\bonLongClick\s*\{/, 20, 'gesture'));
  // Reduce Motion (bipe-chat/bipe#496): LocalReduceMotion, bipeMotion() or a check of the setting.
  hits.push(
    ...windowRule(source, text, /\brememberInfiniteTransition\(|\.animateScrollTo(?:Item)?\(|\banimate\w*AsState\(|\b(?:slideIn|slideOut|expandVertically|expandHorizontally|shrinkVertically|shrinkHorizontally)\w*\(/, /\bLocalReduceMotion\b|\bbipeMotion\(|\breduceMotion\b/, 12, 'motion'),
  );
  // Text that does not grow with the system font size (bipe-chat/bipe#498).
  for (const m of text.matchAll(/\.dp\.toSp\(\)|fontScale\s*=\s*1(?:\.0)?f/g)) hits.push({ line: lineOf(text, m.index), rule: 'text-size', call: m[0] });
  return hits;
}

const ADVICE = {
  'icon-only': 'shows only an icon and has no label. Add an accessibility label from the generated bipe-shared strings.',
  tap: 'is a tap target that VoiceOver does not announce. Add .accessibilityAddTraits(.isButton) or an accessibility action, or use a Button.',
  'tap-target': 'can be smaller than 44 pt / 48 dp to tap. Add .bipeTapTarget() (on the Swift label, first in the Compose chain).',
  motion: 'animates without a check of Reduce Motion. Use withBipeAnimation or .bipeAnimation (SwiftUI), LocalReduceMotion or bipeMotion() (Compose).',
  gesture: 'is a gesture with no accessible way to do the same. Add an accessibility action (SwiftUI .accessibilityAction, Compose customActions or onLongClickLabel).',
  'text-size': 'does not follow the system text size. Use a text style (SwiftUI .font(.body), @ScaledMetric; Compose typography or sp).',
};

export function checkA11y(root) {
  const files = sourceFiles(root).filter((f) => f.startsWith('ios/') || f.startsWith('android/'));
  const errors = [];
  for (const file of files) {
    const source = readFileSync(join(root, file), 'utf8');
    const hits = file.endsWith('.swift') ? auditSwift(source) : auditKotlin(source);
    for (const hit of hits) errors.push(`${file}:${hit.line} ${hit.call} ${ADVICE[hit.rule]}`);
  }
  return { errors, notes: [`checked ${files.length} file(s)`] };
}

if (isMain(import.meta.url)) runCheck('a11y', checkA11y);

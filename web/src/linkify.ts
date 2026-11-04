// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Links in message text, ported from BipeMessageText.linkify and safeLink (BipeKit) with the shared
// registries messageFormatting: linkPattern (http(s) URLs, www. hosts, e-mail addresses) and phonePattern
// (phone numbers, as tel:). Trailing punctuation and unbalanced closing brackets stay outside the link. Only
// the safe schemes of the registry open; an http(s) link needs a host and no user or password.

import { messageFormatting } from './bipe/generated/registries.json';

export type TextRun = { text: string; link?: string };

export function safeLink(raw: string): string | undefined {
  if (!raw || /\s/.test(raw) || [...raw].some((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)) return undefined;
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(raw)?.[1]?.toLowerCase();
  if (!scheme || !messageFormatting.safeSchemes.includes(scheme)) return undefined;
  if (scheme === 'http' || scheme === 'https') {
    try {
      const url = new URL(raw);
      if (!url.hostname || url.username || url.password) return undefined;
    } catch {
      return undefined;
    }
  }
  return raw;
}

const patterns = [messageFormatting.linkPattern, messageFormatting.phonePattern].map((p) => new RegExp(p, 'gi'));

export function linkify(text: string): TextRun[] {
  const candidates: { at: number; length: number; kind: number }[] = [];
  patterns.forEach((re, kind) => {
    re.lastIndex = 0;
    for (let m = re.exec(text); m; m = re.exec(text)) {
      candidates.push({ at: m.index, length: m[0].length, kind });
      if (m[0].length === 0) re.lastIndex++;
    }
  });
  candidates.sort((a, b) => a.at - b.at || a.kind - b.kind);
  const runs: TextRun[] = [];
  let cursor = 0;
  for (const c of candidates) {
    if (c.at < cursor) continue;
    let value = text.slice(c.at, c.at + c.length);
    while (value && '.,;!?'.includes(value[value.length - 1])) value = value.slice(0, -1);
    for (const [open, close] of [
      ['(', ')'],
      ['[', ']'],
      ['{', '}'],
    ]) {
      const count = (ch: string) => [...value].filter((x) => x === ch).length;
      while (value.endsWith(close) && count(close) > count(open)) value = value.slice(0, -1);
    }
    if (!value) continue;
    const destination =
      c.kind === 1
        ? 'tel:' + value.replace(/[^0-9+]/g, '')
        : value.toLowerCase().startsWith('www.')
          ? 'https://' + value
          : value.includes('@') && !value.includes('://')
            ? 'mailto:' + value
            : value;
    const link = safeLink(destination);
    if (!link) continue;
    if (c.at > cursor) runs.push({ text: text.slice(cursor, c.at) });
    runs.push({ text: value, link });
    cursor = c.at + value.length;
  }
  if (cursor < text.length) runs.push({ text: text.slice(cursor) });
  return runs;
}

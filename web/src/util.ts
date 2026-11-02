// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

export function describe(e: unknown): string {
  if (e instanceof Error) return `${e.name}: ${e.message}`;
  try {
    return JSON.stringify(e);
  } catch {
    return String(e);
  }
}

// Applies a VectorDiff list (room list and timeline use the same shape).
export type DiffVariants = Record<
  | 'Append'
  | 'Clear'
  | 'PushFront'
  | 'PushBack'
  | 'PopFront'
  | 'PopBack'
  | 'Insert'
  | 'Set'
  | 'Remove'
  | 'Truncate'
  | 'Reset',
  { instanceOf(v: unknown): boolean }
>;

export function applyDiffs<T>(list: T[], diffs: unknown[], V: DiffVariants): T[] {
  const next = [...list];
  for (const d of diffs) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const inner = (d as any).inner;
    if (V.Append.instanceOf(d)) next.push(...inner.values);
    else if (V.Clear.instanceOf(d)) next.length = 0;
    else if (V.PushFront.instanceOf(d)) next.unshift(inner.value);
    else if (V.PushBack.instanceOf(d)) next.push(inner.value);
    else if (V.PopFront.instanceOf(d)) next.shift();
    else if (V.PopBack.instanceOf(d)) next.pop();
    else if (V.Insert.instanceOf(d)) next.splice(inner.index, 0, inner.value);
    else if (V.Set.instanceOf(d)) next[inner.index] = inner.value;
    else if (V.Remove.instanceOf(d)) next.splice(inner.index, 1);
    else if (V.Truncate.instanceOf(d)) next.length = inner.length;
    else if (V.Reset.instanceOf(d)) next.splice(0, next.length, ...inner.values);
  }
  return next;
}

/** Lower case without accents, so "joao" finds "João". */
export function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase();
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The signed operator list and issuer registry in this browser (bipe-shared docs/signed-lists.md, bipe-chat/bipe#1457),
// with the rules of SignedLists.swift. bipe-core verifies every envelope (seal-rules.ts); this file stores, fetches
// and picks:
// - Start from the newest valid list, stored or bundled. The bundled check skips expiry.
// - Fetch from the core only, at the current list's region core_api (https), when the page comes back to the
//   foreground and the last fetch is more than 1 hour old, and every 6 hours while it is open.
// - Keep a fetched envelope only when bipe-core says valid against the current list. Store the text as received.
//   A failed fetch or a rejected list keeps the current list and shows nothing.
// The bipe-core module loads on the first seal, not with the page.

import { uniffiInitAsync } from './core';
import { acceptList, ListType, newestList, type VerifiedList } from './seal-rules';

const FOREGROUND_MS = 60 * 60 * 1000;
const RUNNING_MS = 6 * 60 * 60 * 1000;
/** A list is a few kilobytes. A larger answer is not a list (bipe-shared#431). */
const MAX_BYTES = 256 * 1024;
const TYPES = [ListType.Operators, ListType.Issuers] as const;

const storageKey = (type: ListType) => `bipe.signedLists.${type === ListType.Operators ? 'operators' : 'issuers'}`;
const path = (type: ListType) => (type === ListType.Operators ? 'v1/operators' : 'v1/issuers');

function stored(type: ListType): string | undefined {
  try {
    return localStorage.getItem(storageKey(type)) ?? undefined;
  } catch {
    return undefined;
  }
}

function store(type: ListType, text: string): void {
  try {
    localStorage.setItem(storageKey(type), text);
  } catch {
    // Private mode or a full store: the list stays in memory for this page.
  }
}

const lists = new Map<ListType, VerifiedList | undefined>();
const listeners = new Set<() => void>();
let lastFetch = 0;
let ready: Promise<void> | undefined;

/** Loads bipe-core and the newest valid lists once, then starts the refresh rules. */
export function coreReady(): Promise<void> {
  ready ??= uniffiInitAsync(new URL('./core/bipe_core.wasm', import.meta.url)).then(() => {
    for (const type of TYPES) lists.set(type, newestList(type, stored(type)));
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && Date.now() - lastFetch >= FOREGROUND_MS) void refresh();
    });
    setInterval(() => void refresh(), RUNNING_MS);
    void refresh();
  });
  return ready;
}

/** The current list of a type, after coreReady. */
export function currentList(type: ListType): VerifiedList | undefined {
  return lists.get(type);
}

/** Called when a fetched list replaced the current one, so verified seals can be checked again. */
export function onListsChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

async function fetchText(url: string): Promise<string | undefined> {
  try {
    const res = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store' });
    if (res.status !== 200) return undefined;
    const text = await res.text();
    return text.length <= MAX_BYTES ? text : undefined;
  } catch {
    return undefined;
  }
}

/** Fetches both lists from the core and keeps each valid one. Errors keep the current list. */
async function refresh(): Promise<void> {
  lastFetch = Date.now();
  let changed = false;
  for (const type of TYPES) {
    const base = lists.get(ListType.Operators)?.operators?.region.coreApi;
    if (!base?.startsWith('https://')) return;
    const text = await fetchText(`${base.replace(/\/$/, '')}/${path(type)}`);
    if (text === undefined) continue;
    const { code, list } = acceptList(text, type, lists.get(type), BigInt(Math.floor(Date.now() / 1000)));
    if (list) {
      store(type, text);
      changed ||= list.version !== lists.get(type)?.version;
      lists.set(type, list);
    } else {
      console.info(`[trace] signed list ${path(type)}: ${code}`);
    }
  }
  if (changed) for (const listener of listeners) listener();
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Mural (spec chat-list, ADR 0246, bipe-chat/bipe#1044), step 1: the fixed rules only. This file holds the
// setting, the plan inputs and the held plan. Mural.tsx draws the tiles. The generated homePlan is the one
// source of the score and the tiers. The score and its inputs stay in this browser. The web gets no model in
// any step of ADR 0246, so the model fields stay absent.

import { useEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react';
import { accountType, apiFor } from './api';
import { homePlan } from './bipe/generated/lib/home-score.mjs';
import type { ChatSummary } from './chat-model';
import { RoomNotificationMode, type ClientLike as Client } from './generated/matrix_sdk_ffi';
import { homeScore, isOfficial } from './registries';

// The setting: per account, in this browser, off by default, no sync.

/** The localStorage key of one account's setting; sign-out removes it (sdk.ts). */
export const muralKey = (userId: string) => `bipe.mural.v1.${userId}`;

const listeners = new Set<() => void>();
// Private windows can refuse storage; the choice then lasts for this page.
const unsaved = new Map<string, boolean>();

export function readMural(userId: string): boolean {
  const kept = unsaved.get(userId);
  if (kept !== undefined) return kept;
  try {
    return localStorage.getItem(muralKey(userId)) === 'true';
  } catch {
    return false;
  }
}

export function writeMural(userId: string, on: boolean) {
  try {
    localStorage.setItem(muralKey(userId), String(on));
    unsaved.delete(userId);
  } catch {
    unsaved.set(userId, on);
  }
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  window.addEventListener('storage', listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', listener);
  };
}

/** The setting of one account, re-rendering at once when it changes, also from another tab. */
export function useMuralEnabled(userId: string): boolean {
  return useSyncExternalStore(subscribe, () => readMural(userId));
}

// The plan.

type Signals = { companies: ReadonlySet<string>; newsRoomId?: string; now: number };

/** The BipeHomeChat of a chat (spec chat-list, Mural inputs and messagesPerWeek). */
function homeChatOf(chat: ChatSummary, { companies, newsRoomId, now }: Signals) {
  const info = chat.info;
  const peer = chat.face.peer?.userId;
  const since = now - homeScore.tie.windowDays * 24 * 60 * 60 * 1000;
  let newest: { at: number; isOwn: boolean } | undefined;
  let messagesPerWeek = 0;
  for (const e of chat.local) {
    if (!e.messageLike) continue;
    if (!newest || e.at > newest.at) newest = e;
    if (e.at >= since) messagesPerWeek += 1;
  }
  return {
    id: chat.roomId,
    kind: chat.kind === 'group' ? 'group' : peer !== undefined && companies.has(peer) ? 'company' : 'person',
    // matrix-sdk-ffi does not export the master key, so the web cannot verify a seal (Trust.tsx): no seal.
    hasSeal: false,
    official: isOfficial(peer) || chat.roomId === newsRoomId,
    favourite: info?.isFavourite === true,
    muted: info?.cachedUserDefinedNotificationMode === RoomNotificationMode.Mute,
    mentioned: Number(info?.numUnreadMentions ?? 0) > 0,
    unreadCount: Number(info?.numUnreadMessages ?? 0),
    lastFromOther: newest ? !newest.isOwn : false,
    minutesSinceActivity: chat.row.at === undefined ? null : Math.floor((now - chat.row.at) / 60_000),
    messagesPerWeek,
    trustWarning: false,
  };
}

type Reason = 'mention' | 'unanswered' | null;
type PlanEntry = { id: string; tier: number | null; reason: Reason };
type Placed = { id: string; tier: number };

export type MuralTile = { chat: ChatSummary; tier: number; reason: Reason };

/**
 * The tiles in plan order. New text, badges and reasons show at once. A new position or tier applies only
 * when atRest is true (spec chat-list, Mural grid rules). A chat that leaves the set leaves at once.
 */
export function useMuralTiles({
  client,
  muralOn,
  chats,
  showsTiles,
  atRest,
}: {
  client: Client;
  muralOn: boolean;
  chats: ChatSummary[];
  showsTiles: boolean;
  atRest: boolean;
}): MuralTile[] {
  const [companies, setCompanies] = useState<ReadonlySet<string>>(() => new Set());
  const [newsRoomId, setNewsRoomId] = useState<string>();
  const held = useRef<{ key: string; placed: Placed[] }>(undefined);

  const peers = [...new Set(chats.flatMap((c) => (c.face.peer ? [c.face.peer.userId] : [])))].sort().join('\n');
  useEffect(() => {
    if (!peers) return;
    let stopped = false;
    for (const id of peers.split('\n')) {
      void accountType(id).then((type) => {
        if (stopped || type !== 'company') return;
        setCompanies((known) => (known.has(id) ? known : new Set(known).add(id)));
      });
    }
    return () => {
      stopped = true;
    };
  }, [peers]);
  useEffect(() => {
    if (!muralOn) return;
    let stopped = false;
    void apiFor(client)
      .newsRoomId()
      .then((id) => {
        if (!stopped) setNewsRoomId(id);
      });
    return () => {
      stopped = true;
    };
  }, [client, muralOn]);

  if (!showsTiles) {
    held.current = undefined;
    return [];
  }
  const now = Date.now();
  const plan = homePlan(
    chats.map((c) => homeChatOf(c, { companies, newsRoomId, now })),
    homeScore,
  ) as PlanEntry[];
  const placed = plan.flatMap((p) => (p.tier === null ? [] : [{ id: p.id, tier: p.tier }]));
  const key = placed.map((p) => `${p.id} ${p.tier}`).join('\n');
  if (!held.current || (held.current.key !== key && atRest)) held.current = { key, placed };

  const chatOf = new Map(chats.map((c) => [c.roomId, c]));
  const reasonOf = new Map(plan.map((p) => [p.id, p.reason]));
  return held.current.placed.flatMap(({ id, tier }) => {
    const chat = chatOf.get(id);
    return chat ? [{ chat, tier, reason: reasonOf.get(id) ?? null }] : [];
  });
}

const SCROLL_REST_MS = 300;

/**
 * False during a pointer press in the element, during a scroll of it and shortly after, and while the Mural
 * reply field has focus: a tile that moved then would take the person's text away.
 */
export function useScreenAtRest(element: RefObject<HTMLElement | null>, enabled: boolean): boolean {
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const el = element.current;
    if (!enabled || !el) return;
    let pressed = false;
    let scrolling = false;
    let typing = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const update = () => setBusy(pressed || scrolling || typing);
    const press = () => {
      pressed = true;
      update();
    };
    const release = () => {
      pressed = false;
      update();
    };
    const scroll = () => {
      scrolling = true;
      update();
      clearTimeout(timer);
      timer = setTimeout(() => {
        scrolling = false;
        update();
      }, SCROLL_REST_MS);
    };
    const focusIn = (e: FocusEvent) => {
      typing = e.target instanceof Element && e.target.closest('[data-mural-reply]') !== null;
      update();
    };
    const focusOut = () => {
      typing = false;
      update();
    };
    el.addEventListener('pointerdown', press);
    el.addEventListener('scroll', scroll, { passive: true });
    el.addEventListener('focusin', focusIn);
    el.addEventListener('focusout', focusOut);
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);
    window.addEventListener('blur', release);
    return () => {
      clearTimeout(timer);
      el.removeEventListener('pointerdown', press);
      el.removeEventListener('scroll', scroll);
      el.removeEventListener('focusin', focusIn);
      el.removeEventListener('focusout', focusOut);
      window.removeEventListener('pointerup', release);
      window.removeEventListener('pointercancel', release);
      window.removeEventListener('blur', release);
      setBusy(false);
    };
  }, [element, enabled]);
  return !busy;
}

// The text of a tile.

export type TileMessage = { text: string; own: boolean };

/**
 * The newest messages of a tile, oldest first (spec chat-list, MuralTile messages). One message is the row
 * text. More come from the local timeline, and the row text is the fallback when it has none.
 */
export function tileMessages(chat: ChatSummary, count: number): TileMessage[] {
  if (count > 1) {
    const local = chat.local
      .filter((e) => e.messageLike && e.preview !== undefined)
      .sort((a, b) => a.at - b.at)
      .slice(-count)
      .map((e) => ({ text: e.preview as string, own: e.isOwn }));
    if (local.length > 0) return local;
  }
  const text = chat.row.preview;
  return text === undefined ? [] : [{ text, own: chat.row.incomingAt === undefined }];
}

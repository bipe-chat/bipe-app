// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec chat-archive (ChatArchiveModel.swift). The m.lowpriority room tag is the archive and syncs with
// every device: Room.setIsLowPriority writes it. A local marker per room keeps the newest known activity
// at archive time, so that a strictly newer incoming message restores the chat (registries chatArchive,
// rule chatArchiveSync). Markers live in localStorage, per account, with no message text. Tag writes run
// one at a time; after a confirmed write the page keeps that value for echoGraceMs until the sync echo.

import { useSyncExternalStore } from 'react';
import { chatArchiveSync } from './shared-rules';
import { chatArchive } from './bipe/generated/registries.json';
import type { RoomLike as Room } from './generated/matrix_sdk_ffi';

/** The localStorage key of one account's markers; sign-out removes it (sdk.ts). */
export const archiveKey = (userId: string) => `bipe.chat-archive.v1.${userId}`;

type Stored = { markers: Record<string, number>; synced: string[] };
type Action = { room: Room; archived: boolean; marker: number | null };
export type ArchiveInput = {
  room: Room;
  roomId: string;
  serverArchived: boolean;
  activity?: number;
  incoming?: number;
};

export class ChatArchive {
  private markers: Record<string, number> = {};
  private synced = new Set<string>();
  private queued = new Set<string>();
  private followUps = new Map<string, Action>();
  private failedActions = new Map<string, Action>();
  private echoes = new Map<string, { archived: boolean; until: number }>();
  private storageFailed = false;
  private tail: Promise<void> = Promise.resolve();
  private latest: ArchiveInput[] = [];
  private listeners = new Set<() => void>();
  private version = 0;
  private readonly key: string;

  constructor(userId: string) {
    this.key = archiveKey(userId);
    this.reload();
  }

  get failed(): boolean {
    return this.storageFailed || this.failedActions.size > 0;
  }

  isArchived(roomId: string): boolean {
    return this.markers[roomId] !== undefined;
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  snapshot = () => this.version;

  /** Archive or unarchive one chat. activity is its newest known event time in ms. */
  set(room: Room, archived: boolean, activity: number | undefined) {
    const action = { room, archived, marker: archived ? Math.max(0, Math.round(activity ?? 0)) : null };
    const id = room.id();
    this.failedActions.delete(id);
    if (this.queued.has(id)) this.followUps.set(id, action);
    else this.enqueue(action);
  }

  /** Brings the markers in line with the server tags of the loaded chats. */
  reconcile(chats: ArchiveInput[]) {
    this.latest = chats;
    if (this.failed) return;
    const next = { ...this.markers };
    const synced = new Set(this.synced);
    const now = Date.now();
    for (const chat of chats) {
      if (this.queued.has(chat.roomId)) continue;
      const echo = this.echoes.get(chat.roomId);
      if (echo) {
        if (echo.archived !== chat.serverArchived && echo.until > now) continue;
        this.echoes.delete(chat.roomId);
      }
      const decision = chatArchiveSync(
        next[chat.roomId] ?? null,
        synced.has(chat.roomId),
        chat.serverArchived,
        chat.activity ?? null,
        chat.incoming ?? null,
      );
      if (decision.tagWrite !== null) {
        this.enqueue({ room: chat.room, archived: decision.tagWrite, marker: decision.marker });
        continue;
      }
      if (decision.marker === null) delete next[chat.roomId];
      else next[chat.roomId] = decision.marker;
      if (decision.synced) synced.add(chat.roomId);
      else synced.delete(chat.roomId);
    }
    if (JSON.stringify(next) === JSON.stringify(this.markers) && sameSet(synced, this.synced)) return;
    if (this.save(next, synced)) {
      this.markers = next;
      this.synced = synced;
    }
    this.changed();
  }

  retry() {
    this.reload();
    if (this.storageFailed) return this.changed();
    const actions = [...this.failedActions.values()];
    this.failedActions.clear();
    actions.forEach((a) => this.enqueue(a));
    this.reconcile(this.latest);
  }

  private enqueue(action: Action) {
    const id = action.room.id();
    if (this.queued.has(id)) return;
    this.queued.add(id);
    this.tail = this.tail.then(async () => {
      try {
        // A failed read must never overwrite another marker.
        const current = this.load();
        if (action.archived && action.marker === null) throw new Error('archive without a timestamp');
        await action.room.setIsLowPriority(action.archived, undefined);
        const markers = { ...current.markers };
        const synced = new Set(current.synced);
        if (action.archived && action.marker !== null) {
          markers[id] = action.marker;
          synced.add(id);
        } else {
          delete markers[id];
          synced.delete(id);
        }
        if (!this.save(markers, synced)) throw new Error('archive storage failed');
        this.markers = markers;
        this.synced = synced;
        this.storageFailed = false;
        this.echoes.set(id, { archived: action.archived, until: Date.now() + chatArchive.echoGraceMs });
        setTimeout(() => this.reconcile(this.latest), chatArchive.echoGraceMs);
      } catch (e) {
        console.warn('[trace] archive write failed', id, e);
        this.failedActions.set(id, action);
      }
      this.queued.delete(id);
      const followUp = this.followUps.get(id);
      this.followUps.delete(id);
      if (followUp) this.enqueue(followUp);
      this.changed();
      this.reconcile(this.latest);
    });
    this.changed();
  }

  private load(): Stored {
    const raw = localStorage.getItem(this.key);
    if (raw === null) return { markers: {}, synced: [] };
    const parsed = JSON.parse(raw) as Stored;
    if (typeof parsed !== 'object' || parsed === null || !Array.isArray(parsed.synced)) throw new Error('bad archive');
    return parsed;
  }

  private reload() {
    try {
      const stored = this.load();
      this.markers = stored.markers;
      this.synced = new Set(stored.synced);
      this.storageFailed = false;
    } catch (e) {
      console.warn('[trace] archive storage unreadable', e);
      this.storageFailed = true;
    }
  }

  private save(markers: Record<string, number>, synced: Set<string>): boolean {
    try {
      localStorage.setItem(this.key, JSON.stringify({ markers, synced: [...synced] } satisfies Stored));
      return true;
    } catch (e) {
      console.warn('[trace] archive storage write failed', e);
      this.storageFailed = true;
      return false;
    }
  }

  private changed() {
    this.version++;
    this.listeners.forEach((l) => l());
  }
}

function sameSet(a: Set<string>, b: Set<string>) {
  return a.size === b.size && [...a].every((x) => b.has(x));
}

/** Re-renders when the archive changes. */
export function useArchiveVersion(archive: ChatArchive): number {
  return useSyncExternalStore(archive.subscribe, archive.snapshot);
}

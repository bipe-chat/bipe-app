// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The time and text of a chat list row (spec chat-list, row date and row text; BipeRowActivity in BipeKit
// and libraries/bipe). The SDK picks a room's latest event once, when the event arrives, and skips events
// it cannot decrypt at that moment, so a row can keep an older event or have none. Each row also reads its
// room's local timeline: a newer message there gives the row its time and preview. The time of an event is
// never encrypted. Never the current time, never a guess.
//
// The phones read each timeline once and read it again on a room list change or a retry timer
// (RowActivityScanner). The web keeps one listener per loaded room instead: the first update is the cached
// items, and later updates bring new events and events that decrypt later, so no read is ever stale.

import {
  ProfileDetails,
  TimelineDiff,
  TimelineItemContent,
  type EventTimelineItem,
  type RoomLike as Room,
  type TaskHandleLike as TaskHandle,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import type { Latest } from './chat-model';
import { rowPreview } from './row-preview';
import { applyDiffs, type DiffVariants } from './util';

/** What a row shows: its time and text, and the time of its newest incoming message (for the archive). */
export type Row = { at?: number; preview?: string; incomingAt?: number };

/** One event of the room's local timeline. A message is message-like (or a membership line with text). */
export type LocalEvent = {
  at: number;
  message: boolean;
  preview?: string;
  isOwn: boolean;
};

/** The SDK latest event of a row. */
export type SdkLatest = { at: number; preview?: string; isOwn: boolean };

/**
 * The row of a room (BipeRowActivity.resolve; the same rules on every platform). Only a local event with a
 * preview gives the row its text: a call, a deleted message or a room change never blanks it.
 * 1. A local message with a preview newer than the SDK latest event: its time and preview.
 * 2. Else the SDK latest event. Without a preview it keeps the newest local preview, else the known one.
 * 3. Without an SDK latest event: the newest local message with a preview, else the newest local event of
 *    any kind (time only). A known time is kept when it is newer, and a known preview when there is none.
 */
export function resolveRow(sdk: SdkLatest | undefined, local: LocalEvent[], known: Row | undefined): Row {
  let newest: LocalEvent | undefined;
  for (const e of local) if (e.message && e.preview !== undefined && (!newest || e.at > newest.at)) newest = e;
  const text = newest ? rowOf(newest) : known;
  if (sdk) {
    if (newest && newest.at > sdk.at) return rowOf(newest);
    if (sdk.preview === undefined && text?.preview !== undefined) {
      return { at: sdk.at, preview: text.preview, incomingAt: text.incomingAt };
    }
    return {
      at: sdk.at,
      preview: sdk.preview,
      incomingAt: !sdk.isOwn && sdk.preview !== undefined ? sdk.at : undefined,
    };
  }
  if (newest) return { ...rowOf(newest), at: Math.max(newest.at, known?.at ?? 0) };
  const times = local.map((e) => e.at);
  if (known?.at !== undefined) times.push(known.at);
  return {
    at: times.length ? Math.max(...times) : undefined,
    preview: known?.preview,
    incomingAt: known?.incomingAt,
  };
}

function rowOf(e: LocalEvent): Row {
  return {
    at: e.at,
    preview: e.preview,
    incomingAt: !e.isOwn && e.preview !== undefined ? e.at : undefined,
  };
}

/** A timeline event in the shape of the SDK latest event, so both get the same preview. */
function latestOfEvent(event: EventTimelineItem): Latest {
  const senderName = ProfileDetails.Ready.instanceOf(event.senderProfile)
    ? event.senderProfile.inner.displayName
    : undefined;
  return {
    at: Number(event.timestamp),
    isOwn: event.isOwn,
    sender: event.sender,
    senderName,
    content: event.content,
  };
}

/** The local events of a room's timeline items, with the same preview as the SDK latest event. */
export function localEvents(items: TimelineItem[], isDm: boolean, ownUserId: string): LocalEvent[] {
  const events: LocalEvent[] = [];
  for (const item of items) {
    const event = item.asEvent();
    if (!event) continue;
    const latest = latestOfEvent(event);
    const preview = rowPreview(latest, isDm, ownUserId);
    events.push({
      at: latest.at,
      message: isMessage(event.content, preview),
      preview,
      isOwn: latest.isOwn,
    });
  }
  return events;
}

// Message-like events, undecryptable ones included, and calls (the SDK takes a call as a latest event too).
// A join, leave or room creation counts when it has a line (spec chat-list, row text).
function isMessage(content: TimelineItemContent, preview: string | undefined): boolean {
  if (
    TimelineItemContent.MsgLike.instanceOf(content) ||
    TimelineItemContent.RtcNotification.instanceOf(content) ||
    TimelineItemContent.CallInvite.instanceOf(content)
  ) {
    return true;
  }
  return (
    (TimelineItemContent.RoomMembership.instanceOf(content) || TimelineItemContent.State.instanceOf(content)) &&
    preview !== undefined
  );
}

type Watch = { items?: TimelineItem[]; handle?: TaskHandle; started: boolean; stopped: boolean };

/**
 * One live timeline listener per loaded room. Listeners open one at a time, so a long room list does not
 * build every timeline at once. A room that leaves the list closes its listener.
 */
export class RoomTimelines {
  private watched = new Map<string, Watch>();
  private queue: Room[] = [];
  private opening = false;
  private pending = false;

  constructor(private readonly onChange: () => void) {}

  /** The room's timeline items, or undefined before the first update. */
  items(roomId: string): TimelineItem[] | undefined {
    return this.watched.get(roomId)?.items;
  }

  watch(rooms: Room[]) {
    const ids = new Set(rooms.map((r) => r.id()));
    for (const [id, w] of this.watched) if (!ids.has(id)) this.stop(id, w);
    for (const room of rooms) {
      if (this.watched.has(room.id())) continue;
      this.watched.set(room.id(), { started: false, stopped: false });
      this.queue.push(room);
    }
    void this.open();
  }

  dispose() {
    for (const [id, w] of this.watched) this.stop(id, w);
    this.queue = [];
  }

  private stop(id: string, w: Watch) {
    w.stopped = true;
    w.handle?.cancel();
    this.watched.delete(id);
  }

  private async open() {
    if (this.opening) return;
    this.opening = true;
    for (let room = this.queue.shift(); room; room = this.queue.shift()) {
      const w = this.watched.get(room.id());
      // A room that left the list and came back is in the queue twice: one listener only.
      if (!w || w.started || w.stopped) continue;
      w.started = true;
      try {
        const timeline = await room.timeline();
        if (w.stopped) continue;
        w.handle = await timeline.addListener({
          onUpdate: (diffs) => {
            if (w.stopped) return;
            w.items = applyDiffs(w.items ?? [], diffs, TimelineDiff as unknown as DiffVariants);
            this.changed();
          },
        });
        if (w.stopped) w.handle.cancel();
      } catch (e) {
        console.warn('[trace] row timeline failed', room.id(), e);
      }
    }
    this.opening = false;
  }

  // Many rooms update in one sync: one change per task, not one per room.
  private changed() {
    if (this.pending) return;
    this.pending = true;
    setTimeout(() => {
      this.pending = false;
      this.onChange();
    });
  }
}

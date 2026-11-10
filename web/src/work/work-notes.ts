// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The notes room of a work account (bipe ADR 0284 points 18 and 19; registries/work-notes.json; spec work-notes): one
// private, encrypted room with only the account's own devices, its ID in global account data. It is the own-room of
// notes.ts with another account data type. A note is an m.text m.room.message with the noteField {room_id, seat_id};
// after each send to a customer this browser sends one m.notice m.room.message, body replyByBody, with the replyByField
// {room_id, event_id, seat_id}. Both are m.room.message because matrix-sdk-ffi 26.08.11 drops custom event types from
// the timeline. The rules workNoteFromEvent and replyByFromEvent of bipe-shared decide what shows; a reply-by notice is
// never a note (msgtype m.notice). The room is read live once a chat of the queue opens.

import { useEffect, useSyncExternalStore } from 'react';
import { replyByFromEvent, workNoteFromEvent } from '../shared-rules';
import { workNotes } from '../bipe/generated/registries.json';
import {
  EventOrTransactionId,
  Membership,
  MsgLikeKind,
  TimelineDiff,
  TimelineItemContent,
  type ClientLike as Client,
  type TaskHandleLike as TaskHandle,
  type TimelineItemLike as TimelineItem,
  type TimelineLike as Timeline,
} from '../generated/matrix_sdk_ffi';
import { openOwnRoom, readOwnRoomId, type OwnRoom } from '../notes';
import { s } from '../ui/i18n';
import { applyDiffs, type DiffVariants } from '../util';

const reg = { workNotes };

export const NOTE_MAX_LENGTH = workNotes.bodyMaxLength;

const NOTES_ROOM: OwnRoom = {
  accountDataType: workNotes.accountDataType,
  pendingKey: (userId) => `bipe.next.work-notes.pending.${userId}`,
  name: () => s('bipe_chat_notes_title'),
};

/** An event of the notes room in the shape of the rules: { sender, type, redacted, content }. */
type RoomEvent = { eventId: string; at: number; sender: string; type: string; redacted: boolean; content: unknown };

/** seatId: the seat that wrote the note, null when it names no seat of the list. */
export type WorkNote = { eventId: string; at: number; body: string; seatId: string | null };

type State = { roomId?: string; events: RoomEvent[] };

class WorkNotesRoom {
  private state: State = { events: [] };
  private listeners = new Set<() => void>();
  private items: TimelineItem[] = [];
  private timeline?: Timeline;
  // The listener lives as long as the page: the room is small and silent.
  private handle?: TaskHandle;
  private starting?: Promise<void>;

  constructor(private readonly client: Client) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  snapshot = () => this.state;

  private set(next: State) {
    this.state = next;
    for (const listener of this.listeners) listener();
  }

  /** Reads account data and, when the room exists, follows its timeline. Never creates the room. */
  start(): Promise<void> {
    if (this.timeline) return Promise.resolve();
    if (this.starting) return this.starting;
    this.starting = readOwnRoomId(this.client, NOTES_ROOM.accountDataType)
      .then((roomId) => (roomId ? this.watch(roomId) : undefined))
      .catch((e: unknown) => console.warn('[trace] work notes read failed', e))
      .finally(() => {
        this.starting = undefined;
      });
    return this.starting;
  }

  private async watch(roomId: string) {
    if (this.timeline) return;
    const room = this.client.getRoom(roomId);
    if (!room || (await room.roomInfo()).membership !== Membership.Joined) return;
    const timeline = await room.timeline();
    this.timeline = timeline;
    this.set({ ...this.state, roomId });
    this.handle = await timeline.addListener({
      onUpdate: (diffs) => {
        this.items = applyDiffs(this.items, diffs, TimelineDiff as unknown as DiffVariants);
        this.set({ roomId, events: this.items.map(eventOf).filter((e) => e !== undefined) });
      },
    });
    // The notes room is small: read back to its start, a few pages at most.
    for (let page = 0; page < 5; page++) {
      if (await timeline.paginateBackwards(50).catch(() => true)) break;
    }
  }

  /** Spec work-notes send: creates the room on first use, then an m.room.message with the noteField. */
  async sendNote(chatRoomId: string, body: string, seatId: string | undefined): Promise<void> {
    const room = await this.room();
    const field = seatId ? { room_id: chatRoomId, seat_id: seatId } : { room_id: chatRoomId };
    await room.sendRaw('m.room.message', JSON.stringify({ msgtype: 'm.text', body, [workNotes.noteField]: field }));
  }

  /** Spec work-chat-bar reply: after a send to a customer, who sent it. */
  async sendReplyBy(chatRoomId: string, eventId: string, seatId: string): Promise<void> {
    const room = await this.room();
    await room.sendRaw(
      'm.room.message',
      JSON.stringify({
        msgtype: 'm.notice',
        body: workNotes.replyByBody,
        [workNotes.replyByField]: { room_id: chatRoomId, event_id: eventId, seat_id: seatId },
      }),
    );
  }

  private async room() {
    const roomId = await openOwnRoom(this.client, NOTES_ROOM);
    const room = this.client.getRoom(roomId);
    if (!room) throw new Error('work notes unavailable');
    void this.watch(roomId).catch((e: unknown) => console.warn('[trace] work notes watch failed', e));
    return room;
  }
}

function eventOf(item: TimelineItem): RoomEvent | undefined {
  const event = item.asEvent();
  if (!event || !EventOrTransactionId.EventId.instanceOf(event.eventOrTransactionId)) return undefined;
  const redacted =
    TimelineItemContent.MsgLike.instanceOf(event.content) && MsgLikeKind.Redacted.instanceOf(event.content.inner.content.kind);
  let raw: { type?: unknown; content?: unknown } | undefined;
  try {
    const json = event.lazyProvider.debugInfo().originalJson;
    raw = json ? (JSON.parse(json) as { type?: unknown; content?: unknown }) : undefined;
  } catch {
    raw = undefined;
  }
  if (!raw || typeof raw.type !== 'string') return undefined;
  return {
    eventId: event.eventOrTransactionId.inner.eventId,
    at: Number(event.timestamp),
    sender: event.sender,
    type: raw.type,
    redacted,
    content: raw.content,
  };
}

const rooms = new WeakMap<Client, WorkNotesRoom>();

/** The notes room of one account, made once per SDK client. */
export function workNotesFor(client: Client): WorkNotesRoom {
  let room = rooms.get(client);
  if (!room) {
    room = new WorkNotesRoom(client);
    rooms.set(client, room);
  }
  return room;
}

/** The notes room state, followed while on is true (the queue shows). */
function useWorkNotesState(client: Client, on: boolean): State {
  const notes = workNotesFor(client);
  useEffect(() => {
    if (on) void notes.start();
  }, [notes, on]);
  return useSyncExternalStore(notes.subscribe, notes.snapshot);
}

/** The ID of the notes room once known, so the chat list leaves it out. */
export function useWorkNotesRoomId(client: Client, on: boolean): string | undefined {
  return useWorkNotesState(client, on).roomId;
}

/** Spec work-notes notes: the notes of one customer chat, oldest first (workNoteFromEvent). */
export function useChatNotes(client: Client, chatRoomId: string, seatIds: string[], on: boolean): WorkNote[] {
  const state = useWorkNotesState(client, on);
  const ctx = { ownUserId: client.userId(), joinedRoomIds: [chatRoomId], seatIds };
  const notes: WorkNote[] = [];
  for (const event of state.events) {
    const result = workNoteFromEvent(event, ctx, reg);
    if ('note' in result && result.note.roomId === chatRoomId)
      notes.push({ eventId: event.eventId, at: event.at, body: result.note.body, seatId: result.note.seatId });
  }
  return notes;
}

/** Spec work-chat-bar notes: customer event ID to seat ID, from valid reply-by events of this chat (replyByFromEvent). */
export function useReplyBy(client: Client, chatRoomId: string, seatIds: string[], on: boolean): Map<string, string> {
  const state = useWorkNotesState(client, on);
  const ctx = { ownUserId: client.userId(), seatIds };
  const found = new Map<string, string>();
  if (!on) return found;
  for (const event of state.events) {
    const result = replyByFromEvent(event, ctx, reg);
    if ('replyBy' in result && result.replyBy.roomId === chatRoomId) found.set(result.replyBy.eventId, result.replyBy.seatId);
  }
  return found;
}

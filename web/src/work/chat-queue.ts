// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Who has each customer chat of a work account (bipe ADR 0284 points 15 to 17; specs work-chats, work-chat-bar). The
// record lives in bipe-api: GET /v1/me/business/chats and the seat list GET /v1/me/business/seats, read when Conversas
// opens and when the page comes to the front. The queue is on only with two or more active people and a seat for this
// browser; with one person nothing changes. A failed read keeps what the page had, and off is the first state.

import { useEffect, useSyncExternalStore } from 'react';
import type { ChatAssignment, ChatAssignmentStatus, Seat } from '../bipe/generated/business-api';
import type { ClientLike as Client } from '../generated/matrix_sdk_ffi';
import { s } from '../ui/i18n';
import { businessClientFor, WorkError, type BusinessClient } from './business-client';

export type ChatQueueState = {
  /** Two or more active seats, and this device belongs to one of them (SeatList.me). */
  on: boolean;
  /** Seat ID of this device. */
  mySeat?: string;
  /** The active seats, in the order of the list: the people a chat can go to. */
  people: Seat[];
  /** Seat ID to name, for every seat of the list. */
  names: Map<string, string>;
  /** Room ID to record. A customer chat with no record is open and in the pool. */
  records: Map<string, ChatAssignment>;
};

const OFF: ChatQueueState = { on: false, people: [], names: new Map(), records: new Map() };

/** Spec work-chats filter. */
export type QueueFilter = 'mine' | 'pool' | 'all';

export class ChatQueue {
  private state: ChatQueueState = OFF;
  private listeners = new Set<() => void>();
  private loading?: Promise<void>;

  constructor(private readonly api: BusinessClient) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  snapshot = () => this.state;

  private set(next: ChatQueueState) {
    this.state = next;
    for (const listener of this.listeners) listener();
  }

  /** Action load of spec work-chats. Calls that overlap share one read. */
  refresh(): Promise<void> {
    if (this.loading) return this.loading;
    this.loading = (async () => {
      try {
        const list = await this.api.seats();
        const people = list.seats.filter((seat) => seat.status === 'active');
        const names = new Map(list.seats.map((seat) => [seat.id, seat.name]));
        const mySeat = list.me.seat_id;
        if (people.length < 2 || !mySeat) {
          this.set({ ...OFF, people, names, mySeat });
          return;
        }
        const chats = await this.api.chats();
        this.set({ on: true, mySeat, people, names, records: new Map(chats.chats.map((c) => [c.room_id, c])) });
      } catch (e) {
        console.warn('[trace] chat queue read failed', e);
      } finally {
        this.loading = undefined;
      }
    })();
    return this.loading;
  }

  /**
   * PUT /v1/me/business/chats/{roomId} with the assignee this page last saw as expected_assignee_seat_id. 409 chat_taken
   * reads the chats again and throws, so the bar shows who has it now.
   */
  async update(roomId: string, assignee: string | null, status?: ChatAssignmentStatus): Promise<void> {
    const expected = this.state.records.get(roomId)?.assignee_seat_id ?? null;
    try {
      const record = await this.api.setChat(roomId, {
        assignee_seat_id: assignee,
        expected_assignee_seat_id: expected,
        ...(status ? { status } : {}),
      });
      const records = new Map(this.state.records);
      records.set(roomId, record);
      this.set({ ...this.state, records });
    } catch (e) {
      if (e instanceof WorkError && e.code === 'chat_taken') await this.refresh();
      throw e;
    }
  }
}

const queues = new WeakMap<Client, ChatQueue>();

/** The queue of one account, made once per SDK client. */
export function chatQueueFor(client: Client): ChatQueue {
  let queue = queues.get(client);
  if (!queue) {
    queue = new ChatQueue(businessClientFor(client));
    queues.set(client, queue);
  }
  return queue;
}

/** The queue state; isCompany false gives off and reads nothing. Reads at start and when the page comes to the front. */
export function useChatQueue(client: Client, isCompany: boolean): ChatQueueState {
  const queue = chatQueueFor(client);
  useEffect(() => {
    if (!isCompany) return;
    void queue.refresh();
    const onFront = () => {
      if (!document.hidden) void queue.refresh();
    };
    document.addEventListener('visibilitychange', onFront);
    return () => document.removeEventListener('visibilitychange', onFront);
  }, [queue, isCompany]);
  const state = useSyncExternalStore(queue.subscribe, queue.snapshot);
  return isCompany ? state : OFF;
}

/** Seat ID of the assignee of a chat, or undefined for the pool. */
export function assigneeOf(state: ChatQueueState, roomId: string): string | undefined {
  return state.records.get(roomId)?.assignee_seat_id ?? undefined;
}

/** ChatAssignmentStatus, open when there is no record. */
export function statusOf(state: ChatQueueState, roomId: string): ChatAssignmentStatus {
  return state.records.get(roomId)?.status ?? 'open';
}

/** Spec work-chats filter, on the device. Only customer chats have records, so mine and pool take only those. */
export function inFilter(state: ChatQueueState, filter: QueueFilter, roomId: string, isCustomerChat: boolean): boolean {
  if (!state.on || filter === 'all') return true;
  if (!isCustomerChat) return false;
  const assignee = assigneeOf(state, roomId);
  return filter === 'mine' ? assignee === state.mySeat : assignee === undefined;
}

/** The row line: bipe_chat_assignee_me, bipe_chat_assignee_other with the seat name, or nothing for the pool. */
export function assigneeLine(state: ChatQueueState, roomId: string): string | undefined {
  if (!state.on) return undefined;
  const assignee = assigneeOf(state, roomId);
  if (assignee === undefined) return undefined;
  if (assignee === state.mySeat) return s('bipe_chat_assignee_me');
  const name = state.names.get(assignee);
  return name ? s('bipe_chat_assignee_other', name) : undefined;
}

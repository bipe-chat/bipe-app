// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Interno (bipe ADR 0284 phase B, spec interno): chats between the people of a work account, on their member
// identities. The member identity is a second Matrix user of this person, signed in as a second session under the
// work account slot (sdk.ts MemberSlot). It never shows in the switcher.
//
// - open: on the first open in this browser, POST /v1/me/business/members/session gives a user and a one-time
//   password; sdk.ts signInMember signs in with m.login.password and the password is gone after that call. Later
//   loads restore the saved session, like the other sessions.
// - ensureGeral: with two or more members and no Geral, this app makes Geral (private, invite only, encrypted),
//   invites every member, then claims it with PUT .../members/geral. 409 geral_exists: it leaves its own room and
//   joins the claimed one. With Geral, it invites each member that is not in it yet.
// - A member identity that bipe-api removed (403 seat_required, or `me` is no longer this session's user), or a
//   session the homeserver refuses (deactivated, unknown token), is dropped here without an error screen.
// - The homeserver lets only members of the same account invite a member identity (ADR 0284 point 8), so the
//   member session joins its invites by itself. Default.

import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { WorkMembers } from '../bipe/generated/business-api';
import type { ChatSummary } from '../chat-model';
import {
  Membership,
  MembershipState,
  RoomListEntriesDynamicFilterKind,
  RoomListEntriesUpdate,
  RoomPreset,
  RoomVisibility,
  type ClientLike as Client,
  type RoomLike as Room,
  type RoomListEntriesWithDynamicAdaptersResultLike as RoomListEntriesWithDynamicAdaptersResult,
  type RoomMember,
  type SyncServiceLike as SyncService,
} from '../generated/matrix_sdk_ffi';
import { roomWhenSynced } from '../NewChat';
import { forgetMember, restoreMember, signInMember, type MemberSlot } from '../sdk';
import { s } from '../ui/i18n';
import { applyDiffs, describe, type DiffVariants } from '../util';
import { businessClientFor, WorkError, type BusinessClient } from './business-client';

export type InternoPhase = 'idle' | 'restoring' | 'setup' | 'ready' | 'failed';

export type InternoState = {
  /** idle: no member session in this browser yet. setup: the first sign-in. failed: shows bipe_interno_error. */
  phase: InternoPhase;
  /** The member session's client once ready. */
  member?: Client;
  /** The last GET /v1/me/business/members. */
  members?: WorkMembers;
};

const IDLE: InternoState = { phase: 'idle' };
const NO_ROOMS: Room[] = [];

/** A homeserver answer that means the member session is over: the identity is deactivated or the token is gone. */
function sessionGone(e: unknown): boolean {
  return /M_UNKNOWN_TOKEN|M_USER_DEACTIVATED|soft.?logout/i.test(describe(e));
}

/** A bipe-api answer that means this device has no person, or the person left Quem atende. */
function seatGone(e: unknown): boolean {
  return e instanceof WorkError && (e.code === 'seat_required' || e.status === 403);
}

const geralPendingKey = (memberUserId: string) => `bipe.interno.geral.pending.${memberUserId}`;

function readPending(key: string): string | undefined {
  try {
    return localStorage.getItem(key) ?? undefined;
  } catch {
    return undefined;
  }
}

function writePending(key: string, value: string | undefined) {
  try {
    if (value === undefined) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    // Without storage a failed claim can make one more room; the next claim still settles Geral.
  }
}

async function joinedMembers(room: Room): Promise<Set<string>> {
  const iterator = await room.members();
  const all: RoomMember[] = [];
  for (let chunk = iterator.nextChunk(200); chunk; chunk = iterator.nextChunk(200)) all.push(...chunk);
  return new Set(
    all
      .filter((m) => MembershipState.Join.instanceOf(m.membership) || MembershipState.Invite.instanceOf(m.membership))
      .map((m) => m.userId),
  );
}

export class Interno {
  private state: InternoState = IDLE;
  private listeners = new Set<() => void>();
  private slot?: MemberSlot;
  private refreshing?: Promise<void>;
  /** Writes the member slot into the work account slot (AccountsApi.remember); set by the hook on each render. */
  save: (member: MemberSlot | undefined) => void = () => undefined;

  constructor(private readonly api: BusinessClient) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  snapshot = () => this.state;

  private set(next: InternoState) {
    this.state = next;
    for (const listener of this.listeners) listener();
  }

  /** Restores the saved member session of the work account slot once. */
  async attach(saved: MemberSlot | undefined): Promise<void> {
    if (!saved || this.slot?.storeName === saved.storeName || this.state.phase === 'setup') return;
    this.slot = saved;
    this.set({ phase: 'restoring' });
    try {
      const member = await restoreMember(saved);
      if (this.slot !== saved) return;
      if (!member) return await this.drop();
      this.set({ phase: 'ready', member });
      await this.refresh();
    } catch (e) {
      console.warn('[trace] interno restore failed', describe(e));
      if (this.slot === saved) await this.drop();
    }
  }

  /** Spec interno open: the first sign-in, or a refresh of the members and Geral. */
  open(): Promise<void> {
    if (this.state.phase === 'ready') return this.refresh();
    if (this.state.phase === 'idle' || this.state.phase === 'failed') return this.setup();
    return Promise.resolve();
  }

  private async setup(): Promise<void> {
    this.set({ phase: 'setup' });
    try {
      const { member, client } = await (async () => {
        // The password stays inside this block: it goes to the sign-in and nowhere else.
        const answer = await this.api.memberSession();
        return signInMember(answer.user_id, answer.password);
      })();
      this.slot = member;
      this.save(member);
      this.set({ phase: 'ready', member: client });
      await this.refresh();
    } catch (e) {
      console.warn('[trace] interno setup failed', describe(e));
      this.slot = undefined;
      this.set({ phase: 'failed' });
    }
  }

  /** Reads the members, drops a removed identity, and keeps Geral in step. Calls that overlap share one run. */
  refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing;
    this.refreshing = (async () => {
      const member = this.state.member;
      if (this.state.phase !== 'ready' || !member) return;
      try {
        const members = await this.api.members();
        if (members.me !== member.userId()) return await this.drop();
        if (this.state.member !== member) return;
        this.set({ ...this.state, members });
        await this.ensureGeral(member, members);
      } catch (e) {
        if (seatGone(e) || sessionGone(e)) return await this.drop();
        console.warn('[trace] interno refresh failed', describe(e));
      } finally {
        this.refreshing = undefined;
      }
    })();
    return this.refreshing;
  }

  /** The member session failed on the homeserver (its sync or a call): an ended session is dropped. */
  failed(e: unknown) {
    if (sessionGone(e)) void this.drop();
    else console.warn('[trace] interno session error', describe(e));
  }

  /** Drops the member session in this browser, with no error screen. The next open signs in again. */
  private async drop(): Promise<void> {
    const slot = this.slot;
    this.slot = undefined;
    this.set(IDLE);
    this.save(undefined);
    if (slot) await forgetMember(slot).catch((e: unknown) => console.warn('[trace] interno forget failed', e));
  }

  /** Spec interno ensureGeral. */
  private async ensureGeral(client: Client, members: WorkMembers): Promise<void> {
    if (members.members.length < 2 || !members.me) return;
    const me = members.me;
    const others = members.members.map((m) => m.user_id).filter((id) => id !== me);
    if (members.geral_room_id) return this.keepGeral(client, members.geral_room_id, others);
    const pendingKey = geralPendingKey(me);
    let roomId = readPending(pendingKey);
    if (!roomId) {
      roomId = await client.createRoom({
        name: s('bipe_interno_geral'),
        topic: undefined,
        isEncrypted: true,
        isDirect: false,
        visibility: new RoomVisibility.Private(),
        preset: RoomPreset.PrivateChat,
        invite: undefined,
        avatar: undefined,
        powerLevelContentOverride: undefined,
        joinRuleOverride: undefined,
        historyVisibilityOverride: undefined,
        canonicalAlias: undefined,
        isSpace: false,
      });
      // A failed claim finishes with this room next time instead of making another.
      writePending(pendingKey, roomId);
      const room = await roomWhenSynced(client, roomId);
      for (const id of others) {
        // One refused invite does not stop Geral; the next refresh invites again.
        await room?.inviteUserById(id).catch((e: unknown) => console.warn('[trace] geral invite failed', e));
      }
    }
    try {
      const claimed = await this.api.claimGeral({ room_id: roomId });
      writePending(pendingKey, undefined);
      if (this.state.member === client) this.set({ ...this.state, members: claimed });
    } catch (e) {
      if (!(e instanceof WorkError && e.code === 'geral_exists')) throw e;
      writePending(pendingKey, undefined);
      // Another member's app claimed Geral first: leave this room, join that one.
      const own = await roomWhenSynced(client, roomId);
      await own?.leave().catch((err: unknown) => console.warn('[trace] geral leave failed', err));
      const fresh = await this.api.members();
      if (this.state.member === client) this.set({ ...this.state, members: fresh });
      if (fresh.geral_room_id) await this.keepGeral(client, fresh.geral_room_id, others);
    }
  }

  /** Joins Geral when invited, then invites each member that is not in it yet. */
  private async keepGeral(client: Client, roomId: string, others: string[]): Promise<void> {
    const room = await roomWhenSynced(client, roomId);
    // Not invited yet: a member's app in Geral invites this person on its next refresh.
    if (!room) return;
    let membership = (await room.roomInfo()).membership;
    if (membership === Membership.Invited) {
      await room.join();
      membership = Membership.Joined;
    }
    if (membership !== Membership.Joined) return;
    const present = await joinedMembers(room);
    for (const id of others) {
      if (!present.has(id))
        await room.inviteUserById(id).catch((e: unknown) => console.warn('[trace] geral invite failed', e));
    }
  }
}

const internos = new WeakMap<Client, Interno>();

/** The Interno of one work account, made once per SDK client of that account. */
export function internoFor(client: Client): Interno {
  let interno = internos.get(client);
  if (!interno) {
    interno = new Interno(businessClientFor(client));
    internos.set(client, interno);
  }
  return interno;
}

/**
 * The Interno state of a work account. A saved member session is restored when the chat list opens, so Interno
 * rings and Geral invites new people; refreshes when the page comes to the front. isCompany false gives idle.
 */
export function useInterno(
  client: Client,
  isCompany: boolean,
  saved: MemberSlot | undefined,
  save: (member: MemberSlot | undefined) => void,
): { state: InternoState; interno: Interno } {
  const interno = internoFor(client);
  interno.save = save;
  useEffect(() => {
    if (isCompany) void interno.attach(saved);
  }, [interno, isCompany, saved]);
  useEffect(() => {
    if (!isCompany) return;
    const onFront = () => {
      if (!document.hidden) void interno.refresh();
    };
    document.addEventListener('visibilitychange', onFront);
    return () => document.removeEventListener('visibilitychange', onFront);
  }, [interno, isCompany]);
  const state = useSyncExternalStore(interno.subscribe, interno.snapshot);
  return { state: isCompany ? state : IDLE, interno };
}

/**
 * The rooms of the member session: its own sync and room list (joined rooms and invites), and the room subscription
 * that gives each row its latest event, as in Chats. An error of the sync goes to onError.
 */
export function useMemberRooms(member: Client | undefined, onError: (e: unknown) => void): Room[] {
  const [rooms, setRooms] = useState<Room[]>(NO_ROOMS);
  const errorRef = useRef(onError);
  errorRef.current = onError;
  const keep = useRef<{ sync?: SyncService; entries?: RoomListEntriesWithDynamicAdaptersResult }>({});
  const subscribed = useRef('');
  useEffect(() => {
    // A new member client starts with an empty list.
    setRooms(NO_ROOMS);
    subscribed.current = '';
    if (!member) return;
    let stopped = false;
    (async () => {
      const sync = await member.syncService().finish();
      keep.current.sync = sync;
      await sync.start();
      const list = await sync.roomListService().allRooms();
      const entries = list.entriesWithDynamicAdapters(100, {
        onUpdate(updates) {
          if (!stopped) setRooms((prev) => applyDiffs(prev, updates, RoomListEntriesUpdate as unknown as DiffVariants));
        },
      });
      keep.current.entries = entries;
      entries.controller().setFilter(new RoomListEntriesDynamicFilterKind.NonLeft());
    })().catch((e: unknown) => !stopped && errorRef.current(e));
    return () => {
      stopped = true;
      const sync = keep.current.sync;
      keep.current = {};
      void sync?.stop();
    };
  }, [member]);
  useEffect(() => {
    const sync = keep.current.sync;
    const ids = rooms.map((r) => r.id()).sort();
    const key = ids.join('\n');
    if (!sync || !ids.length || key === subscribed.current) return;
    subscribed.current = key;
    sync
      .roomListService()
      .subscribeToRooms(ids)
      .catch((e: unknown) => {
        if (subscribed.current === key) subscribed.current = '';
        console.warn('[trace] interno room subscription failed', e);
      });
  }, [rooms]);
  return member ? rooms : NO_ROOMS;
}


/** Joins the invites of the member session: only members of the same account can send them (ADR 0284 point 8). */
export function useAutoJoin(chats: ChatSummary[], onError: (e: unknown) => void) {
  const tried = useRef(new Set<string>());
  const errorRef = useRef(onError);
  errorRef.current = onError;
  useEffect(() => {
    for (const chat of chats) {
      if (chat.kind !== 'request' && chat.kind !== 'groupInvite') continue;
      if (tried.current.has(chat.roomId)) continue;
      tried.current.add(chat.roomId);
      chat.room.join().catch((e: unknown) => errorRef.current(e));
    }
  }, [chats]);
}

/** Spec interno rooms: Geral first, then direct chats newest first. Other rooms of the member session do not show. */
export function internoRooms(
  chats: ChatSummary[],
  geralRoomId: string | null | undefined,
): { geral?: ChatSummary; direct: ChatSummary[] } {
  const geral = geralRoomId ? chats.find((c) => c.roomId === geralRoomId && c.kind === 'group') : undefined;
  const direct = chats.filter((c) => c.kind === 'dm').sort((a, b) => (b.row.at ?? 0) - (a.row.at ?? 0));
  return { geral, direct };
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Bipe's self-chat protocol (ADR 0053, NotesRoom.swift, NotesRoom.kt): an encrypted room with only its owner,
// saved in global account data. The wire key and content are the apps'.

import { useEffect, useState } from 'react';
import {
  Membership,
  RoomHistoryVisibility,
  RoomNotificationMode,
  RoomPreset,
  RoomVisibility,
  type ClientLike as Client,
  type RoomLike as Room,
} from './generated/matrix_sdk_ffi';
import { roomWhenSynced } from './NewChat';
import { s } from './ui/i18n';

const EVENT_TYPE = 'chat.bipe.self_chat';

/**
 * A private room with only the account's own user, saved in global account data as {"room_id": ...}: the self-chat here,
 * and the notes room of a work account (work/work-notes.ts). pendingKey keeps a created room until account data has it.
 */
export type OwnRoom = {
  accountDataType: string;
  pendingKey: (userId: string) => string;
  name: () => string;
  /** The drafts room (bipe-shared docs/drafts-room.md "The room"): history from the join, and muted. */
  joinedHistoryMuted?: boolean;
};

const SELF_CHAT: OwnRoom = {
  accountDataType: EVENT_TYPE,
  pendingKey: (userId) => `bipe.next.notes.pending.${userId}`,
  name: () => s('bipe_self_chat_title'),
};

/** The room in account data. undefined: no room yet. A failed or malformed read throws. */
export async function readOwnRoomId(client: Client, accountDataType: string): Promise<string | undefined> {
  const json = await client.accountData(accountDataType);
  if (!json) return undefined;
  const id = (JSON.parse(json) as { room_id?: unknown }).room_id;
  if (typeof id !== 'string' || !id.startsWith('!') || /\s/.test(id)) throw new Error(`bad ${accountDataType}`);
  return id;
}

const readNotesId = (client: Client) => readOwnRoomId(client, EVENT_TYPE);

/** The notes room, once read. A new `version` reads again; a failed read keeps what it has. */
export function useNotesRoomId(client: Client, version: unknown): string | undefined {
  const [id, setId] = useState<string>();
  useEffect(() => {
    let stopped = false;
    readNotesId(client)
      .then((found) => !stopped && found && setId(found))
      .catch((e: unknown) => console.warn('[trace] notes read failed', e));
    return () => {
      stopped = true;
    };
  }, [client, version]);
  return id;
}

/** Spec group-details notes, Notes: is this room the notes? undefined until read; a failed read says no. */
export function useIsNotes(client: Client, room: Room): boolean | undefined {
  const [isNotes, setIsNotes] = useState<boolean>();
  useEffect(() => {
    let stopped = false;
    setIsNotes(undefined);
    readNotesId(client)
      .then((id) => id === room.id())
      .catch(() => false)
      .then((found) => !stopped && setIsNotes(found));
    return () => {
      stopped = true;
    };
  }, [client, room]);
  return isNotes;
}

/** Spec voce openNotes: the room, created on first use. A failed read never means "no notes yet". */
export function openNotes(client: Client): Promise<string> {
  return openOwnRoom(client, SELF_CHAT);
}

/** The own room, created on first use (encrypted, private, no invites). A failed read never means "no room yet". */
export async function openOwnRoom(client: Client, own: OwnRoom): Promise<string> {
  const pendingKey = own.pendingKey(client.userId());
  const known = await readOwnRoomId(client, own.accountDataType);
  const pending = localStorage.getItem(pendingKey) ?? undefined;
  if (known && known !== pending) {
    // A room missing from the local store may still be syncing. Keep its identity; never replace it.
    const room = await roomWhenSynced(client, known);
    if (!room || (await room.roomInfo()).membership !== Membership.Joined) throw new Error('own room unavailable');
    return known;
  }
  let id = pending;
  if (!id) {
    id = await client.createRoom({
      name: own.name(),
      topic: undefined,
      isEncrypted: true,
      isDirect: false,
      visibility: new RoomVisibility.Private(),
      preset: RoomPreset.PrivateChat,
      invite: undefined,
      avatar: undefined,
      powerLevelContentOverride: undefined,
      joinRuleOverride: undefined,
      historyVisibilityOverride: own.joinedHistoryMuted ? new RoomHistoryVisibility.Joined() : undefined,
      canonicalAlias: undefined,
      isSpace: false,
    });
    // If writing account data fails, the next attempt finishes that write instead of creating another room.
    localStorage.setItem(pendingKey, id);
  }
  await client.setAccountData(own.accountDataType, JSON.stringify({ room_id: id }));
  localStorage.removeItem(pendingKey);
  await roomWhenSynced(client, id);
  if (own.joinedHistoryMuted) {
    const settings = await client.getNotificationSettings();
    await settings.setRoomNotificationMode(id, RoomNotificationMode.Mute).catch(() => undefined);
  }
  return id;
}

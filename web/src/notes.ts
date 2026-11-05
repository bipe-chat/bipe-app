// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Bipe's self-chat protocol (ADR 0053, NotesRoom.swift, NotesRoom.kt): an encrypted room with only its owner,
// saved in global account data. The wire key and content are the apps'.

import { useEffect, useState } from 'react';
import {
  Membership,
  RoomPreset,
  RoomVisibility,
  type ClientLike as Client,
  type RoomLike as Room,
} from './generated/matrix_sdk_ffi';
import { roomWhenSynced } from './NewChat';
import { s } from './ui/i18n';

const EVENT_TYPE = 'chat.bipe.self_chat';

function pendingKey(client: Client) {
  return `bipe.next.notes.pending.${client.userId()}`;
}

/** The room in account data. undefined: no notes yet. A failed or malformed read throws. */
async function readNotesId(client: Client): Promise<string | undefined> {
  const json = await client.accountData(EVENT_TYPE);
  if (!json) return undefined;
  const id = (JSON.parse(json) as { room_id?: unknown }).room_id;
  if (typeof id !== 'string' || !id.startsWith('!') || /\s/.test(id)) throw new Error('bad self chat');
  return id;
}

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
export async function openNotes(client: Client): Promise<string> {
  const known = await readNotesId(client);
  const pending = localStorage.getItem(pendingKey(client)) ?? undefined;
  if (known && known !== pending) {
    // A room missing from the local store may still be syncing. Keep its identity; never replace it.
    const room = await roomWhenSynced(client, known);
    if (!room || (await room.roomInfo()).membership !== Membership.Joined) throw new Error('notes unavailable');
    return known;
  }
  let id = pending;
  if (!id) {
    id = await client.createRoom({
      name: s('bipe_self_chat_title'),
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
    // If writing account data fails, the next attempt finishes that write instead of creating another room.
    localStorage.setItem(pendingKey(client), id);
  }
  await client.setAccountData(EVENT_TYPE, JSON.stringify({ room_id: id }));
  localStorage.removeItem(pendingKey(client));
  await roomWhenSynced(client, id);
  return id;
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The chat actions of the apps (specs chat-row-actions, chat-notifications, dm-delete, block-person,
// report-chat, group-leave) as SDK calls, and the account state they read (cleared DMs, the ignore list).

import { useEffect, useState } from 'react';
import { clearedChatMarker, clearedChatsWithClear } from './core';
import { clearedChats } from './bipe/generated/registries.json';
import {
  ReceiptType,
  RoomNotificationMode,
  type ClientLike as Client,
  type RoomLike as Room,
} from './generated/matrix_sdk_ffi';

export type NotifyMode = 'quiet' | 'all' | 'mute' | 'mentionsLegacy';

const toSdk: Record<Exclude<NotifyMode, 'mentionsLegacy'>, RoomNotificationMode> = {
  quiet: RoomNotificationMode.MentionsAndKeywordsOnly,
  all: RoomNotificationMode.AllMessages,
  mute: RoomNotificationMode.Mute,
};

/** Spec chat-notifications appear: the room's mode; a DM with mentions-only from before is mentionsLegacy. */
export async function notifyMode(client: Client, room: Room, isDm: boolean): Promise<NotifyMode> {
  const settings = await client.getNotificationSettings();
  const encrypted = await room.isEncrypted();
  const { mode: roomMode, isDefault } = await settings.getRoomNotificationSettings(room.id(), encrypted, isDm);
  // Spec chat-notifications notes (ADR 0254): the plain rule is the default of a group.
  const mode = isDefault ? await settings.getDefaultRoomNotificationMode(encrypted && isDm, isDm) : roomMode;
  if (mode === RoomNotificationMode.Mute) return 'mute';
  if (mode === RoomNotificationMode.AllMessages) return 'all';
  return isDm ? 'mentionsLegacy' : 'quiet';
}

/** Spec chat-notifications choose: the account default restores it; from mute, unmute first. */
export async function setNotifyMode(
  client: Client,
  room: Room,
  isDm: boolean,
  from: NotifyMode,
  to: Exclude<NotifyMode, 'mentionsLegacy'>,
) {
  const settings = await client.getNotificationSettings();
  const encrypted = await room.isEncrypted();
  if (from === 'mute' && to !== 'mute') await settings.unmuteRoom(room.id(), encrypted, isDm);
  const fallback = await settings.getDefaultRoomNotificationMode(encrypted && isDm, isDm);
  // A quiet rule on an encrypted group would stop its pushes, so quiet there always restores the default (ADR 0254).
  if (toSdk[to] === fallback || (to === 'quiet' && encrypted && !isDm)) await settings.restoreDefaultRoomNotificationMode(room.id());
  else await settings.setRoomNotificationMode(room.id(), toSdk[to]);
}

/** Spec chat-row-actions markRead: a public read receipt to the latest event, then clear the manual flag. */
export async function markRead(room: Room) {
  await room.markAsRead(ReceiptType.Read);
  await room.setUnreadFlag(false);
}

/** markUnread: the manual unread flag; the read receipt never moves back. */
export const markUnread = (room: Room) => room.setUnreadFlag(true);

function parse(json: string | undefined): unknown {
  try {
    return json ? JSON.parse(json) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Spec dm-delete: adds the DM with the time of its latest event to chat.bipe.cleared_chats by the shared
 * rule clearedChatsWithClear, keeping every other entry, and writes it back. The user stays in the room.
 */
export async function deleteDm(client: Client, room: Room, latestTs: number) {
  const current = parse(await client.accountData(clearedChats.eventType));
  const next = clearedChatsWithClear(current, room.id(), Math.max(0, Math.round(latestTs)));
  await client.setAccountData(clearedChats.eventType, JSON.stringify(next));
}

/** The cleared-chats account data, read at start and again when the page comes back to the front. */
export function useClearedChats(client: Client, version = 0): (roomId: string) => number | null {
  const [content, setContent] = useState<unknown>();
  useEffect(() => {
    let stopped = false;
    const read = () =>
      client
        .accountData(clearedChats.eventType)
        .then((json) => !stopped && setContent(parse(json)))
        .catch(() => undefined);
    void read();
    const onVisible = () => document.visibilityState === 'visible' && void read();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      stopped = true;
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [client, version]);
  return (roomId) => clearedChatMarker(content, roomId) as number | null;
}

/** The Matrix ignore list (block-person), live. */
export function useIgnoredUsers(client: Client): Set<string> {
  const [ignored, setIgnored] = useState<Set<string>>(new Set());
  useEffect(() => {
    let stopped = false;
    client
      .ignoredUsers()
      .then((list) => !stopped && setIgnored(new Set(list)))
      .catch(() => undefined);
    const handle = client.subscribeToIgnoredUsers({ call: (list) => !stopped && setIgnored(new Set(list)) });
    return () => {
      stopped = true;
      handle.cancel();
    };
  }, [client]);
  return ignored;
}

/** Spec report-chat send: the report, then in order block, delete, leave. A failed follow-up keeps the report. */
export async function reportChat(
  client: Client,
  room: Room,
  opts: { reason: string; block?: string; deleteAt?: number; leave?: boolean },
): Promise<{ leaveFailed: boolean }> {
  await room.reportRoom(opts.reason.trim());
  if (opts.block) await client.ignoreUser(opts.block).catch(() => undefined);
  if (opts.deleteAt !== undefined) await deleteDm(client, room, opts.deleteAt).catch(() => undefined);
  if (opts.leave) {
    try {
      await room.leave();
    } catch {
      return { leaveFailed: true };
    }
  }
  return { leaveFailed: false };
}

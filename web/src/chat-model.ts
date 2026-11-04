// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// One summary per room of the SDK room list (ChatSummary on iOS): RoomInfo and the latest event, read
// again each time the room list hands over a new Room for that row (the SDK sends a Set when the room
// changes), and the row (time and text) from those and the room's local timeline (row-activity.ts).
// The order stays the SDK's: newest activity first.

import { useEffect, useRef, useState } from 'react';
import {
  LatestEventValue,
  Membership,
  ProfileDetails,
  type RoomInfo,
  type RoomLike as Room,
  type TimelineItemContent,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import { faceOf, type RoomFace } from './room-face';
import { localEvents, resolveRow, RoomTimelines, type LocalEvent, type Row } from './row-activity';
import { rowPreview } from './row-preview';

export type Latest = {
  at: number;
  isOwn: boolean;
  sender: string;
  senderName?: string;
  content?: TimelineItemContent;
};

export type ChatKind = 'dm' | 'group' | 'request' | 'groupInvite' | 'hidden';

export type ChatSummary = {
  room: Room;
  roomId: string;
  info?: RoomInfo;
  face: RoomFace;
  /** The SDK latest event, for notifications. The list shows `row`. */
  latest?: Latest;
  row: Row;
  /** The events of the local timeline, with no network and no pagination. Mural reads them (mural.ts). */
  local: LocalEvent[];
  kind: ChatKind;
};

function latestOf(value: LatestEventValue | undefined): Latest | undefined {
  if (!value) return undefined;
  if (LatestEventValue.Remote.instanceOf(value) || LatestEventValue.Local.instanceOf(value)) {
    // A Local value is an own message still in the send queue.
    const v = value.inner;
    const senderName = ProfileDetails.Ready.instanceOf(v.profile) ? v.profile.inner.displayName : undefined;
    return {
      at: Number(v.timestamp),
      isOwn: 'isOwn' in v ? v.isOwn : true,
      sender: v.sender,
      senderName,
      content: v.content,
    };
  }
  if (LatestEventValue.RemoteInvite.instanceOf(value)) {
    return { at: Number(value.inner.timestamp), isOwn: false, sender: '' };
  }
  return undefined;
}

/**
 * The tab of a room (specs chat-list-filters and chat-requests). A request is an invite whose invite is
 * direct (RoomInfo.isDirect) with at most two active members, service members not counted: isDm reads
 * m.direct, which the invitee writes only on joining. Other invites are group invites. A joined room
 * is a DM by RoomInfo.isDm (ADR 0141), else a group. Spaces and left rooms do not show.
 */
export function kindOf(info: RoomInfo | undefined): ChatKind {
  if (!info || info.isSpace) return 'hidden';
  if (info.membership === Membership.Invited) {
    const people = Number(info.activeMembersCount) - Number(info.activeServiceMembersCount);
    return info.isDirect && people <= 2 ? 'request' : 'groupInvite';
  }
  if (info.membership !== Membership.Joined) return 'hidden';
  return info.isDm ? 'dm' : 'group';
}

type Entry = { room: Room; info?: RoomInfo; latest?: Latest };

export function useChatSummaries(rooms: Room[], ownUserId: string): ChatSummary[] {
  const loaded = useRef(new Map<string, Entry>());
  const mounted = useRef(true);
  const [, setVersion] = useState(0);
  const [timelines] = useState(() => new RoomTimelines(() => mounted.current && setVersion((v) => v + 1)));
  // The last row of each room, from the inputs it came from: a row changes only when they do.
  const rows = useRef(new Map<string, { entry?: Entry; items?: TimelineItem[]; row: Row; local: LocalEvent[] }>());
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      timelines.dispose();
    };
  }, [timelines]);
  useEffect(() => {
    timelines.watch(rooms);
    for (const room of rooms) {
      const id = room.id();
      const seen = loaded.current.get(id);
      if (seen?.room === room) continue;
      loaded.current.set(id, { room, info: seen?.info, latest: seen?.latest });
      Promise.all([room.roomInfo(), room.latestEvent().catch(() => undefined)])
        .then(([info, latest]) => {
          // A newer Room for this row may have come in meanwhile; its own read wins.
          if (!mounted.current || loaded.current.get(id)?.room !== room) return;
          loaded.current.set(id, { room, info, latest: latestOf(latest) });
          setVersion((v) => v + 1);
        })
        .catch((e: unknown) => console.warn('[trace] room info failed', id, e));
    }
  }, [rooms, timelines]);
  return rooms.map((room) => {
    const id = room.id();
    const entry = loaded.current.get(id);
    const items = timelines.items(id);
    let last = rows.current.get(id);
    if (!last || last.entry !== entry || last.items !== items) {
      const isDm = entry?.info?.isDm ?? false;
      const latest = entry?.latest;
      const sdk = latest && { at: latest.at, isOwn: latest.isOwn, preview: rowPreview(latest, isDm, ownUserId) };
      const local = localEvents(items ?? [], isDm, ownUserId);
      last = { entry, items, row: resolveRow(sdk, local, last?.row), local };
      rows.current.set(id, last);
    }
    return {
      room,
      roomId: id,
      info: entry?.info,
      face: faceOf(room, entry?.info),
      latest: entry?.latest,
      row: last.row,
      local: last.local,
      kind: kindOf(entry?.info),
    };
  });
}

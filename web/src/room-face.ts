// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The name and photo of a chat. ADR 0141: a DM shows the person's name and photo, never m.room.name
// or m.room.avatar; a group shows its own. The DM test is RoomInfo.isDirect; the person is the one hero.
// A group with no m.room.avatar shows up to 3 heroes, without the own user (spec chat-list notes, Group
// avatar, bipe-chat/bipe#1004).

import { useEffect, useState } from 'react';
import type { RoomHero, RoomInfo, RoomLike as Room } from './generated/matrix_sdk_ffi';

export type RoomFace = {
  name: string;
  avatarUrl?: string;
  isDirect?: boolean;
  peer?: RoomHero;
  /** The people of a group with no photo, for the member cluster of Avatar. */
  members?: RoomHero[];
};

export function faceOf(room: Room, info: RoomInfo | undefined): RoomFace {
  if (!info) return { name: room.displayName() ?? room.id(), avatarUrl: room.avatarUrl() };
  const peer = info.isDirect && info.heroes.length === 1 ? info.heroes[0] : undefined;
  const own = room.ownUserId();
  const members =
    info.isDm || info.avatarUrl ? undefined : info.heroes.filter((h) => h.userId !== own).slice(0, 3);
  return {
    name: peer?.displayName ?? info.displayName ?? room.displayName() ?? room.id(),
    avatarUrl: peer ? peer.avatarUrl : (info.avatarUrl ?? room.avatarUrl()),
    isDirect: info.isDirect,
    peer,
    members: members?.length ? members : undefined,
  };
}

export function useRoomFace(room: Room): RoomFace {
  const [info, setInfo] = useState<RoomInfo>();
  useEffect(() => {
    let stopped = false;
    room
      .roomInfo()
      .then((i) => !stopped && setInfo(i))
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [room]);
  return faceOf(room, info);
}

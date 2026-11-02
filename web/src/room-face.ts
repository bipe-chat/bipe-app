// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The name and photo of a chat. ADR 0141: a DM shows the person's name and photo, never m.room.name
// or m.room.avatar; a group shows its own. The DM test is RoomInfo.isDirect; the person is the one hero.

import { useEffect, useState } from 'react';
import type { RoomHero, RoomLike as Room } from './generated/matrix_sdk_ffi';

export type RoomFace = { name: string; avatarUrl?: string; isDirect?: boolean; peer?: RoomHero };

export function useRoomFace(room: Room): RoomFace {
  const fallback: RoomFace = { name: room.displayName() ?? room.id(), avatarUrl: room.avatarUrl() };
  const [face, setFace] = useState<RoomFace>(fallback);
  useEffect(() => {
    let stopped = false;
    room
      .roomInfo()
      .then((info) => {
        if (stopped) return;
        const peer = info.isDirect && info.heroes.length === 1 ? info.heroes[0] : undefined;
        setFace({
          name: peer?.displayName ?? info.displayName ?? room.displayName() ?? room.id(),
          avatarUrl: peer ? peer.avatarUrl : (info.avatarUrl ?? room.avatarUrl()),
          isDirect: info.isDirect,
          peer,
        });
      })
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [room]);
  return face;
}

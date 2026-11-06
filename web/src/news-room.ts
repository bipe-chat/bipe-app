// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The account's news room (spec chat-list, news room mark), read once per client.

import { useEffect, useState } from 'react';
import { apiFor } from './api';
import type { ClientLike as Client } from './generated/matrix_sdk_ffi';
import { useClient } from './media';

const newsRooms = new WeakMap<Client, Promise<string | undefined>>();

function newsRoomOf(client: Client): Promise<string | undefined> {
  let room = newsRooms.get(client);
  if (!room) {
    room = apiFor(client)
      .newsRoomId()
      .catch(() => undefined);
    newsRooms.set(client, room);
  }
  return room;
}

/** True when the room is the account's news room. False until the API answers, and after a failure. */
export function useIsNewsRoom(roomId: string | undefined): boolean {
  const client = useClient();
  const [news, setNews] = useState(false);
  useEffect(() => {
    let stopped = false;
    void newsRoomOf(client).then((id) => !stopped && setNews(id !== undefined && id === roomId));
    return () => {
      stopped = true;
    };
  }, [client, roomId]);
  return news;
}

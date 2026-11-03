// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The chat side of Trabalho on the web (common.tsx WorkHost), from the account's Matrix client: the DM with a
// customer is the newest joined one, else a new one (NewChat.tsx openOrCreateDm, PeopleService.openOrCreateDM).

import { apiFor } from '../api';
import { messageEventContentFromMarkdown, type ClientLike as Client } from '../generated/matrix_sdk_ffi';
import { openOrCreateDm, roomWhenSynced } from '../NewChat';
import type { WorkHost } from './common';

const hosts = new WeakMap<Client, WorkHost>();

/** One host per client, so the screens keep the same value across renders. onRoom opens a chat by room ID. */
export function workHostFor(client: Client, onRoom: (roomId: string) => void): WorkHost {
  let host = hosts.get(client);
  if (!host) {
    host = makeHost(client, onRoom);
    hosts.set(client, host);
  }
  return host;
}

function makeHost(client: Client, onRoom: (roomId: string) => void): WorkHost {
  return {
    openChat: (userId) => {
      openOrCreateDm(client, userId).then(onRoom, (e: unknown) => console.warn('[trace] work open chat failed', e));
    },
    // AgendaOwnerModel.send on the phone: one text message per DM, from this browser.
    sendText: async (userId, text) => {
      try {
        const room = await roomWhenSynced(client, await openOrCreateDm(client, userId));
        if (!room) return false;
        const timeline = await room.timeline();
        await timeline.send(messageEventContentFromMarkdown(text));
        return true;
      } catch (e) {
        console.warn('[trace] work send failed', e);
        return false;
      }
    },
    ownHandle: async () => {
      const me = client.userId();
      return (await apiFor(client).resolveHandles([me])).get(me);
    },
  };
}

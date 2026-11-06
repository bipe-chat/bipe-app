// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { useIsNewsRoom } from '../news-room';
import { isOfficial } from '../registries';
import { OfficialBadge } from './List';

/** The Bipe badge of a chat (spec chat-list isOfficial): a DM with an official account, or the news room. */
export function ChatOfficialBadge({ roomId, peerId }: { roomId: string | undefined; peerId: string | undefined }) {
  const newsRoom = useIsNewsRoom(roomId);
  return newsRoom || isOfficial(peerId) ? <OfficialBadge /> : null;
}

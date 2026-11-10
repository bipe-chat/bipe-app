// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { useIsNewsRoom } from '../news-room';
import { isOfficial } from '../registries';
import { SealMark } from '../seals';
import { OfficialBadge } from './List';

/**
 * The Bipe badge of a chat (spec chat-list isOfficial): a DM with an official account, or the news room. Else the
 * seal of the DM's person or company as a mark that does not open a sheet (person-profile notes, bipe-chat/bipe#1457).
 */
export function ChatOfficialBadge({ roomId, peerId }: { roomId: string | undefined; peerId: string | undefined }) {
  const newsRoom = useIsNewsRoom(roomId);
  if (newsRoom || isOfficial(peerId)) return <OfficialBadge />;
  return peerId ? <SealMark userId={peerId} size={16} /> : null;
}

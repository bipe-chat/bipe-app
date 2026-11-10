// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// What the sender line of a group row shows after the name. Spec timeline-rows nameAmbiguous (bipe-chat/bipe#825):
// the @ when another member shows the same name. Spec chat-timeline senderRole (bipe-chat/bipe#733): admin or moderator.
// Ports: MemberNames (iOS, Android). A name or role that changes while the chat is open shows after the chat opens again.
// Provenance: matrix-sdk-ffi Room.members, RoomMember.suggestedRoleForPowerLevel; bipe-api /v1/handles/resolve.

import { useEffect, useMemo, useState } from 'react';
import { apiFor } from './api';
import { normalize } from './core';
import { homoglyphs } from './bipe/generated/registries.json';
import { MembershipState, RoomMemberRole, type RoomLike as Room, type RoomMember } from './generated';
import { useClient } from './media';

export type SenderRole = 'admin' | 'moderator';
export const ROLE_BADGE = { admin: 'bipe_app_role_badge_admin', moderator: 'bipe_app_role_badge_moderator' } as const;

export type SenderFacts = {
  roles: ReadonlyMap<string, SenderRole>;
  /** The user IDs whose name, after normalize (security rule 8), another member also has. */
  lookalikes: ReadonlySet<string>;
  handles: ReadonlyMap<string, string>;
};

const noFacts: SenderFacts = { roles: new Map(), lookalikes: new Set(), handles: new Map() };

function roleOf(member: RoomMember): SenderRole | undefined {
  const role = member.suggestedRoleForPowerLevel;
  if (role === RoomMemberRole.Administrator || role === RoomMemberRole.Creator) return 'admin';
  return role === RoomMemberRole.Moderator ? 'moderator' : undefined;
}

function lookalikesOf(members: RoomMember[]): Set<string> {
  const byName = new Map<string, string[]>();
  for (const m of members) {
    const key = m.displayName ? normalize(m.displayName, homoglyphs.map) : '';
    if (key) byName.set(key, [...(byName.get(key) ?? []), m.userId]);
  }
  return new Set([...byName.values()].filter((ids) => ids.length > 1).flat());
}

/** "Maria @maria1" once the @ is known, the name alone before and for a user of another server. Never the Matrix ID. */
export function senderLabel(name: string, userId: string, ambiguous: boolean, facts: SenderFacts): string {
  if (!ambiguous && !facts.lookalikes.has(userId)) return name;
  const handle = facts.handles.get(userId);
  return handle && name !== `@${handle}` ? `${name} @${handle}` : name;
}

type SenderRow = { kind: string; own?: boolean; whoId?: string; whoAmbiguous?: boolean };

export function useSenderFacts(room: Room, isDm: boolean, rows: SenderRow[]): SenderFacts {
  const client = useClient();
  const [members, setMembers] = useState<Pick<SenderFacts, 'roles' | 'lookalikes'>>(noFacts);
  const [handles, setHandles] = useState<ReadonlyMap<string, string>>(noFacts.handles);
  const others = rows.filter((r) => r.kind === 'message' && !r.own && r.whoId);
  // A sender not seen before can be a member who just joined: read the list again.
  const sendersKey = [...new Set(others.map((r) => r.whoId))].sort().join(' ');

  useEffect(() => {
    if (isDm) {
      setMembers(noFacts);
      return;
    }
    let stopped = false;
    (async () => {
      const iterator = await room.members();
      const all: RoomMember[] = [];
      for (let chunk = iterator.nextChunk(200); chunk; chunk = iterator.nextChunk(200)) all.push(...chunk);
      if (stopped) return;
      const joined = all.filter((m) => MembershipState.Join.instanceOf(m.membership));
      const roles = new Map<string, SenderRole>();
      for (const m of joined) {
        const role = roleOf(m);
        if (role) roles.set(m.userId, role);
      }
      setMembers({ roles, lookalikes: lookalikesOf(joined) });
    })().catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [room, isDm, sendersKey]);

  // Only the senders the line names with an @ cost a lookup.
  const ambiguousKey = isDm
    ? ''
    : [...new Set(others.filter((r) => r.whoAmbiguous || members.lookalikes.has(r.whoId!)).map((r) => r.whoId!))]
        .sort()
        .join(' ');

  useEffect(() => {
    if (!ambiguousKey) return;
    let stopped = false;
    void apiFor(client)
      .resolveHandles(ambiguousKey.split(' '))
      .then((found) => !stopped && setHandles(found));
    return () => {
      stopped = true;
    };
  }, [client, ambiguousKey]);

  return useMemo(() => ({ ...members, handles }), [members, handles]);
}

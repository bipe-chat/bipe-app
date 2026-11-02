// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The info screen behind the chat title, read-only. A DM opens the person (spec person-profile with
// fromDm, PersonProfileView.swift): photo, name, the Bipe badge of an official account. A group opens
// group-details (GroupDetailsView.swift): photo, name, the people count, the description and the people.
// The header block is a 96 px avatar, the name in title2 bold, the subtitle in subheadline.

import { useEffect, useState } from 'react';
import { MembershipState, type RoomLike as Room, type RoomMember } from './generated/matrix_sdk_ffi';
import type { RoomFace } from './room-face';
import { isOfficial } from './registries';
import { Avatar } from './ui/Avatar';
import { s } from './ui/i18n';
import { NavBar, OfficialBadge, Row, Section } from './ui/List';
import { useEscape } from './ui/keys';
import { Spinner } from './ui/Spinner';

export function ChatInfo({ room, face, onClose }: { room: Room; face: RoomFace; onClose: () => void }) {
  const isDm = face.isDirect === true;
  useEscape(onClose);
  return (
    <aside className="fixed inset-0 z-40 flex min-h-0 flex-col bg-grouped-screen md:static md:z-auto md:w-[360px] md:shrink-0 md:border-l md:border-line-subtle">
      <NavBar
        title={isDm ? s('bipe_dm_details_title') : s('bipe_app_group_details_title')}
        onBack={onClose}
        backLabel={s('bipe_app_close')}
      />
      <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pb-6">
        {isDm ? <PersonInfo face={face} /> : <GroupInfo room={room} face={face} />}
      </div>
    </aside>
  );
}

function Header({
  name,
  avatarUrl,
  badge,
  subtitle,
}: {
  name: string;
  avatarUrl?: string;
  badge?: boolean;
  subtitle?: string;
}) {
  return (
    <div className="flex flex-col items-center gap-1 py-2 text-center">
      <Avatar name={name} size={96} mxcUrl={avatarUrl} />
      <div className="mt-2 flex items-center gap-1.5">
        <h3 className="text-title2 text-ink-primary">{name}</h3>
        {badge && <OfficialBadge />}
      </div>
      {subtitle && <p className="text-subheadline text-ink-secondary">{subtitle}</p>}
    </div>
  );
}

// TODO: the @handle (BipeHandleResolver, POST /v1/handles/resolve), the trust facts and marks, the bio
// and the company square need the Bipe API with an OpenID token, which the web client does not use yet.
function PersonInfo({ face }: { face: RoomFace }) {
  return <Header name={face.name} avatarUrl={face.avatarUrl} badge={isOfficial(face.peer?.userId)} />;
}

function GroupInfo({ room, face }: { room: Room; face: RoomFace }) {
  const [members, setMembers] = useState<RoomMember[]>();
  const [topic, setTopic] = useState(room.topic());
  const [count, setCount] = useState<number>();
  useEffect(() => {
    let stopped = false;
    (async () => {
      const info = await room.roomInfo();
      if (stopped) return;
      setTopic(info.topic);
      setCount(Number(info.joinedMembersCount));
      const iterator = await room.members();
      const all: RoomMember[] = [];
      for (let chunk = iterator.nextChunk(200); chunk; chunk = iterator.nextChunk(200)) all.push(...chunk);
      if (stopped) return;
      const joined = all.filter((m) => MembershipState.Join.instanceOf(m.membership));
      joined.sort((a, b) => (a.displayName ?? a.userId).localeCompare(b.displayName ?? b.userId));
      setMembers(joined);
    })().catch((e: unknown) => console.warn('[trace] group info failed', e));
    return () => {
      stopped = true;
    };
  }, [room]);
  const own = room.ownUserId();
  const people = count ?? members?.length;
  return (
    <>
      <Header
        name={face.name}
        avatarUrl={face.avatarUrl}
        subtitle={people === undefined ? undefined : s('bipe_app_group_details_people', people.toLocaleString())}
      />
      {topic && (
        <Section header={s('bipe_app_group_description')}>
          <Row>
            <p className="py-1 text-body break-words whitespace-pre-wrap text-ink-primary">{topic}</p>
          </Row>
        </Section>
      )}
      {members ? (
        <Section
          header={people === undefined ? undefined : s('bipe_app_group_details_people', people.toLocaleString())}
        >
          {members.map((m) => {
            const name = m.displayName ?? m.userId;
            return (
              <Row key={m.userId} leading={<Avatar name={name} size={32} mxcUrl={m.avatarUrl} />}>
                <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{name}</span>
                {isOfficial(m.userId) && <OfficialBadge />}
                {m.userId === own && <span className="text-subheadline text-ink-secondary">{s('bipe_app_you')}</span>}
              </Row>
            );
          })}
        </Section>
      ) : (
        <div className="grid place-items-center py-6">
          <Spinner />
        </div>
      )}
    </>
  );
}

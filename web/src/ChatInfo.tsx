// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The info screen behind the chat title, read-only. A DM opens the person (spec person-profile with
// fromDm, PersonProfileView.swift): photo, name, the Bipe badge of an official account. A group opens
// group-details (GroupDetailsView.swift): photo, name, the people count, the description and the people.
// The header block is a 96 px avatar, the name in title2 bold, the subtitle in subheadline.

import { useEffect, useState, type ReactNode } from 'react';
import { MembershipState, type RoomLike as Room, type RoomMember } from './generated/matrix_sdk_ffi';
import type { RoomFace } from './room-face';
import { isOfficial } from './registries';
import { GroupDetails } from './GroupDetails';
import type { ChatSummary } from './chat-model';
import { ProfileFactsLines, useHandle } from './Trust';
import { Avatar, type AvatarMember } from './ui/Avatar';
import { s } from './ui/i18n';
import { NavBar, OfficialBadge, Row, Section } from './ui/List';
import { useEscape } from './ui/keys';
import { Spinner } from './ui/Spinner';
import { BlockAlert, DmDeleteAlert, LeaveAlert, NotifySheet, ReportSheet } from './ChatActions';
import { useIgnoredUsers, useIsNotes } from './chat-actions';
import { useClient } from './media';
import { bell, flag, link, prohibit, signOut, trash } from './phosphor/icons';
import { Icon } from './ui/Icon';
import { publicProfile } from './api';
import { socialHandleClean, socialUrl } from './bipe/generated/lib/registries.mjs';
import { profiles } from './bipe/generated/registries.json';

export function ChatInfo({
  room,
  face,
  chats,
  latestTs,
  businessTools,
  onClose,
  onGone,
}: {
  room: Room;
  face: RoomFace;
  /** The account's chats, for the people search of Adicionar pessoas. */
  chats: ChatSummary[];
  /** The time of the chat's latest event, for the DM delete marker. */
  latestTs?: number;
  /** Spec business-chat-bar: the tools of a company account, under its name. */
  businessTools?: ReactNode;
  onClose: () => void;
  /** The chat left the list (deleted or left): back to the list. */
  onGone: () => void;
}) {
  const isDm = face.isDirect === true;
  const isNotes = useIsNotes(useClient(), room);
  useEscape(onClose);
  return (
    <aside className="fixed inset-0 z-40 flex min-h-0 flex-col bg-grouped-screen md:static md:z-auto md:w-[360px] md:shrink-0 md:border-l md:border-line-subtle">
      <NavBar
        title={isDm || isNotes ? s('bipe_dm_details_title') : s('bipe_app_group_details_title')}
        onBack={onClose}
        backLabel={s('bipe_app_close')}
      />
      <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pb-6">
        {isDm ? (
          <PersonInfo face={face} />
        ) : isNotes === false ? (
          <GroupDetails room={room} face={face} chats={chats} Header={Header} />
        ) : (
          <Header name={face.name} avatarUrl={face.avatarUrl} members={face.members} />
        )}
        {businessTools}
        {(isDm || isNotes === false) && (
          <ChatActionRows room={room} face={face} isDm={isDm} latestTs={latestTs} onGone={onGone} />
        )}
      </div>
    </aside>
  );
}

function Header({
  name,
  avatarUrl,
  badge,
  subtitle,
  userId,
  members,
}: {
  name: string;
  avatarUrl?: string;
  badge?: boolean;
  subtitle?: string;
  userId?: string;
  members?: AvatarMember[];
}) {
  return (
    <div className="flex flex-col items-center gap-1 py-2 text-center">
      <Avatar name={name} size={96} mxcUrl={avatarUrl} userId={userId} members={members} />
      <div className="mt-2 flex items-center gap-1.5">
        <h3 className="text-title2 text-ink-primary">{name}</h3>
        {badge && <OfficialBadge />}
      </div>
      {subtitle && <p className="text-subheadline text-ink-secondary">{subtitle}</p>}
    </div>
  );
}

// The @handle and the profile facts come from bipe-api (Trust.tsx), the social handles from the public profile.
// TODO: trust marks and the bio are not read yet.
function PersonInfo({ face }: { face: RoomFace }) {
  const handle = useHandle(face.peer?.userId);
  return (
    <>
      <Header
        name={face.name}
        avatarUrl={face.avatarUrl}
        badge={isOfficial(face.peer?.userId)}
        userId={face.peer?.userId}
        subtitle={handle ? `@${handle}` : undefined}
      />
      <ProfileFactsLines userId={face.peer?.userId} />
      <SocialLinks handle={handle} />
    </>
  );
}

type SocialLink = { id: string; name: string; handle: string; url: string };

/** Spec person-profile socials: PublicProfile.socials in registry order, unknown ids skipped, links built here. */
function socialLinks(socials: Record<string, string> | undefined): SocialLink[] {
  if (!socials) return [];
  return profiles.socials.flatMap((p) => {
    const handle = socialHandleClean(socials[p.id] ?? '');
    return handle ? [{ id: p.id, name: p.name, handle, url: socialUrl(p.id, handle, { profiles }) }] : [];
  });
}

// Spec person-profile socials (ADR 0228): one row per network, opened in a new browser tab, never a preview.
// The link comes from the registry template, never from the server.
function SocialLinks({ handle }: { handle?: string }) {
  const [links, setLinks] = useState<SocialLink[]>([]);
  useEffect(() => {
    if (!handle) return;
    let stopped = false;
    void publicProfile(handle).then((p) => !stopped && setLinks(socialLinks(p?.socials)));
    return () => {
      stopped = true;
    };
  }, [handle]);
  if (links.length === 0) return null;
  return (
    <Section header={s('bipe_public_profile_socials')} footer={s('bipe_public_profile_socials_not_verified')}>
      {links.map((l) => (
        <Row key={l.id}>
          <a href={l.url} target="_blank" rel="noopener noreferrer" className="flex min-w-0 flex-1 items-center gap-2 text-body text-ink-primary">
            <span className="min-w-0 flex-1 truncate">{`${l.name} · @${l.handle}`}</span>
            <Icon path={link} size={16} className="text-ink-secondary" />
          </a>
        </Row>
      ))}
    </Section>
  );
}

type Sheet = 'notify' | 'block' | 'report' | 'delete' | 'leave';

// The action rows of person-profile (fromDm) and group-details: notifications, block or unblock, report, then
// delete (DM) or leave (group), each with the apps' confirm. A DM never leaves (ADR 0141).
function ChatActionRows({
  room,
  face,
  isDm,
  latestTs,
  onGone,
}: {
  room: Room;
  face: RoomFace;
  isDm: boolean;
  latestTs?: number;
  onGone: () => void;
}) {
  const client = useClient();
  const ignored = useIgnoredUsers(client);
  const [sheet, setSheet] = useState<Sheet>();
  const peer = face.peer?.userId;
  const blocked = peer !== undefined && ignored.has(peer);
  const row = (key: Sheet, icon: string, label: string, danger = false) => (
    <li key={key} className="flex items-center gap-3 pl-4">
      <Icon path={icon} size={22} className={danger ? 'text-trust-danger-ink' : 'text-accent-ink'} />
      <button
        type="button"
        className={`flex min-h-11 min-w-0 flex-1 items-center pr-4 text-left text-body [li:not(:last-child)_&]:border-b-[0.5px] [li:not(:last-child)_&]:border-line-subtle ${danger ? 'text-trust-danger-ink' : 'text-ink-primary'}`}
        onClick={() => setSheet(key)}
      >
        {label}
      </button>
    </li>
  );
  return (
    <>
      <Section>
        {row('notify', bell, s('bipe_settings_notifications'))}
        {isDm &&
          peer &&
          row('block', prohibit, blocked ? s('bipe_action_unblock_person') : s('bipe_action_block_person'), !blocked)}
        {row('report', flag, isDm ? s('bipe_dm_report') : s('bipe_app_report_group'), true)}
        {isDm
          ? row('delete', trash, s('bipe_dm_delete_action'), true)
          : row('leave', signOut, s('bipe_app_group_leave_action'), true)}
      </Section>
      {isDm && blocked && <p className="px-4 text-footnote text-ink-secondary">{s('bipe_app_person_blocked')}</p>}
      {sheet === 'notify' && <NotifySheet room={room} isDm={isDm} onClose={() => setSheet(undefined)} />}
      {sheet === 'block' && peer && (
        <BlockAlert userId={peer} name={face.name} blocked={blocked} onClose={() => setSheet(undefined)} />
      )}
      {sheet === 'report' && (
        <ReportSheet
          room={room}
          isDm={isDm}
          peer={peer}
          latestTs={latestTs}
          onDone={onGone}
          onClose={() => setSheet(undefined)}
        />
      )}
      {sheet === 'delete' && (
        <DmDeleteAlert room={room} latestTs={latestTs ?? 0} onDone={onGone} onClose={() => setSheet(undefined)} />
      )}
      {sheet === 'leave' && <LeaveAlert room={room} onDone={onGone} onClose={() => setSheet(undefined)} />}
    </>
  );
}

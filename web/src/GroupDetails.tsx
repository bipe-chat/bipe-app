// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Group details with the admin basics (specs group-details and group-members; GroupDetailsView.swift): photo,
// name, the people count, the description, the people (admins marked) and the invites not yet joined. People
// with the right power level edit the name, description and photo, add people, make or remove admins, take
// people out and cancel invites; nothing else, as the spec says (no roles editor beyond admin).

import { useEffect, useState } from 'react';
import type { ChatSummary } from './chat-model';
import {
  MembershipState,
  PowerLevel,
  RoomMemberRole,
  StateEventType,
  suggestedPowerLevelForRole,
  type RoomLike as Room,
  type RoomMember,
  type RoomPowerLevelsLike,
} from './generated';
import { PersonRow, SearchBox, usePeople } from './NewChat';
import { isOfficial } from './registries';
import type { RoomFace } from './room-face';
import { Alert } from './ui/Alert';
import { Avatar } from './ui/Avatar';
import { s } from './ui/i18n';
import { NavBar, OfficialBadge, Row, Section } from './ui/List';
import { Spinner } from './ui/Spinner';
import { userPlus } from './phosphor/icons';
import { Icon } from './ui/Icon';

type Rights = { edit: boolean; invite: boolean; kick: boolean; roles: boolean };

const isAdmin = (m: RoomMember) =>
  m.suggestedRoleForPowerLevel === RoomMemberRole.Administrator ||
  m.suggestedRoleForPowerLevel === RoomMemberRole.Creator;

function levelOf(role: RoomMemberRole): bigint {
  const level = suggestedPowerLevelForRole(role);
  return PowerLevel.Value.instanceOf(level) ? level.inner.value : 0n;
}

export function GroupDetails({
  room,
  face,
  chats,
  Header,
}: {
  room: Room;
  face: RoomFace;
  chats: ChatSummary[];
  Header: (p: { name: string; avatarUrl?: string; subtitle?: string }) => React.ReactNode;
}) {
  const [members, setMembers] = useState<RoomMember[]>();
  const [topic, setTopic] = useState(room.topic());
  const [count, setCount] = useState<number>();
  const [rights, setRights] = useState<Rights>({ edit: false, invite: false, kick: false, roles: false });
  const [version, setVersion] = useState(0);
  const [editing, setEditing] = useState(false);
  const [adding, setAdding] = useState(false);
  const [acting, setActing] = useState<RoomMember>();
  const [error, setError] = useState('');
  useEffect(() => {
    let stopped = false;
    (async () => {
      const info = await room.roomInfo();
      const levels: RoomPowerLevelsLike | undefined = await room.getPowerLevels().catch(() => undefined);
      if (stopped) return;
      setTopic(info.topic);
      setCount(Number(info.joinedMembersCount));
      if (levels)
        setRights({
          // canEdit of the spec: the level for m.room.name covers photo and description in Bipe groups.
          edit: levels.canOwnUserSendState(new StateEventType.RoomName()),
          invite: levels.canOwnUserInvite(),
          kick: levels.canOwnUserKick(),
          roles: levels.canOwnUserSendState(new StateEventType.RoomPowerLevels()),
        });
      const iterator = await room.members();
      const all: RoomMember[] = [];
      for (let chunk = iterator.nextChunk(200); chunk; chunk = iterator.nextChunk(200)) all.push(...chunk);
      if (stopped) return;
      const keep = all.filter(
        (m) => MembershipState.Join.instanceOf(m.membership) || MembershipState.Invite.instanceOf(m.membership),
      );
      keep.sort((a, b) => (a.displayName ?? a.userId).localeCompare(b.displayName ?? b.userId));
      setMembers(keep);
    })().catch((e: unknown) => console.warn('[trace] group info failed', e));
    return () => {
      stopped = true;
    };
  }, [room, version]);
  const own = room.ownUserId();
  const joined = members?.filter((m) => MembershipState.Join.instanceOf(m.membership)) ?? [];
  const invited = members?.filter((m) => MembershipState.Invite.instanceOf(m.membership)) ?? [];
  const people = count ?? joined.length;
  const reload = () => setVersion((v) => v + 1);
  const run = (work: Promise<unknown>) => {
    setError('');
    work.then(reload).catch(() => setError(s('bipe_app_group_member_error')));
  };

  if (editing)
    return (
      <EditGroup
        room={room}
        name={face.name}
        topic={topic ?? ''}
        avatarUrl={face.avatarUrl}
        onDone={() => {
          setEditing(false);
          reload();
        }}
      />
    );
  return (
    <>
      <Header
        name={face.name}
        avatarUrl={face.avatarUrl}
        subtitle={s('bipe_app_group_details_people', people.toLocaleString())}
      />
      {rights.edit && (
        <button type="button" className="-mt-4 self-center text-body text-accent-ink" onClick={() => setEditing(true)}>
          {s('bipe_app_edit')}
        </button>
      )}
      {topic && (
        <Section header={s('bipe_app_group_description')}>
          <Row>
            <p className="py-1 text-body break-words whitespace-pre-wrap text-ink-primary">{topic}</p>
          </Row>
        </Section>
      )}
      {error && <p className="px-4 text-footnote text-trust-danger-ink">{error}</p>}
      {members ? (
        <>
          <Section header={s('bipe_app_group_members_title')}>
            {rights.invite && (
              <Row leading={<Icon path={userPlus} size={22} className="mx-[5px] text-accent-ink" />}>
                <button
                  type="button"
                  className="min-w-0 flex-1 py-1 text-left text-body text-accent-ink"
                  onClick={() => setAdding(true)}
                >
                  {s('bipe_app_group_add_people')}
                </button>
              </Row>
            )}
            {joined.map((m) => {
              const name = m.displayName ?? m.userId;
              const canAct = m.userId !== own && (rights.kick || rights.roles);
              return (
                <Row key={m.userId} leading={<Avatar name={name} size={32} mxcUrl={m.avatarUrl} userId={m.userId} />}>
                  <button
                    type="button"
                    disabled={!canAct}
                    className="flex min-w-0 flex-1 flex-col py-1 text-left disabled:cursor-default"
                    onClick={() => setActing(m)}
                  >
                    <span className="flex items-center gap-1.5">
                      <span className="min-w-0 truncate text-body text-ink-primary">{name}</span>
                      {isOfficial(m.userId) && <OfficialBadge />}
                    </span>
                    {isAdmin(m) && (
                      <span className="text-footnote text-ink-secondary">{s('bipe_app_group_admin')}</span>
                    )}
                  </button>
                  {m.userId === own && <span className="text-subheadline text-ink-secondary">{s('bipe_app_you')}</span>}
                </Row>
              );
            })}
          </Section>
          {invited.length > 0 && (
            <Section header={s('bipe_app_group_members_invited')}>
              {invited.map((m) => {
                const name = m.displayName ?? m.userId;
                return (
                  <Row key={m.userId} leading={<Avatar name={name} size={32} mxcUrl={m.avatarUrl} userId={m.userId} />}>
                    <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{name}</span>
                    {rights.kick && (
                      <button
                        type="button"
                        className="text-subheadline text-trust-danger-ink"
                        onClick={() => run(room.kickUser(m.userId, undefined))}
                      >
                        {s('bipe_app_group_member_cancel_invite')}
                      </button>
                    )}
                  </Row>
                );
              })}
            </Section>
          )}
        </>
      ) : (
        <div className="grid place-items-center py-6">
          <Spinner />
        </div>
      )}
      {acting && (
        <MemberActions
          member={acting}
          rights={rights}
          onClose={() => setActing(undefined)}
          onAdmin={(on) =>
            run(
              room.updatePowerLevelsForUsers([
                { userId: acting.userId, powerLevel: levelOf(on ? RoomMemberRole.Administrator : RoomMemberRole.User) },
              ]),
            )
          }
          onRemove={() => run(room.kickUser(acting.userId, undefined))}
        />
      )}
      {adding && (
        <AddPeople
          chats={chats}
          already={new Set(members?.map((m) => m.userId) ?? [])}
          onClose={() => setAdding(false)}
          onInvite={(ids) => {
            setAdding(false);
            run(Promise.all(ids.map((id) => room.inviteUserById(id))));
          }}
        />
      )}
    </>
  );
}

// The action sheet of a member (group-members openMember): make or remove admin, remove from the group.
function MemberActions({
  member,
  rights,
  onAdmin,
  onRemove,
  onClose,
}: {
  member: RoomMember;
  rights: Rights;
  onAdmin: (on: boolean) => void;
  onRemove: () => void;
  onClose: () => void;
}) {
  const [confirm, setConfirm] = useState(false);
  const name = member.displayName ?? member.userId;
  if (confirm)
    return (
      <Alert
        title={s('bipe_app_group_member_remove')}
        action={s('bipe_app_group_member_remove')}
        onCancel={onClose}
        onAction={() => {
          onClose();
          onRemove();
        }}
      >
        <p>{s('bipe_app_group_member_remove_body', name)}</p>
      </Alert>
    );
  const admin = isAdmin(member);
  return (
    // The dim behind a sheet is black at 40% in both schemes, as the system draws it; no token covers it.
    <div className="fixed inset-0 z-50 grid place-items-end bg-black/40 md:place-items-center" onClick={onClose}>
      <div
        role="menu"
        aria-label={name}
        className="m-3 flex w-full max-w-[400px] flex-col overflow-hidden rounded-[14px] bg-surface-raised"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="px-4 pt-3 pb-1 text-center text-footnote text-ink-secondary">{name}</p>
        {rights.roles && member.suggestedRoleForPowerLevel !== RoomMemberRole.Creator && (
          <button
            type="button"
            role="menuitem"
            className="min-h-11 border-t-[0.5px] border-line-subtle text-body text-accent-ink"
            onClick={() => {
              onClose();
              onAdmin(!admin);
            }}
          >
            {admin ? s('bipe_app_group_member_remove_admin') : s('bipe_app_group_member_make_admin')}
          </button>
        )}
        {rights.kick && (
          <button
            type="button"
            role="menuitem"
            className="min-h-11 border-t-[0.5px] border-line-subtle text-body text-trust-danger-ink"
            onClick={() => setConfirm(true)}
          >
            {s('bipe_app_group_member_remove')}
          </button>
        )}
        <button
          type="button"
          className="min-h-11 border-t-[0.5px] border-line-subtle text-headline text-accent-ink"
          onClick={onClose}
        >
          {s('bipe_app_cancel')}
        </button>
      </div>
    </div>
  );
}

// Adicionar pessoas (group-invite-members, pick mode): the people search of Nova conversa with check marks.
function AddPeople({
  chats,
  already,
  onInvite,
  onClose,
}: {
  chats: ChatSummary[];
  already: Set<string>;
  onInvite: (userIds: string[]) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const { people } = usePeople(chats, query);
  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-surface-base md:static md:z-auto md:h-full">
      <NavBar title={s('bipe_app_group_add_people')} onBack={onClose} backLabel={s('bipe_app_cancel')} />
      <SearchBox value={query} onChange={setQuery} />
      <ul className="min-h-0 flex-1 overflow-y-auto">
        {people
          .filter((p) => !already.has(p.userId))
          .map((p) => (
            <PersonRow
              key={p.userId}
              person={p}
              picked={picked.includes(p.userId)}
              onClick={() =>
                setPicked((list) =>
                  list.includes(p.userId) ? list.filter((x) => x !== p.userId) : [...list, p.userId],
                )
              }
            />
          ))}
      </ul>
      <div className="shrink-0 border-t-[0.5px] border-line-subtle p-3">
        <button
          type="button"
          disabled={picked.length === 0}
          className="min-h-[50px] w-full rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent disabled:opacity-50"
          onClick={() => onInvite(picked)}
        >
          {s('bipe_app_group_add_people')}
        </button>
      </div>
    </div>
  );
}

// The editing phase of group-details: name, description and photo, then Salvar. bipe_app_save_error on
// failure, and the form stays.
function EditGroup({
  room,
  name: oldName,
  topic: oldTopic,
  avatarUrl,
  onDone,
}: {
  room: Room;
  name: string;
  topic: string;
  avatarUrl?: string;
  onDone: () => void;
}) {
  const [name, setName] = useState(oldName);
  const [topic, setTopic] = useState(oldTopic);
  const [photo, setPhoto] = useState<File | null>();
  const [preview, setPreview] = useState<string>();
  const [phase, setPhase] = useState<'editing' | 'saving' | 'failed'>('editing');
  useEffect(() => {
    if (!photo) return setPreview(undefined);
    const url = URL.createObjectURL(photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);
  const save = async () => {
    setPhase('saving');
    try {
      if (name.trim() && name.trim() !== oldName) await room.setName(name.trim());
      if (topic.trim() !== oldTopic.trim()) await room.setTopic(topic.trim());
      if (photo) {
        const bitmap = await createImageBitmap(photo);
        await room.uploadAvatar(photo.type || 'image/jpeg', await photo.arrayBuffer(), {
          width: BigInt(bitmap.width),
          height: BigInt(bitmap.height),
          mimetype: photo.type || 'image/jpeg',
          size: BigInt(photo.size),
        });
      } else if (photo === null) await room.removeAvatar();
      onDone();
    } catch (e) {
      console.warn('[trace] group save failed', e);
      setPhase('failed');
    }
  };
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col items-center gap-2 py-2">
        {preview ? (
          <img src={preview} alt="" className="size-24 rounded-full object-cover" />
        ) : (
          <Avatar name={name || oldName} size={96} mxcUrl={photo === null ? undefined : avatarUrl} />
        )}
        <label className="cursor-pointer text-body text-accent-ink">
          {s('bipe_app_group_photo_choose')}
          <input
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) setPhoto(f);
            }}
          />
        </label>
        {(avatarUrl || photo) && (
          <button type="button" className="text-body text-trust-danger-ink" onClick={() => setPhoto(null)}>
            {s('bipe_app_group_photo_remove')}
          </button>
        )}
      </div>
      <Section header={s('bipe_app_group_name')}>
        <Row>
          <input
            className="min-h-11 min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none"
            aria-label={s('bipe_app_group_name')}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Row>
      </Section>
      <Section header={s('bipe_app_group_description')}>
        <Row>
          <textarea
            className="min-h-20 min-w-0 flex-1 resize-none bg-transparent py-2 text-body text-ink-primary outline-none"
            placeholder={s('bipe_app_group_description_placeholder')}
            aria-label={s('bipe_app_group_description')}
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
          />
        </Row>
      </Section>
      {phase === 'failed' && <p className="px-4 text-footnote text-trust-danger-ink">{s('bipe_app_save_error')}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          className="min-h-11 flex-1 rounded-full bg-surface-sunken text-body text-accent-ink"
          onClick={onDone}
        >
          {s('bipe_app_cancel')}
        </button>
        <button
          type="button"
          disabled={phase === 'saving' || !name.trim()}
          className="min-h-11 flex-1 rounded-full bg-accent-fill text-headline text-ink-on-accent disabled:opacity-50"
          onClick={() => void save()}
        >
          {phase === 'saving' ? <Spinner /> : s('bipe_app_save')}
        </button>
      </div>
    </div>
  );
}

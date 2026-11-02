// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Nova conversa and Novo grupo (the CreateGroupButton menu of ChatListView.swift; PeopleService.openOrCreateDM
// and NativeDmSelection on iOS; spec group-create). People come from the account's DMs and the homeserver
// user directory (Client.searchUsers). A DM reuses the newest joined DM with the person (ADR 0141, the
// generated rule newestDmRooms); a new one is encrypted, trusted private, invites the person, and then
// writes the DM power levels of registries dmPowerLevels. A group is encrypted, private, with the defaults
// of the privateChat preset; people are invited one by one after the create.

import { useEffect, useState } from 'react';
import { newestDmRooms } from './bipe/generated/lib/registries.mjs';
import { dmPowerLevels } from './bipe/generated/registries.json';
import type { ChatSummary } from './chat-model';
import {
  Membership,
  RoomPreset,
  RoomVisibility,
  type ClientLike as Client,
  type RoomLike as Room,
} from './generated/matrix_sdk_ffi';
import { useClient } from './media';
import { check, magnifyingGlass } from './phosphor/icons';
import { Avatar } from './ui/Avatar';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { NavBar } from './ui/List';
import { Spinner } from './ui/Spinner';
import { describe, normalize } from './util';

type Person = { userId: string; name: string; avatarUrl?: string };

/** The DM content of registries dmPowerLevels for these people (BipeDmPowerLevels.content). */
function dmPowerLevelsContent(userIds: string[]): string {
  const pl = dmPowerLevels;
  return JSON.stringify({
    ban: pl.ban,
    events: pl.events,
    events_default: pl.eventsDefault,
    invite: pl.invite,
    kick: pl.kick,
    notifications: pl.notifications,
    redact: pl.redact,
    state_default: pl.stateDefault,
    users: Object.fromEntries([...new Set(userIds)].sort().map((id) => [id, pl.usersDefault])),
    users_default: pl.usersDefault,
  });
}

async function roomWhenSynced(client: Client, roomId: string): Promise<Room | undefined> {
  // The created room reaches the local store with the next sync.
  for (let attempt = 1; attempt <= 10; attempt++) {
    try {
      const room = client.getRoom(roomId);
      if (room) return room;
    } catch {
      // Not there yet.
    }
    await new Promise((r) => setTimeout(r, attempt * 300));
  }
  return undefined;
}

/** The newest joined DM with the person, else a new one (PeopleService.openOrCreateDM). Returns the room ID. */
export async function openOrCreateDm(client: Client, peer: string): Promise<string> {
  const candidates = [];
  for (const room of client.getDmRooms(peer)) {
    const info = await room.roomInfo();
    if (!info.isDm || info.isSpace || info.membership !== Membership.Joined) continue;
    const latest = await room.latestEvent().catch(() => undefined);
    const at =
      latest && 'inner' in latest && latest.inner && 'timestamp' in latest.inner
        ? Number(latest.inner.timestamp)
        : null;
    candidates.push({ roomId: info.id, peerUserId: peer, latestActivityMs: at, eligible: true, cleared: false });
  }
  const existing = (newestDmRooms(candidates) as Record<string, string>)[peer];
  if (existing) return existing;
  const me = client.userId();
  const roomId = await client.createRoom({
    name: undefined,
    topic: undefined,
    isEncrypted: true,
    isDirect: true,
    visibility: new RoomVisibility.Private(),
    preset: RoomPreset.TrustedPrivateChat,
    invite: [peer],
    avatar: undefined,
    powerLevelContentOverride: {
      users: new Map([
        [me, dmPowerLevels.createLevel],
        [peer, dmPowerLevels.usersDefault],
      ]),
      events: new Map(),
    },
    joinRuleOverride: undefined,
    historyVisibilityOverride: undefined,
    canonicalAlias: undefined,
    isSpace: false,
  });
  // A failed second write leaves a usable DM where only the account can invite or change it.
  const room = await roomWhenSynced(client, roomId);
  await room?.sendStateEventRaw('m.room.power_levels', '', dmPowerLevelsContent([me, peer])).catch(() => undefined);
  return roomId;
}

const refused = (e: unknown) => /forbidden|M_FORBIDDEN/i.test(describe(e));

/** Known people from the DMs plus the user directory, filtered by the search. */
function usePeople(chats: ChatSummary[], query: string): { people: Person[]; searching: boolean } {
  const client = useClient();
  const [found, setFound] = useState<Person[]>([]);
  const [searching, setSearching] = useState(false);
  const term = query.trim().replace(/^@/, '');
  useEffect(() => {
    if (term.length < 2) return setFound([]);
    let stopped = false;
    setSearching(true);
    const timer = setTimeout(() => {
      client
        .searchUsers(term, 20n)
        .then((r) => {
          if (stopped) return;
          setFound(
            r.results.map((p) => ({ userId: p.userId, name: p.displayName ?? p.userId, avatarUrl: p.avatarUrl })),
          );
        })
        .catch(() => !stopped && setFound([]))
        .finally(() => !stopped && setSearching(false));
    }, 300);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [client, term]);
  const me = client.userId();
  const known: Person[] = chats
    .filter((c) => c.kind === 'dm' && c.face.peer)
    .map((c) => ({ userId: c.face.peer!.userId, name: c.face.name, avatarUrl: c.face.avatarUrl }));
  const needle = normalize(term);
  const all = new Map<string, Person>();
  for (const p of [...known.filter((p) => !needle || normalize(p.name).includes(needle)), ...found]) {
    if (p.userId !== me && !all.has(p.userId)) all.set(p.userId, p);
  }
  return { people: [...all.values()], searching };
}

function SearchBox({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="mx-4 my-2 flex h-9 shrink-0 items-center gap-1.5 rounded-[10px] bg-surface-sunken px-2 text-ink-secondary">
      <Icon path={magnifyingGlass} size={18} />
      <input
        type="search"
        autoFocus
        className="min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none placeholder:text-ink-secondary"
        placeholder={s('bipe_app_people_search')}
        aria-label={s('bipe_app_people_search')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}

function PersonRow({ person, picked, onClick }: { person: Person; picked?: boolean; onClick: () => void }) {
  return (
    <li>
      <button
        type="button"
        role={picked === undefined ? undefined : 'checkbox'}
        aria-checked={picked}
        className="flex w-full items-center gap-3 px-4 py-1.5 text-left hover:bg-surface-raised"
        onClick={onClick}
      >
        <Avatar name={person.name} size={40} mxcUrl={person.avatarUrl} userId={person.userId} />
        <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{person.name}</span>
        {picked !== undefined && (
          <span
            className={`grid size-6 shrink-0 place-items-center rounded-full border ${picked ? 'border-accent-fill bg-accent-fill text-ink-on-accent' : 'border-line-strong'}`}
          >
            {picked && <Icon path={check} size={14} />}
          </span>
        )}
      </button>
    </li>
  );
}

export function NewChat({
  chats,
  onOpen,
  onNewGroup,
  onClose,
}: {
  chats: ChatSummary[];
  onOpen: (roomId: string) => void;
  onNewGroup: () => void;
  onClose: () => void;
}) {
  const client = useClient();
  const [query, setQuery] = useState('');
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const { people, searching } = usePeople(chats, query);
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_action_start_chat')} onBack={onClose} />
      <SearchBox value={query} onChange={setQuery} />
      <button type="button" className="mx-4 mb-1 min-h-11 self-start text-body text-accent-ink" onClick={onNewGroup}>
        {s('bipe_app_group_create_title')}
      </button>
      {error && <p className="px-4 py-1 text-footnote text-trust-danger-ink">{error}</p>}
      {working && (
        <div className="grid place-items-center py-2">
          <Spinner />
        </div>
      )}
      <ul className="min-h-0 flex-1 overflow-y-auto">
        {people.map((p) => (
          <PersonRow
            key={p.userId}
            person={p}
            onClick={() => {
              if (working) return;
              setWorking(true);
              setError('');
              openOrCreateDm(client, p.userId)
                .then(onOpen)
                .catch((e: unknown) => {
                  console.warn('[trace] start chat failed', describe(e));
                  setError(refused(e) ? s('bipe_app_group_invite_refused', p.name) : s('bipe_error_generic'));
                })
                .finally(() => setWorking(false));
            }}
          />
        ))}
        {!searching && query.trim().length >= 2 && people.length === 0 && (
          <li className="px-6 py-8 text-center text-ink-secondary">{s('bipe_app_search_no_results', query.trim())}</li>
        )}
      </ul>
    </div>
  );
}

// Spec group-create: the name, the people (picked from the same search), Criar grupo. No photo on the web yet.
export function NewGroup({
  chats,
  onOpen,
  onClose,
}: {
  chats: ChatSummary[];
  onOpen: (roomId: string) => void;
  onClose: () => void;
}) {
  const client = useClient();
  const [name, setName] = useState('');
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<Person[]>([]);
  const [phase, setPhase] = useState<'editing' | 'creating' | 'failed'>('editing');
  const [invitesFailed, setInvitesFailed] = useState<string>();
  const { people } = usePeople(chats, query);
  const canCreate = name.trim() !== '' && phase !== 'creating';
  const toggle = (p: Person) =>
    setPicked((list) =>
      list.some((x) => x.userId === p.userId) ? list.filter((x) => x.userId !== p.userId) : [...list, p],
    );
  const create = async () => {
    setPhase('creating');
    try {
      const roomId = await client.createRoom({
        name: name.trim(),
        topic: undefined,
        isEncrypted: true,
        isDirect: false,
        visibility: new RoomVisibility.Private(),
        preset: RoomPreset.PrivateChat,
        invite: undefined,
        avatar: undefined,
        powerLevelContentOverride: undefined,
        joinRuleOverride: undefined,
        historyVisibilityOverride: undefined,
        canonicalAlias: undefined,
        isSpace: false,
      });
      const room = await roomWhenSynced(client, roomId);
      let failed = false;
      for (const p of picked) {
        // One refused invite does not stop the group.
        await room?.inviteUserById(p.userId).catch(() => (failed = true));
      }
      // The group is made: a failed invite shows bipe_app_group_create_invites_failed before it opens.
      if (failed || (!room && picked.length > 0)) setInvitesFailed(roomId);
      else onOpen(roomId);
    } catch (e) {
      console.warn('[trace] group create failed', describe(e));
      setPhase('failed');
    }
  };
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_app_group_create_title')} onBack={onClose} />
      <div className="flex flex-col gap-2 px-4 pt-2">
        <input
          className="min-h-11 rounded-xl bg-surface-raised px-3 text-body text-ink-primary outline-none focus-visible:outline-2 focus-visible:outline-accent-fill"
          placeholder={s('bipe_app_group_name')}
          aria-label={s('bipe_app_group_name')}
          value={name}
          disabled={phase === 'creating'}
          onChange={(e) => setName(e.target.value)}
        />
        {picked.length > 0 && (
          <p className="text-footnote text-ink-secondary">
            {s('bipe_app_group_create_people', picked.map((p) => p.name).join(', '))}
          </p>
        )}
        {phase === 'failed' && (
          <p className="text-footnote text-trust-danger-ink">{s('bipe_app_group_create_error')}</p>
        )}
        {invitesFailed && (
          <div className="flex flex-col gap-2 rounded-xl bg-trust-caution-wash p-3">
            <p className="text-footnote text-ink-primary">{s('bipe_app_group_create_invites_failed')}</p>
            <button
              type="button"
              className="min-h-11 self-start rounded-full bg-accent-fill px-4 text-subheadline font-semibold text-ink-on-accent"
              onClick={() => onOpen(invitesFailed)}
            >
              {s('bipe_action_continue')}
            </button>
          </div>
        )}
        <button
          type="button"
          disabled={!canCreate}
          className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent disabled:opacity-50"
          onClick={() => void create()}
        >
          {phase === 'creating' ? s('bipe_app_group_creating') : s('bipe_groups_empty_action')}
        </button>
      </div>
      <h3 className="px-4 pt-4 text-footnote text-ink-secondary">{s('bipe_app_group_add_people')}</h3>
      <SearchBox value={query} onChange={setQuery} />
      <ul className="min-h-0 flex-1 overflow-y-auto">
        {[...picked, ...people.filter((p) => !picked.some((x) => x.userId === p.userId))].map((p) => (
          <PersonRow
            key={p.userId}
            person={p}
            picked={picked.some((x) => x.userId === p.userId)}
            onClick={() => toggle(p)}
          />
        ))}
      </ul>
    </div>
  );
}

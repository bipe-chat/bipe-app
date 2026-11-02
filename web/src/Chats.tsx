// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The chat list and the open chat (ChatListView.swift). A wide window (768 px and up) shows them side
// by side, like NavigationSplitView on iPad: the list column is 360 px (300 to 420 on iOS), the
// selected row is accent.wash, and with no chat open the detail shows the Bipe mark. A narrow window
// shows one at a time, with a back button in the chat.
//
// Structure (specs chat-list, chat-list-filters, chat-requests, chat-archive; ADR 0053): two scopes,
// Conversas (DMs, with the Pedidos row on top) and Grupos (other joined rooms, group invites on top).
// The apps switch them with the tab bar; the web has a segmented control under the title. Archived chats
// leave both lists and show under the Arquivadas row at the end of each scope.

import { useEffect, useMemo, useRef, useState, type MouseEvent } from 'react';
import { ChatArchive, useArchiveVersion } from './archive';
import { useChatSummaries, type ChatSummary } from './chat-model';
import {
  RoomListEntriesDynamicFilterKind,
  RoomListEntriesUpdate,
  RoomNotificationMode,
  type ClientLike as Client,
  type RoomLike as Room,
  type RoomListEntriesWithDynamicAdaptersResultLike as RoomListEntriesWithDynamicAdaptersResult,
  type SyncServiceLike as SyncService,
} from './generated/matrix_sdk_ffi';
import { archive as archiveIcon, bellSlash, caretLeft, gear, magnifyingGlass, userCircle, x } from './phosphor/icons';
import { isOfficial } from './registries';
import { rowPreview, rowTime } from './row-preview';
import { OfficialBadge } from './ui/List';
import { RequestScreen, RequestsList, requesterOf } from './Requests';
import { ClientContext } from './media';
import { Settings } from './Settings';
import { RoomTimeline } from './Timeline';
import { Avatar } from './ui/Avatar';
import { BipeMark } from './ui/BipeMark';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';
import { applyDiffs, describe, type DiffVariants } from './util';

type SyncState = { kind: 'starting' } | { kind: 'ready' } | { kind: 'failed'; message: string };
type Scope = 'conversas' | 'grupos';
type Detail =
  { kind: 'room'; room: Room } | { kind: 'settings' } | { kind: 'requests' } | { kind: 'request'; roomId: string };

export function Chats({ client }: { client: Client }) {
  const [rooms, setRooms] = useState<Room[]>([]);
  const [detail, setDetail] = useState<Detail>();
  const [scope, setScope] = useState<Scope>('conversas');
  const [showingArchived, setShowingArchived] = useState(false);
  const [menu, setMenu] = useState<{ chat: ChatSummary; x: number; y: number }>();
  const [query, setQuery] = useState('');
  const [sync, setSync] = useState<SyncState>({ kind: 'starting' });
  const keep = useRef<{ sync?: SyncService; entries?: RoomListEntriesWithDynamicAdaptersResult }>({});

  useEffect(() => {
    let stopped = false;
    (async () => {
      const sync = await client.syncService().finish();
      keep.current.sync = sync;
      await sync.start();
      const list = await sync.roomListService().allRooms();
      const entries = list.entriesWithDynamicAdapters(100, {
        onUpdate(updates) {
          if (!stopped) setRooms((prev) => applyDiffs(prev, updates, RoomListEntriesUpdate as unknown as DiffVariants));
        },
      });
      keep.current.entries = entries;
      // NonLeft: joined rooms and invites. The scopes, requests and archive split them here.
      entries.controller().setFilter(new RoomListEntriesDynamicFilterKind.NonLeft());
      console.info(`[trace] signed in as ${client.userId()}`);
      setSync({ kind: 'ready' });
    })().catch((e) => setSync({ kind: 'failed', message: describe(e) }));
    return () => {
      stopped = true;
      void keep.current.sync?.stop();
    };
  }, [client]);

  const summaries = useChatSummaries(rooms);
  const archive = useMemo(() => new ChatArchive(client.userId()), [client]);
  useArchiveVersion(archive);
  useEffect(() => {
    archive.reconcile(
      summaries
        .filter((c) => c.kind === 'dm' || c.kind === 'group')
        .map((c) => ({
          room: c.room,
          roomId: c.roomId,
          serverArchived: c.info?.isLowPriority ?? false,
          activity: c.latest?.at,
          // TODO: iOS reads the newest incoming message from the local timeline (RowActivityScanner); the
          // web takes the SDK latest event when someone else sent it.
          incoming: c.latest && !c.latest.isOwn ? c.latest.at : undefined,
        })),
    );
  }, [summaries, archive]);

  const open = detail?.kind === 'room' ? detail.room : undefined;
  const inScope = (c: ChatSummary) => (scope === 'conversas' ? c.kind === 'dm' : c.kind === 'group');
  const active = summaries.filter((c) => inScope(c) && !archive.isArchived(c.roomId));
  const archived = summaries.filter((c) => inScope(c) && archive.isArchived(c.roomId));
  const requests = summaries.filter((c) => c.kind === 'request');
  const groupInvites = summaries.filter((c) => c.kind === 'groupInvite');
  // Tab badges (chat-list-filters): unread DMs; mentions in groups. Archived chats keep their badges.
  const chatsBadge = summaries.filter((c) => c.kind === 'dm' && Number(c.info?.numUnreadMessages ?? 0) > 0).length;
  const groupsBadge = summaries
    .filter((c) => c.kind === 'group')
    .reduce((n, c) => n + Number(c.info?.numUnreadMentions ?? 0), 0);
  const scopeTitle = scope === 'conversas' ? s('bipe_app_chat_list_title') : s('bipe_tab_groups');
  const request = detail?.kind === 'request' ? summaries.find((c) => c.roomId === detail.roomId) : undefined;
  const showsDetail = detail !== undefined;

  const openChat = (room: Room) => setDetail({ kind: 'room', room });
  const searching = query.trim() !== '';
  const needle = normalize(query.trim());
  // Search covers both scopes and archived chats; it opens any of them.
  const found = summaries.filter(
    (c) => (c.kind === 'dm' || c.kind === 'group') && normalize(c.face.name).includes(needle),
  );
  const rows = searching ? found : showingArchived ? archived : active;
  const ownUserId = client.userId();

  return (
    <ClientContext value={client}>
      <div className="flex h-full min-h-0 overflow-hidden">
        <aside
          className={`${showsDetail ? 'hidden md:flex' : 'flex'} min-h-0 w-full min-w-0 flex-col overflow-hidden bg-surface-base md:w-[360px] md:shrink-0 md:border-r md:border-line-subtle`}
        >
          {/* The navigation bar: the toolbar row with its glass button, then the large title. */}
          <div className="flex h-11 shrink-0 items-center justify-end px-2">
            {/* Settings for this browser; sign out lives there, in Sua conta. */}
            <button
              className={`grid size-11 place-items-center rounded-full ${detail?.kind === 'settings' ? 'bg-accent-fill text-ink-on-accent' : 'bipe-glass text-glyph-primary'}`}
              aria-label={s('bipe_settings_section')}
              title={s('bipe_settings_section')}
              aria-pressed={detail?.kind === 'settings'}
              onClick={() => setDetail({ kind: 'settings' })}
            >
              <Icon path={gear} size={22} />
            </button>
          </div>
          <h1 className="shrink-0 px-4 pb-2 text-large-title text-ink-primary">{scopeTitle}</h1>
          <SearchField value={query} onChange={setQuery} />
          {!searching && (
            <ScopePicker
              scope={scope}
              badges={{ conversas: chatsBadge, grupos: groupsBadge }}
              onChange={(next) => {
                setScope(next);
                setShowingArchived(false);
              }}
            />
          )}
          {sync.kind === 'failed' && (
            <div className="px-4 py-2">
              <p className="text-subheadline text-trust-danger-ink">{s('bipe_error_generic')}</p>
              <p className="text-footnote break-words text-ink-secondary">{sync.message}</p>
            </div>
          )}
          {archive.failed && (
            <div className="flex items-center gap-3 px-4 py-2">
              <p className="min-w-0 flex-1 text-footnote text-trust-danger-ink">{s('bipe_archive_error')}</p>
              <button type="button" className="text-subheadline text-accent-ink" onClick={() => archive.retry()}>
                {s('bipe_app_retry')}
              </button>
            </div>
          )}
          <ul
            className="min-h-0 flex-1 overflow-y-auto overscroll-contain"
            onScroll={(e) => {
              // loadMore (spec chat-list): the next page when the end of the list is near.
              const el = e.currentTarget;
              if (el.scrollTop + el.clientHeight > el.scrollHeight - 200)
                keep.current.entries?.controller().addOnePage();
            }}
          >
            {searching ? null : showingArchived ? (
              <EntryRow icon={caretLeft} title={scopeTitle} onOpen={() => setShowingArchived(false)} />
            ) : scope === 'conversas' ? (
              requests.length > 0 && (
                <EntryRow
                  icon={userCircle}
                  title={s('bipe_requests_title')}
                  detail={s('bipe_requests_row_body')}
                  count={requests.length}
                  selected={detail?.kind === 'requests' || detail?.kind === 'request'}
                  onOpen={() => setDetail({ kind: 'requests' })}
                />
              )
            ) : (
              groupInvites.map((invite) => <GroupInviteRow key={invite.roomId} invite={invite} onJoined={openChat} />)
            )}
            {rows.length === 0 && sync.kind === 'starting' && (
              <li className="grid place-items-center py-12">
                <Spinner />
              </li>
            )}
            {rows.length === 0 && sync.kind === 'ready' && (
              <li className="px-6 py-12 text-center text-ink-secondary">
                {searching
                  ? s('bipe_app_search_no_results', query.trim())
                  : showingArchived
                    ? s('bipe_archived_empty')
                    : scope === 'conversas'
                      ? s('bipe_app_chat_list_empty')
                      : s('bipe_app_groups_empty')}
              </li>
            )}
            {rows.map((chat) => (
              <ChatRow
                key={chat.roomId}
                chat={chat}
                ownUserId={ownUserId}
                selected={open?.id() === chat.roomId}
                onOpen={() => openChat(chat.room)}
                onMenu={(e) => {
                  e.preventDefault();
                  setMenu({ chat, x: e.clientX, y: e.clientY });
                }}
              />
            ))}
            {!searching && !showingArchived && sync.kind === 'ready' && (
              <EntryRow icon={archiveIcon} title={s('bipe_archived_title')} onOpen={() => setShowingArchived(true)} />
            )}
          </ul>
        </aside>
        <main className={`${showsDetail ? 'flex' : 'hidden md:flex'} min-h-0 min-w-0 flex-1 flex-col overflow-hidden`}>
          {detail?.kind === 'settings' ? (
            <Settings onClose={() => setDetail(undefined)} />
          ) : detail?.kind === 'requests' ? (
            <RequestsList
              requests={requests}
              onOpen={(r) => setDetail({ kind: 'request', roomId: r.roomId })}
              onBack={() => setDetail(undefined)}
            />
          ) : detail?.kind === 'request' && request ? (
            <RequestScreen
              key={request.roomId}
              request={request}
              onAccepted={() => openChat(request.room)}
              onDone={() => setDetail({ kind: 'requests' })}
              onBack={() => setDetail({ kind: 'requests' })}
            />
          ) : open ? (
            <RoomTimeline key={open.id()} room={open} onBack={() => setDetail(undefined)} />
          ) : (
            // A wide window with no chat open. The list beside it is the call to action, so no text here.
            <div className="grid flex-1 place-items-center bg-surface-base">
              <BipeMark className="size-16 text-ink-secondary" />
            </div>
          )}
        </main>
      </div>
      {menu && (
        <ChatMenu
          x={menu.x}
          y={menu.y}
          archived={archive.isArchived(menu.chat.roomId)}
          onArchive={(value) => archive.set(menu.chat.room, value, menu.chat.latest?.at)}
          onClose={() => setMenu(undefined)}
        />
      )}
    </ClientContext>
  );
}

// The two scopes as a segmented control, each with its badge (chat-list-filters).
function ScopePicker({
  scope,
  badges,
  onChange,
}: {
  scope: Scope;
  badges: Record<Scope, number>;
  onChange: (scope: Scope) => void;
}) {
  const item = (value: Scope, title: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={scope === value}
      className={`flex min-h-8 flex-1 items-center justify-center gap-1.5 rounded-[7px] text-subheadline font-semibold ${scope === value ? 'bg-surface-base text-ink-primary shadow-sm' : 'text-ink-secondary'}`}
      onClick={() => onChange(value)}
    >
      {title}
      {badges[value] > 0 && (
        <span className="rounded-full bg-accent-fill px-1.5 text-caption font-bold text-ink-on-accent">
          {badges[value].toLocaleString()}
        </span>
      )}
    </button>
  );
  return (
    <div role="tablist" className="mx-4 mb-2 flex shrink-0 gap-0.5 rounded-[9px] bg-surface-sunken p-0.5">
      {item('conversas', s('bipe_tab_chats'))}
      {item('grupos', s('bipe_tab_groups'))}
    </div>
  );
}

// ListEntryRow (ChatRequestsView.swift): an entry that is not a chat (Pedidos, Arquivadas): a 24 px icon
// in accent.ink on an accent.wash circle in the 48 px avatar footprint, the title, an optional detail
// and count.
function EntryRow({
  icon,
  title,
  detail,
  count,
  selected,
  onOpen,
}: {
  icon: string;
  title: string;
  detail?: string;
  count?: number;
  selected?: boolean;
  onOpen: () => void;
}) {
  return (
    <li>
      <button
        type="button"
        className={`flex w-full items-center gap-3 pl-4 text-left ${selected ? 'bg-accent-wash' : 'hover:bg-surface-raised'}`}
        onClick={onOpen}
      >
        <span className="grid size-12 shrink-0 place-items-center rounded-full bg-accent-wash text-accent-ink">
          <Icon path={icon} size={24} />
        </span>
        <span className="flex min-h-[72px] min-w-0 flex-1 items-center gap-2 border-b-[0.5px] border-line-subtle py-2 pr-4">
          <span className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="truncate text-headline text-ink-primary">{title}</span>
            {detail && <span className="truncate text-subheadline text-ink-secondary">{detail}</span>}
          </span>
          {count !== undefined && count > 0 && (
            <span className="rounded-full bg-accent-fill px-[7px] py-0.5 text-caption font-bold text-ink-on-accent">
              {count.toLocaleString()}
            </span>
          )}
        </span>
      </button>
    </li>
  );
}

// A group invite at the top of Grupos (chat-list-filters): who invited, Aceitar and Recusar.
function GroupInviteRow({ invite, onJoined }: { invite: ChatSummary; onJoined: (room: Room) => void }) {
  const [state, setState] = useState<'idle' | 'working' | 'error'>('idle');
  const who = requesterOf(invite);
  const act = (work: () => Promise<void>, after?: () => void) => {
    setState('working');
    work()
      .then(() => {
        setState('idle');
        after?.();
      })
      .catch((e: unknown) => {
        console.warn('[trace] group invite failed', describe(e));
        setState('error');
      });
  };
  return (
    <li className="flex items-start gap-3 border-b-[0.5px] border-line-subtle px-4 py-3">
      <Avatar name={invite.face.name} size={48} mxcUrl={invite.face.avatarUrl} />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="truncate text-headline text-ink-primary">{invite.face.name}</span>
        <span className="text-subheadline text-ink-secondary">
          {s('bipe_group_notification_room_invite_body_with_sender', who.name)}
        </span>
        {state === 'error' && <span className="text-footnote text-trust-danger-ink">{s('bipe_error_generic')}</span>}
        <div className="flex gap-2 pt-1">
          <button
            type="button"
            disabled={state === 'working'}
            className="min-h-9 rounded-full bg-accent-fill px-4 text-subheadline font-semibold text-ink-on-accent disabled:opacity-50"
            onClick={() =>
              act(
                () => invite.room.join(),
                () => onJoined(invite.room),
              )
            }
          >
            {s('bipe_app_request_accept')}
          </button>
          <button
            type="button"
            disabled={state === 'working'}
            className="min-h-9 rounded-full bg-surface-sunken px-4 text-subheadline font-semibold text-accent-ink disabled:opacity-50"
            onClick={() => act(() => invite.room.leave())}
          >
            {s('bipe_app_request_decline')}
          </button>
        </div>
      </div>
    </li>
  );
}

// The row's actions (right-click or the context menu key): Arquivar or Desarquivar (spec chat-archive).
function ChatMenu({
  x,
  y,
  archived,
  onArchive,
  onClose,
}: {
  x: number;
  y: number;
  archived: boolean;
  onArchive: (archived: boolean) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <>
      <button type="button" className="fixed inset-0 z-40 cursor-default" tabIndex={-1} aria-hidden onClick={onClose} />
      <div
        role="menu"
        aria-label={s('bipe_chat_actions')}
        className="fixed z-50 min-w-[200px] overflow-hidden rounded-[14px] bg-surface-raised py-1 shadow-lg ring-1 ring-line-subtle"
        style={{ left: Math.min(x, window.innerWidth - 216), top: Math.min(y, window.innerHeight - 64) }}
      >
        <button
          type="button"
          role="menuitem"
          autoFocus
          className="flex min-h-11 w-full items-center justify-between gap-4 px-4 text-left text-body text-ink-primary hover:bg-surface-sunken"
          onClick={() => {
            onClose();
            onArchive(!archived);
          }}
        >
          {archived ? s('bipe_unarchive_action') : s('bipe_archive_action')}
          <Icon path={archiveIcon} size={22} className="text-glyph-primary" />
        </button>
      </div>
    </>
  );
}

// ChatRow (ChatListView.swift): a 48 pt avatar, 12 pt gap; the name in headline with the date in caption
// on the first line; the preview in subheadline (two lines) with the muted bell, the @ mark and the
// unread count (or the marked-unread dot) on the second. The separator starts at the text.
function ChatRow({
  chat,
  ownUserId,
  selected,
  onOpen,
  onMenu,
}: {
  chat: ChatSummary;
  ownUserId: string;
  selected: boolean;
  onOpen: () => void;
  onMenu: (e: MouseEvent) => void;
}) {
  const { name, avatarUrl } = chat.face;
  const info = chat.info;
  const preview = rowPreview(chat.latest, chat.kind === 'dm', ownUserId);
  const unread = Number(info?.numUnreadMessages ?? 0);
  const mentions = Number(info?.numUnreadMentions ?? 0);
  const muted = info?.cachedUserDefinedNotificationMode === RoomNotificationMode.Mute;
  const count = unread.toLocaleString();
  return (
    <li>
      <button
        className={`flex w-full items-center gap-3 pl-4 text-left ${selected ? 'bg-accent-wash' : 'hover:bg-surface-raised'}`}
        aria-current={selected || undefined}
        onClick={onOpen}
        onContextMenu={onMenu}
      >
        <Avatar name={name} size={48} mxcUrl={avatarUrl} />
        <span className="flex min-h-[72px] min-w-0 flex-1 flex-col justify-center gap-1 border-b-[0.5px] border-line-subtle py-2 pr-4">
          <span className="flex items-baseline gap-2">
            <span className="min-w-0 flex-1 truncate text-headline text-ink-primary">{name}</span>
            {isOfficial(chat.face.peer?.userId) && <OfficialBadge />}
            {chat.latest && (
              <time
                className="shrink-0 text-caption text-ink-secondary tabular-nums"
                dateTime={new Date(chat.latest.at).toISOString()}
              >
                {rowTime(chat.latest.at)}
              </time>
            )}
          </span>
          <span className="flex items-center gap-2">
            <span className="line-clamp-2 min-w-0 flex-1 text-subheadline break-words text-ink-secondary">
              {preview ?? ''}
            </span>
            {muted && (
              <Icon
                path={bellSlash}
                size={16}
                label={s('bipe_app_notification_mode_off')}
                className="shrink-0 text-ink-secondary"
              />
            )}
            {unread > 0 ? (
              <>
                {mentions > 0 && (
                  <span
                    aria-hidden
                    className="grid size-5 shrink-0 place-items-center rounded-full bg-accent-fill text-caption font-bold text-ink-on-accent"
                  >
                    @
                  </span>
                )}
                <span
                  className="shrink-0 rounded-full bg-accent-fill px-[7px] py-0.5 text-caption font-bold text-ink-on-accent"
                  aria-label={
                    mentions > 0
                      ? `${s('bipe_app_chat_list_unread', count)}, ${s('bipe_a11y_mention')}`
                      : s('bipe_app_chat_list_unread', count)
                  }
                >
                  {count}
                </span>
              </>
            ) : (
              info?.isMarkedUnread && (
                <span
                  role="img"
                  aria-label={s('bipe_chat_unread_state')}
                  className="size-2 shrink-0 rounded-full bg-accent-fill"
                />
              )
            )}
          </span>
        </span>
      </button>
    </li>
  );
}

// The search field under the title (ChatSearchView, by name only on the web).
function SearchField({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <div className="mx-4 mb-2 flex h-9 shrink-0 items-center gap-1.5 rounded-[10px] bg-surface-sunken px-2 text-ink-secondary">
      <Icon path={magnifyingGlass} size={18} />
      <input
        id="chat-search"
        type="search"
        className="min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none placeholder:text-ink-secondary [&::-webkit-search-cancel-button]:hidden"
        placeholder={s('bipe_app_search_placeholder')}
        aria-label={s('bipe_app_search_placeholder')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => e.key === 'Escape' && onChange('')}
      />
      {value && (
        <button
          type="button"
          className="grid size-6 place-items-center"
          aria-label={s('bipe_a11y_clear_search')}
          onClick={() => onChange('')}
        >
          <Icon path={x} size={14} />
        </button>
      )}
    </div>
  );
}

/** Lower case without accents, so "joao" finds "João". */
export function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase();
}

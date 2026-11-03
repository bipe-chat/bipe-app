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

import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react';
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
import { useAccounts } from './accounts-context';
import { AccountSwitcher } from './AccountSwitcher';
import { DmDeleteAlert, NotifySheet } from './ChatActions';
import { markRead, markUnread, useClearedChats } from './chat-actions';
import { clearedChatHidden } from './bipe/generated/lib/registries.mjs';
import { NewChat, NewGroup } from './NewChat';
import {
  archive as archiveIcon,
  pencilSimple,
  bellSlash,
  briefcase,
  caretLeft,
  gear,
  magnifyingGlass,
  userCircle,
  x,
  bell,
  check,
  info as infoIcon,
  trash,
} from './phosphor/icons';
import { useDesktopNotifications, useNotificationPermission, useTabTitle } from './notifications';
import { bannerDismissed, EncryptionBanner, EncryptionSheet, needsApproval, useEncryptionState } from './Encryption';
import { isOfficial } from './registries';
import { navigate, useRoute } from './router';
import { useEscape } from './ui/keys';
import { rowPreview, rowTime } from './row-preview';
import { NavBar, OfficialBadge } from './ui/List';
import { RequestScreen, RequestsList, requesterOf } from './Requests';
import { ClientContext } from './media';
import { Settings } from './Settings';
import { businessClientFor } from './work/business-client';
import { workHostFor } from './work/host';
import { WorkView } from './work/WorkView';
import { RoomTimeline } from './Timeline';
import { Avatar, useIsCompany } from './ui/Avatar';
import { BipeMark } from './ui/BipeMark';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';
import { applyDiffs, describe, normalize, type DiffVariants } from './util';

type SyncState = { kind: 'starting' } | { kind: 'ready' } | { kind: 'failed'; message: string };
type Scope = 'conversas' | 'grupos';
type Detail =
  | { kind: 'room'; room: Room }
  | { kind: 'settings' }
  | { kind: 'work' }
  | { kind: 'requests' }
  | { kind: 'request'; roomId: string }
  | { kind: 'encryption' }
  | { kind: 'newChat' }
  | { kind: 'newGroup' };

export function Chats({ client }: { client: Client }) {
  const [rooms, setRooms] = useState<Room[]>([]);
  // The page comes from the URL (router.ts); the encryption sheet is an overlay without one.
  const route = useRoute();
  // Screens without a URL: the encryption sheet, Nova conversa and Novo grupo.
  const [overlay, setOverlay] = useState<'encryption' | 'newChat' | 'newGroup'>();
  // A new page (a link, back or forward) closes them.
  useEffect(() => setOverlay(undefined), [route]);
  const lastScope = useRef<Scope>('conversas');
  if (route.kind === 'list') lastScope.current = route.scope;
  const scope: Scope = route.kind === 'list' ? route.scope : lastScope.current;
  const showingArchived = route.kind === 'archived';
  const setScope = (next: Scope) => navigate({ kind: 'list', scope: next });
  const setShowingArchived = (on: boolean) => navigate(on ? { kind: 'archived' } : { kind: 'list', scope });
  const [menu, setMenu] = useState<{ chat: ChatSummary; x: number; y: number }>();
  const [query, setQuery] = useState('');
  const [sheet, setSheet] = useState<{ kind: 'notify' | 'delete'; chat: ChatSummary }>();
  const [clearedVersion, setClearedVersion] = useState(0);
  const clearedMarker = useClearedChats(client, clearedVersion);
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

  // A chat link opens any room this account has: a loaded row, or the SDK's room beyond the loaded page.
  const routeSummary = route.kind === 'chat' ? summaries.find((c) => c.roomId === route.roomId) : undefined;
  let routeRoom: Room | undefined = routeSummary?.room;
  if (route.kind === 'chat' && !routeRoom) {
    try {
      routeRoom = client.getRoom(route.roomId);
    } catch {
      routeRoom = undefined;
    }
  }
  const detail: Detail | undefined = overlay
    ? { kind: overlay }
    : route.kind === 'chat' && routeRoom
      ? { kind: 'room', room: routeRoom }
      : route.kind === 'settings'
        ? { kind: 'settings' }
        : route.kind === 'work'
          ? { kind: 'work' }
          : route.kind === 'requests'
            ? { kind: 'requests' }
            : route.kind === 'request'
              ? { kind: 'request', roomId: route.roomId }
              : undefined;
  const setDetail = (next: Detail | undefined) => {
    const kind = next?.kind;
    setOverlay(kind === 'encryption' || kind === 'newChat' || kind === 'newGroup' ? kind : undefined);
    if (kind === 'encryption' || kind === 'newChat' || kind === 'newGroup') return;
    if (!next) return navigate(showingArchived ? { kind: 'archived' } : { kind: 'list', scope });
    if (next.kind === 'room') return navigate({ kind: 'chat', roomId: next.room.id() });
    if (next.kind === 'request') return navigate({ kind: 'request', roomId: next.roomId });
    navigate({ kind: next.kind });
  };
  // A dead or wrong link goes to the right page without an error: an invite to its request, a room this
  // account does not have to the list.
  useEffect(() => {
    if (sync.kind !== 'ready') return;
    if (route.kind === 'chat') {
      if (routeSummary?.info && routeSummary.kind === 'request')
        navigate({ kind: 'request', roomId: route.roomId }, true);
      else if ((routeSummary?.info && routeSummary.kind === 'hidden') || !routeRoom)
        navigate({ kind: 'list', scope: 'conversas' }, true);
    } else if (route.kind === 'request') {
      const r = summaries.find((c) => c.roomId === route.roomId);
      if (r?.info && r.kind !== 'request') navigate({ kind: 'chat', roomId: route.roomId }, true);
    }
  }, [route, routeSummary, routeRoom, sync.kind, summaries]);
  const open = detail?.kind === 'room' ? detail.room : undefined;
  const openId = useRef<string>(undefined);
  openId.current = open?.id();
  const ownUserId = client.userId();
  const inScope = (c: ChatSummary) => (scope === 'conversas' ? c.kind === 'dm' : c.kind === 'group');
  // Spec dm-delete: a cleared DM stays hidden until a message newer than its marker arrives.
  const cleared = (c: ChatSummary) =>
    c.kind === 'dm' && clearedChatHidden(clearedMarker(c.roomId), c.latest?.at ?? null);
  const active = summaries.filter((c) => inScope(c) && !archive.isArchived(c.roomId) && !cleared(c));
  const archived = summaries.filter((c) => inScope(c) && archive.isArchived(c.roomId) && !cleared(c));
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

  const openChat = useCallback((room: Room) => {
    setOverlay(undefined);
    navigate({ kind: 'chat', roomId: room.id() });
  }, []);
  useDesktopNotifications(summaries, open?.id(), ownUserId, openChat);
  const page =
    detail?.kind === 'room'
      ? (routeSummary?.face.name ?? detail.room.displayName())
      : route.kind === 'requests' || route.kind === 'request'
        ? s('bipe_requests_title')
        : route.kind === 'archived'
          ? s('bipe_archived_title')
          : route.kind === 'settings'
            ? s('bipe_settings_section')
            : route.kind === 'work'
              ? s('bipe_tab_work')
              : undefined;
  useTabTitle(chatsBadge + groupsBadge, page);
  // The switcher shows each account's last known name, photo and unread badge (ADR 0225 point 4).
  const { remember, current: account } = useAccounts();
  // Company mode (ADR 0121 point kept by ADR 0225, ADR 0087): the account type from bipe-api.
  const isCompany = useIsCompany(client.userId());
  useEffect(() => remember({ isCompany }), [isCompany, remember]);
  useEffect(() => {
    if (sync.kind === 'ready') remember({ unread: chatsBadge + groupsBadge });
  }, [chatsBadge, groupsBadge, sync.kind, remember]);
  useEffect(() => {
    let stopped = false;
    Promise.all([client.displayName().catch(() => undefined), client.avatarUrl().catch(() => undefined)]).then(
      ([name, avatarUrl]) => !stopped && remember({ name: name ?? undefined, avatarUrl: avatarUrl ?? undefined }),
    );
    return () => {
      stopped = true;
    };
  }, [client, remember]);
  const [permission, askPermission] = useNotificationPermission();
  // Escape closes the settings and request screens (the chat info and viewers handle their own).
  useEscape(
    () => setDetail(undefined),
    detail?.kind === 'settings' ||
      detail?.kind === 'requests' ||
      detail?.kind === 'request' ||
      detail?.kind === 'encryption' ||
      detail?.kind === 'newChat' ||
      detail?.kind === 'newGroup',
  );
  // Keyboard: Cmd or Ctrl+K finds a chat; Alt+Up and Alt+Down open the chat above or below the open one.
  const listed = useRef<ChatSummary[]>([]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        // A narrow window shows the list first.
        if (window.matchMedia('(max-width: 767px)').matches) setDetail(undefined);
        requestAnimationFrame(() => document.getElementById('chat-search')?.focus());
      } else if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
        const list = listed.current;
        if (list.length === 0) return;
        e.preventDefault();
        const at = list.findIndex((c) => c.roomId === openId.current);
        const next = at === -1 ? 0 : Math.min(list.length - 1, Math.max(0, at + (e.key === 'ArrowUp' ? -1 : 1)));
        openChat(list[next].room);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [openChat]);
  const [bannerClosed, setBannerClosed] = useState(false);
  const encryption = useEncryptionState(client);
  const [encryptionDismissed, setEncryptionDismissed] = useState(bannerDismissed);
  const searching = query.trim() !== '';
  const needle = normalize(query.trim());
  // Search covers both scopes and archived chats; it opens any of them.
  const found = summaries.filter(
    (c) => (c.kind === 'dm' || c.kind === 'group') && normalize(c.face.name).includes(needle),
  );
  const rows = searching ? found : showingArchived ? archived : active;
  listed.current = rows;

  const rowActions = (chat: ChatSummary): MenuItem[] => {
    const info = chat.info;
    const unread = Number(info?.numUnreadMessages ?? 0) > 0 || info?.isMarkedUnread === true;
    const isArchived = archive.isArchived(chat.roomId);
    const items: MenuItem[] = [
      unread
        ? {
            label: s('bipe_app_notification_mark_read'),
            icon: check,
            run: () => void markRead(chat.room).catch(() => undefined),
          }
        : {
            label: s('bipe_chat_mark_unread'),
            icon: check,
            run: () => void markUnread(chat.room).catch(() => undefined),
          },
      { label: s('bipe_settings_notifications'), icon: bell, run: () => setSheet({ kind: 'notify', chat }) },
      {
        label: s('bipe_chat_details'),
        icon: infoIcon,
        run: () => navigate({ kind: 'chat', roomId: chat.roomId, info: true }),
      },
      {
        label: isArchived ? s('bipe_unarchive_action') : s('bipe_archive_action'),
        icon: archiveIcon,
        run: () => archive.set(chat.room, !isArchived, chat.latest?.at),
      },
    ];
    // A DM never leaves; its menu deletes (ADR 0141).
    if (chat.kind === 'dm')
      items.push({
        label: s('bipe_dm_delete_action'),
        icon: trash,
        danger: true,
        run: () => setSheet({ kind: 'delete', chat }),
      });
    return items;
  };

  return (
    <ClientContext value={client}>
      <div className="flex h-full min-h-0 overflow-hidden">
        <aside
          className={`${showsDetail ? 'hidden md:flex' : 'flex'} min-h-0 w-full min-w-0 flex-col overflow-hidden bg-surface-base md:w-[360px] md:shrink-0 md:border-r md:border-line-subtle`}
        >
          {/* The navigation bar: the toolbar row with its glass button, then the large title. */}
          <div className="flex h-11 shrink-0 items-center gap-2 px-2">
            <AccountSwitcher />
            {isCompany && (
              // The company's name in the top bar: the person sees which identity writes (ADR 0121).
              <span className="min-w-0 truncate text-headline text-ink-primary">{account.name}</span>
            )}
            <span className="flex-1" />
            {/* Nova conversa (the apps' create button): the person search, with Novo grupo at its top. */}
            <button
              className={`grid size-11 place-items-center rounded-full ${overlay === 'newChat' || overlay === 'newGroup' ? 'bg-accent-fill text-ink-on-accent' : 'bipe-glass text-glyph-primary'}`}
              aria-label={s('bipe_action_start_chat')}
              title={s('bipe_action_start_chat')}
              onClick={() => setDetail({ kind: 'newChat' })}
            >
              <Icon path={pencilSimple} size={22} />
            </button>
            {isCompany && (
              // Trabalho: the business tools of a company account on the web (ADR 0226).
              <button
                className={`grid size-11 place-items-center rounded-full ${detail?.kind === 'work' ? 'bg-accent-fill text-ink-on-accent' : 'bipe-glass text-glyph-primary'}`}
                aria-label={s('bipe_tab_work')}
                title={s('bipe_tab_work')}
                aria-pressed={detail?.kind === 'work'}
                onClick={() => navigate({ kind: 'work' })}
              >
                <Icon path={briefcase} size={22} />
              </button>
            )}
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
          {permission === 'default' && !bannerClosed && (
            // Desktop notifications need the person's yes, asked from this banner, never on load.
            <div className="mx-4 mb-2 flex items-start gap-3 rounded-xl bg-accent-wash p-3">
              <div className="flex min-w-0 flex-1 flex-col gap-2">
                <p className="text-subheadline font-semibold text-ink-primary">
                  {s('bipe_notifications_permission_title')}
                </p>
                <button
                  type="button"
                  className="min-h-9 self-start rounded-full bg-accent-fill px-4 text-subheadline font-semibold text-ink-on-accent"
                  onClick={askPermission}
                >
                  {s('bipe_notifications_prompt_allow')}
                </button>
              </div>
              <button
                type="button"
                className="grid size-8 place-items-center rounded-full text-glyph-secondary"
                aria-label={s('bipe_app_close')}
                onClick={() => setBannerClosed(true)}
              >
                <Icon path={x} size={14} />
              </button>
            </div>
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
            {!searching && !showingArchived && needsApproval(encryption) && !encryptionDismissed && (
              <EncryptionBanner
                onOpen={() => setDetail({ kind: 'encryption' })}
                onDismiss={() => setEncryptionDismissed(true)}
              />
            )}
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
          {detail?.kind === 'newChat' ? (
            <NewChat
              chats={summaries}
              onOpen={(roomId) => {
                setOverlay(undefined);
                navigate({ kind: 'chat', roomId });
              }}
              onNewGroup={() => setDetail({ kind: 'newGroup' })}
              onClose={() => setDetail(undefined)}
            />
          ) : detail?.kind === 'newGroup' ? (
            <NewGroup
              chats={summaries}
              onOpen={(roomId) => {
                setOverlay(undefined);
                navigate({ kind: 'chat', roomId });
              }}
              onClose={() => setDetail({ kind: 'newChat' })}
            />
          ) : detail?.kind === 'encryption' ? (
            <EncryptionSheet state={encryption} onClose={() => setDetail(undefined)} />
          ) : detail?.kind === 'work' ? (
            isCompany ? (
              <WorkView
                client={businessClientFor(client)}
                tool={route.kind === 'work' ? route.tool : undefined}
                onTool={(tool) => navigate({ kind: 'work', tool })}
                onClose={() => setDetail(undefined)}
                nameOf={(userId) => client.getProfile(userId).then((p) => p.displayName ?? undefined)}
                host={workHostFor(client, (roomId) => navigate({ kind: 'chat', roomId }))}
              />
            ) : (
              // A personal account, or bipe-api has not answered yet: only the way back.
              <NavBar title={s('bipe_tab_work')} onBack={() => setDetail(undefined)} />
            )
          ) : detail?.kind === 'settings' ? (
            <Settings
              group={route.kind === 'settings' ? route.group : undefined}
              onGroup={(group) => navigate({ kind: 'settings', group })}
              onClose={() => setDetail(undefined)}
            />
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
              contactNames={summaries.filter((c) => c.kind === 'dm').map((c) => c.face.name)}
              onAccepted={() => openChat(request.room)}
              onDone={() => setDetail({ kind: 'requests' })}
              onBack={() => setDetail({ kind: 'requests' })}
            />
          ) : open ? (
            <RoomTimeline
              key={open.id()}
              room={open}
              chats={summaries}
              showsInfo={route.kind === 'chat' && route.info === true}
              onInfo={(show) => navigate({ kind: 'chat', roomId: open.id(), info: show })}
              focusEventId={route.kind === 'chat' ? route.eventId : undefined}
              onBack={() => setDetail(undefined)}
            />
          ) : (
            // A wide window with no chat open. The list beside it is the call to action, so no text here.
            <div className="grid flex-1 place-items-center bg-surface-base">
              <BipeMark className="size-16 text-ink-secondary" />
            </div>
          )}
        </main>
      </div>
      {menu && <ChatMenu x={menu.x} y={menu.y} items={rowActions(menu.chat)} onClose={() => setMenu(undefined)} />}
      {sheet?.kind === 'notify' && (
        <NotifySheet room={sheet.chat.room} isDm={sheet.chat.kind === 'dm'} onClose={() => setSheet(undefined)} />
      )}
      {sheet?.kind === 'delete' && (
        <DmDeleteAlert
          room={sheet.chat.room}
          latestTs={sheet.chat.latest?.at ?? 0}
          onClose={() => setSheet(undefined)}
          onDone={() => {
            setSheet(undefined);
            setClearedVersion((v) => v + 1);
            if (open?.id() === sheet.chat.roomId) setDetail(undefined);
          }}
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
      <Avatar name={invite.face.name} size={48} mxcUrl={invite.face.avatarUrl} userId={invite.info?.inviter?.userId} />
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

// The row's actions (spec chat-row-actions; right-click or the context menu key, the long press of the apps).
type MenuItem = { label: string; icon: string; danger?: boolean; run: () => void };

function ChatMenu({ x, y, items, onClose }: { x: number; y: number; items: MenuItem[]; onClose: () => void }) {
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
        className="fixed z-50 min-w-[240px] overflow-hidden rounded-[14px] bg-surface-raised py-1 shadow-lg ring-1 ring-line-subtle"
        style={{
          left: Math.min(x, window.innerWidth - 256),
          top: Math.min(y, window.innerHeight - items.length * 44 - 16),
        }}
      >
        {items.map((item, i) => (
          <button
            key={item.label}
            type="button"
            role="menuitem"
            autoFocus={i === 0}
            className={`flex min-h-11 w-full items-center justify-between gap-4 px-4 text-left text-body hover:bg-surface-sunken ${item.danger ? 'text-trust-danger-ink' : 'text-ink-primary'}`}
            onClick={() => {
              onClose();
              item.run();
            }}
          >
            {item.label}
            <Icon path={item.icon} size={22} className={item.danger ? '' : 'text-glyph-primary'} />
          </button>
        ))}
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
        <Avatar name={name} size={48} mxcUrl={avatarUrl} userId={chat.face.peer?.userId} />
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

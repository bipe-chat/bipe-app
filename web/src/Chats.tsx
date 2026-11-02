// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The chat list and the open chat (ChatListView.swift). A wide window (768 px and up) shows them side
// by side, like NavigationSplitView on iPad: the list column is 360 px (300 to 420 on iOS), the
// selected row is accent.wash, and with no chat open the detail shows the Bipe mark. A narrow window
// shows one at a time, with a back button in the chat.

import { useEffect, useRef, useState } from 'react';
import {
  RoomListEntriesDynamicFilterKind,
  RoomListEntriesUpdate,
  type ClientLike as Client,
  type RoomLike as Room,
  type RoomListEntriesWithDynamicAdaptersResultLike as RoomListEntriesWithDynamicAdaptersResult,
  type SyncServiceLike as SyncService,
} from './generated/matrix_sdk_ffi';
import { gear } from './phosphor/icons';
import { ClientContext } from './media';
import { useRoomFace } from './room-face';
import { Settings } from './Settings';
import { RoomTimeline } from './Timeline';
import { Avatar } from './ui/Avatar';
import { BipeMark } from './ui/BipeMark';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';
import { applyDiffs, describe, type DiffVariants } from './util';

type SyncState = { kind: 'starting' } | { kind: 'ready' } | { kind: 'failed'; message: string };

export function Chats({ client }: { client: Client }) {
  const [rooms, setRooms] = useState<Room[]>([]);
  const [open, setOpen] = useState<Room>();
  const [settingsOpen, setSettingsOpen] = useState(false);
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
      entries.controller().setFilter(new RoomListEntriesDynamicFilterKind.NonLeft());
      console.info(`[trace] signed in as ${client.userId()}`);
      setSync({ kind: 'ready' });
    })().catch((e) => setSync({ kind: 'failed', message: describe(e) }));
    return () => {
      stopped = true;
      void keep.current.sync?.stop();
    };
  }, [client]);

  return (
    <ClientContext value={client}>
      <div className="flex h-full">
        <aside
          className={`${open || settingsOpen ? 'hidden md:flex' : 'flex'} w-full min-w-0 flex-col bg-surface-base md:w-[360px] md:shrink-0 md:border-r md:border-line-subtle`}
        >
          {/* The navigation bar: the toolbar row with its glass button, then the large title. */}
          <div className="flex h-11 shrink-0 items-center justify-end px-2">
            {/* Settings for this browser; sign out lives there, in Sua conta. */}
            <button
              className={`grid size-11 place-items-center rounded-full ${settingsOpen ? 'bg-accent-fill text-ink-on-accent' : 'bipe-glass text-glyph-primary'}`}
              aria-label={s('bipe_settings_section')}
              title={s('bipe_settings_section')}
              aria-pressed={settingsOpen}
              onClick={() => {
                setSettingsOpen(true);
                setOpen(undefined);
              }}
            >
              <Icon path={gear} size={22} />
            </button>
          </div>
          <h1 className="shrink-0 px-4 pb-2 text-large-title text-ink-primary">{s('bipe_app_chat_list_title')}</h1>
          {sync.kind === 'failed' && (
            <div className="px-4 py-2">
              <p className="text-subheadline text-trust-danger-ink">{s('bipe_error_generic')}</p>
              <p className="text-footnote break-words text-ink-secondary">{sync.message}</p>
            </div>
          )}
          {rooms.length === 0 ? (
            <div className="grid flex-1 place-items-center px-4 py-12 text-center">
              {sync.kind === 'starting' ? (
                <Spinner />
              ) : (
                sync.kind === 'ready' && <p className="text-ink-secondary">{s('bipe_app_chat_list_empty')}</p>
              )}
            </div>
          ) : (
            <ul className="flex-1 overflow-y-auto">
              {rooms.map((room) => (
                <ChatRow
                  key={room.id()}
                  room={room}
                  selected={open?.id() === room.id()}
                  onOpen={() => {
                    setSettingsOpen(false);
                    setOpen(room);
                  }}
                />
              ))}
            </ul>
          )}
        </aside>
        <main className={`${open || settingsOpen ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col`}>
          {settingsOpen ? (
            <Settings onClose={() => setSettingsOpen(false)} />
          ) : open ? (
            <RoomTimeline key={open.id()} room={open} onBack={() => setOpen(undefined)} />
          ) : (
            // A wide window with no chat open. The list beside it is the call to action, so no text here.
            <div className="grid flex-1 place-items-center bg-surface-base">
              <BipeMark className="size-16 text-ink-secondary" />
            </div>
          )}
        </main>
      </div>
    </ClientContext>
  );
}

// ChatRow (ChatListView.swift): a 48 pt avatar, 12 pt gap, the name in headline. The separator starts
// at the text, not at the avatar. TODO: the last message, its time and the unread badge need room info
// from the SDK (latestEvent, roomInfo) that the web client does not read yet.
function ChatRow({ room, selected, onOpen }: { room: Room; selected: boolean; onOpen: () => void }) {
  const { name, avatarUrl } = useRoomFace(room);
  return (
    <li>
      <button
        className={`flex w-full items-center gap-3 pl-4 text-left ${selected ? 'bg-accent-wash' : 'hover:bg-surface-raised'}`}
        aria-current={selected || undefined}
        onClick={onOpen}
      >
        <Avatar name={name} size={48} mxcUrl={avatarUrl} />
        <span className="flex min-h-[72px] min-w-0 flex-1 items-center border-b-[0.5px] border-line-subtle pr-4">
          <span className="truncate text-headline text-ink-primary">{name}</span>
        </span>
      </button>
    </li>
  );
}

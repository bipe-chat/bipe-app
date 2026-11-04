// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Search in one chat (spec chat-search with roomId, bipe-chat/bipe#861) and the floating date with jump to a
// date (bipe-app PR #295, bipe-chat/bipe#828, spec chat-timeline floatingDate and jumpToDate).
//
// The apps search with the SDK's local search index (Client.searchService); the wasm bindings have no search
// service, so the web searches the messages it has loaded and, while fewer than 20 match, loads older pages,
// at most 5 after each new query, as the spec does with SearchService.paginate.

import { useEffect, useRef, useState } from 'react';
import type { ClientLike as Client } from './generated/matrix_sdk_ffi';
import type { MessageRow, Row } from './TimelineRows';
import { magnifyingGlass, x } from './phosphor/icons';
import { Avatar } from './ui/Avatar';
import { dayText, s, timeText } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';
import { normalize } from './util';

const ENOUGH = 20;
const MAX_PAGES = 5;

export function ChatSearch({
  rows,
  loadMore,
  onOpen,
  onClose,
}: {
  rows: Row[];
  /** Loads one older page; false when the chat starts there. */
  loadMore: () => Promise<boolean>;
  onOpen: (eventId: string) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const pages = useRef(0);
  const needle = normalize(query.trim());
  const hits = needle
    ? rows
        .filter((r): r is MessageRow => r.kind === 'message' && r.body === 'text' && r.eventId !== undefined)
        .filter((r) => normalize(r.text).includes(needle))
        .reverse()
    : [];
  useEffect(() => {
    pages.current = 0;
  }, [needle]);
  useEffect(() => {
    if (!needle || hits.length >= ENOUGH || pages.current >= MAX_PAGES || loading) return;
    pages.current++;
    setLoading(true);
    void loadMore()
      .catch(() => false)
      .then((more) => {
        if (!more) pages.current = MAX_PAGES;
      })
      .finally(() => setLoading(false));
  }, [needle, hits.length, loading, loadMore]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="relative z-20 flex max-h-[60%] shrink-0 flex-col border-b-[0.5px] border-line-subtle bg-surface-base">
      <div className="flex items-center gap-2 px-3 py-2">
        <div className="flex h-9 min-w-0 flex-1 items-center gap-1.5 rounded-[10px] bg-surface-sunken px-2 text-ink-secondary">
          <Icon path={magnifyingGlass} size={18} />
          <input
            type="search"
            autoFocus
            className="min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none placeholder:text-ink-secondary"
            placeholder={s('bipe_search_in_chat')}
            aria-label={s('bipe_search_in_chat')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {loading && <Spinner />}
        </div>
        <button
          type="button"
          className="grid size-9 place-items-center rounded-full text-glyph-secondary"
          aria-label={s('bipe_app_close')}
          onClick={onClose}
        >
          <Icon path={x} size={16} />
        </button>
      </div>
      {needle && (
        <ul className="min-h-0 overflow-y-auto pb-2">
          {hits.map((r) => (
            <li key={r.key}>
              <button
                type="button"
                className="flex w-full items-start gap-2.5 px-4 py-2 text-left hover:bg-surface-raised"
                onClick={() => onOpen(r.eventId!)}
              >
                <Avatar name={r.who} size={32} />
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="flex items-baseline gap-2">
                    <span className="min-w-0 flex-1 truncate text-subheadline font-semibold text-ink-primary">
                      {r.own ? s('bipe_app_you') : r.who}
                    </span>
                    <span className="shrink-0 text-caption text-ink-secondary">
                      {dayText(r.at)} {timeText(r.at)}
                    </span>
                  </span>
                  <span className="line-clamp-2 text-subheadline text-ink-secondary">{r.text}</span>
                </span>
              </button>
            </li>
          ))}
          {!loading && hits.length === 0 && (
            <li className="px-6 py-6 text-center text-ink-secondary">
              {s('bipe_app_search_no_results', query.trim())}
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

/**
 * Jump to a date: the first event of the day from the homeserver (GET /_matrix/client/v1/rooms/{roomId}/
 * timestamp_to_event?dir=f, MSC3030). matrix-sdk-ffi has no call for it, so the web uses the session's access
 * token, as the apps' ServerAccountData does. Returns the event ID, or undefined when the day has none.
 */
export async function firstEventOfDay(
  client: Client,
  roomId: string,
  day: Date,
  clearedMarker: number | null,
): Promise<string | undefined> {
  const session = client.session();
  const start = new Date(day.getFullYear(), day.getMonth(), day.getDate()).getTime();
  // Spec dm-delete: a cleared DM has no events at or below its marker, so the search starts after it.
  const ts = clearedMarker === null ? start : Math.max(start, clearedMarker + 1);
  const base = session.homeserverUrl.replace(/\/$/, '');
  const res = await fetch(
    `${base}/_matrix/client/v1/rooms/${encodeURIComponent(roomId)}/timestamp_to_event?ts=${ts}&dir=f`,
    { headers: { Authorization: `Bearer ${session.accessToken}` } },
  );
  if (!res.ok) return undefined;
  const body = (await res.json()) as { event_id?: string; origin_server_ts?: number };
  if (!body.event_id || body.origin_server_ts === undefined) return undefined;
  // An event on a later day means the picked day had none.
  const end = start + 86_400_000;
  return body.origin_server_ts < end ? body.event_id : undefined;
}

// The floating date: while the list scrolls, a pill at the top shows the day of the top row on screen; it
// fades out 1 s after scrolling stops (no fade under reduced motion). The pill is a button that opens a date
// field up to today; a picked day jumps to its first message.
export function FloatingDate({
  at,
  visible,
  onPick,
  notice,
}: {
  at: number | undefined;
  visible: boolean;
  onPick: (day: Date) => void;
  notice: string;
}) {
  const field = useRef<HTMLInputElement>(null);
  const today = new Date();
  const max = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  return (
    <div className="pointer-events-none absolute top-[60px] right-0 left-0 z-10 flex flex-col items-center gap-1">
      {at !== undefined && (
        <button
          type="button"
          className={`pointer-events-auto rounded-full bg-surface-sunken px-2.5 py-1 text-caption text-ink-secondary shadow-sm motion-safe:transition-opacity motion-safe:duration-300 ${visible ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
          aria-label={`${dayText(at)}, ${s('bipe_jump_to_date_title')}`}
          onClick={() => field.current?.showPicker?.() ?? field.current?.focus()}
        >
          {dayText(at)}
        </button>
      )}
      <input
        ref={field}
        type="date"
        max={max}
        className="sr-only"
        tabIndex={-1}
        aria-hidden
        onChange={(e) => {
          const [y, m, d] = e.target.value.split('-').map(Number);
          if (y && m && d) onPick(new Date(y, m - 1, d));
          e.target.value = '';
        }}
      />
      {notice && (
        <p className="rounded-full bg-surface-raised px-3 py-1.5 text-subheadline text-ink-primary shadow">{notice}</p>
      )}
    </div>
  );
}

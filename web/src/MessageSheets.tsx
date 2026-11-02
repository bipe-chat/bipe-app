// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The sheets behind the message menu: Encaminhar (spec message-forward, MessageForward.swift), Mais reações
// (spec message-reactions openPicker) and the list of who reacted (openSenders, MessageSheets.swift).

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { forwardChatLimit, forwardCount, forwardedContent } from './bipe/generated/lib/registries.mjs';
import { voiceMedia } from './bipe/generated/registries.json';
import type { ChatSummary } from './chat-model';
import type { RoomLike as Room } from './generated/matrix_sdk_ffi';
import { check, magnifyingGlass } from './phosphor/icons';
import { Avatar } from './ui/Avatar';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';
import { normalize } from './util';

const reg = { voiceMedia };

/** A sheet over the chat: the dim, a card with a title bar, Escape and a click outside close it. */
function Sheet({
  title,
  onClose,
  children,
  footer,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    // The dim behind a sheet is black at 40% in both schemes, as the system draws it; no token covers it.
    <div className="fixed inset-0 z-40 grid place-items-center bg-black/40 md:p-6" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="flex h-full w-full max-w-[440px] flex-col bg-surface-base md:h-auto md:max-h-[80vh] md:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="grid h-[52px] shrink-0 grid-cols-[1fr_auto_1fr] items-center px-2">
          <button
            type="button"
            className="min-h-11 justify-self-start px-2 text-body text-accent-ink"
            onClick={onClose}
          >
            {s('bipe_app_cancel')}
          </button>
          <h2 className="text-headline text-ink-primary">{title}</h2>
          <span />
        </div>
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">{children}</div>
        {footer}
      </div>
    </div>
  );
}

/** The content a forward of this event sends, or undefined when the event JSON is not there (a local echo). */
export function forwardContentOf(originalJson: string | undefined, latestEditJson: string | undefined) {
  if (!originalJson) return undefined;
  const original = (JSON.parse(originalJson) as { content?: Record<string, unknown> }).content ?? {};
  const edit = latestEditJson
    ? ((JSON.parse(latestEditJson) as { content?: Record<string, unknown> }).content?.['m.new_content'] as
        Record<string, unknown> | undefined)
    : undefined;
  return forwardedContent(original, edit, reg) as Record<string, unknown>;
}

/** The forward count of an event's content, for the forward line. */
export function forwardCountOf(originalJson: string | undefined): number {
  if (!originalJson) return 0;
  try {
    return forwardCount((JSON.parse(originalJson) as { content?: unknown }).content, reg);
  } catch {
    return 0;
  }
}

// Spec message-forward: the joined chats by name, a search, at most forwardChats picks (forwardChatsManyTimes
// for a much-forwarded message), Encaminhar sends Room.sendRaw m.room.message with the content of
// forwardedContent (the chat.bipe.forward mark, no reply, no mentions); media keep their source.
export function ForwardSheet({
  content,
  chats,
  onClose,
  onDone,
}: {
  content: Record<string, unknown>;
  chats: ChatSummary[];
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [warning, setWarning] = useState('');
  const [sending, setSending] = useState(false);
  const limit = forwardChatLimit([forwardCount(content, reg)], reg);
  const sorted = useMemo(
    () =>
      chats.filter((c) => c.kind === 'dm' || c.kind === 'group').sort((a, b) => a.face.name.localeCompare(b.face.name)),
    [chats],
  );
  const needle = normalize(query.trim());
  const shown = sorted.filter((c) => normalize(c.face.name).includes(needle));
  const toggle = (roomId: string) => {
    setWarning('');
    if (picked.includes(roomId)) return setPicked(picked.filter((id) => id !== roomId));
    if (picked.length >= limit) {
      return setWarning(
        limit === voiceMedia.forwardChatsManyTimes && limit < voiceMedia.forwardChats
          ? s('bipe_forward_limit_many')
          : s('bipe_forward_limit', String(limit)),
      );
    }
    setPicked([...picked, roomId]);
  };
  const send = async () => {
    setSending(true);
    const failed: string[] = [];
    const body = JSON.stringify(content);
    for (const id of picked) {
      const chat = sorted.find((c) => c.roomId === id);
      if (!chat) continue;
      try {
        await chat.room.sendRaw('m.room.message', body);
      } catch (e) {
        console.warn('[trace] forward failed', id, e);
        failed.push(chat.face.name);
      }
    }
    setSending(false);
    if (failed.length > 0) setWarning(failed.map((name) => s('bipe_forward_failed', name)).join(' '));
    else {
      onDone(s('bipe_forward_done'));
      onClose();
    }
  };
  return (
    <Sheet
      title={s('bipe_forward_title')}
      onClose={onClose}
      footer={
        <div className="flex shrink-0 flex-col gap-2 border-t-[0.5px] border-line-subtle p-3">
          {warning && <p className="text-footnote text-trust-danger-ink">{warning}</p>}
          <button
            type="button"
            disabled={picked.length === 0 || sending}
            className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent disabled:opacity-50"
            onClick={() => void send()}
          >
            {sending ? <Spinner /> : s('bipe_forward_action')}
          </button>
        </div>
      }
    >
      <div className="mx-4 my-2 flex h-9 shrink-0 items-center gap-1.5 rounded-[10px] bg-surface-sunken px-2 text-ink-secondary">
        <Icon path={magnifyingGlass} size={18} />
        <input
          type="search"
          autoFocus
          className="min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none placeholder:text-ink-secondary"
          placeholder={s('bipe_app_search_placeholder')}
          aria-label={s('bipe_app_search_placeholder')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      <ul>
        {shown.map((c) => {
          const on = picked.includes(c.roomId);
          return (
            <li key={c.roomId}>
              <button
                type="button"
                role="checkbox"
                aria-checked={on}
                className="flex w-full items-center gap-3 px-4 py-1.5 text-left hover:bg-surface-raised"
                onClick={() => toggle(c.roomId)}
              >
                <Avatar name={c.face.name} size={40} mxcUrl={c.face.avatarUrl} userId={c.face.peer?.userId} />
                <span className="min-w-0 flex-1 truncate text-body text-ink-primary">
                  {c.face.name || s('bipe_dm_no_name')}
                </span>
                {/* A picked chat shows a check mark, not only a colour. */}
                <span
                  className={`grid size-6 shrink-0 place-items-center rounded-full border ${on ? 'border-accent-fill bg-accent-fill text-ink-on-accent' : 'border-line-strong'}`}
                >
                  {on && <Icon path={check} size={14} />}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </Sheet>
  );
}

type EmojiGroup = { name: string; emojis: { emoji: string; name: string }[] };

// Mais reações: the apps open the system emoji picker; a browser has none, so the web draws one from the
// Unicode emoji data of unicode-emoji-json (MIT, Mu-An Chiou), loaded only when the picker opens. Groups
// show as their first emoji, so no English group names reach the screen; the search matches the English
// CLDR short names of the data set.
export function EmojiPicker({ onPick, onClose }: { onPick: (emoji: string) => void; onClose: () => void }) {
  const [groups, setGroups] = useState<EmojiGroup[]>();
  const [group, setGroup] = useState(0);
  const [query, setQuery] = useState('');
  useEffect(() => {
    let stopped = false;
    void import('unicode-emoji-json/data-by-group.json').then((m) => {
      if (!stopped) setGroups((m.default as EmojiGroup[]).filter((g) => g.emojis.length > 0));
    });
    return () => {
      stopped = true;
    };
  }, []);
  const needle = query.trim().toLowerCase();
  const shown = !groups
    ? []
    : needle
      ? groups.flatMap((g) => g.emojis.filter((e) => e.name.includes(needle)))
      : groups[group].emojis;
  return (
    <Sheet title={s('bipe_app_message_action_more_reactions')} onClose={onClose}>
      <div className="mx-4 my-2 flex h-9 shrink-0 items-center gap-1.5 rounded-[10px] bg-surface-sunken px-2 text-ink-secondary">
        <Icon path={magnifyingGlass} size={18} />
        <input
          type="search"
          autoFocus
          className="min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none placeholder:text-ink-secondary"
          placeholder={s('bipe_web_emoji_search')}
          aria-label={s('bipe_web_emoji_search')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {!groups ? (
        <div className="grid place-items-center py-12">
          <Spinner />
        </div>
      ) : (
        <>
          {!needle && (
            <div role="tablist" className="flex shrink-0 gap-1 overflow-x-auto px-3 pb-2">
              {groups.map((g, i) => (
                <button
                  key={g.name}
                  type="button"
                  role="tab"
                  aria-selected={i === group}
                  aria-label={g.emojis[0].emoji}
                  className={`grid size-9 shrink-0 place-items-center rounded-full text-[20px] ${i === group ? 'bg-accent-wash' : 'hover:bg-surface-sunken'}`}
                  onClick={() => setGroup(i)}
                >
                  {g.emojis[0].emoji}
                </button>
              ))}
            </div>
          )}
          <div className="grid grid-cols-8 gap-0.5 px-3 pb-3">
            {shown.map((e) => (
              <button
                key={e.emoji}
                type="button"
                title={e.name}
                className="grid aspect-square place-items-center rounded-lg text-[26px] hover:bg-surface-sunken"
                onClick={() => {
                  onPick(e.emoji);
                  onClose();
                }}
              >
                {e.emoji}
              </button>
            ))}
          </div>
        </>
      )}
    </Sheet>
  );
}

// openSenders (MessageSheets.swift): who reacted with one key, newest first, with photos; the account as Você.
export function ReactionSenders({
  room,
  reactionKey,
  senders,
  onClose,
}: {
  room: Room;
  reactionKey: string;
  senders: { userId: string; at: number }[];
  onClose: () => void;
}) {
  const own = room.ownUserId();
  const [people, setPeople] = useState<{ userId: string; name: string; avatarUrl?: string }[]>();
  useEffect(() => {
    let stopped = false;
    const ordered = [...senders].sort((a, b) => b.at - a.at);
    Promise.all(
      ordered.map(async ({ userId }) => {
        const member = await room.member(userId).catch(() => undefined);
        return { userId, name: member?.displayName ?? userId, avatarUrl: member?.avatarUrl };
      }),
    ).then((list) => !stopped && setPeople(list));
    return () => {
      stopped = true;
    };
  }, [room, senders]);
  return (
    <Sheet title={`${s('bipe_app_reactions_title')} ${reactionKey}`} onClose={onClose}>
      {!people ? (
        <div className="grid place-items-center py-12">
          <Spinner />
        </div>
      ) : (
        <ul className="py-1">
          {people.map((p) => (
            <li key={p.userId} className="flex items-center gap-3 px-4 py-1.5">
              <Avatar name={p.name} size={32} mxcUrl={p.avatarUrl} userId={p.userId} />
              <span className="min-w-0 flex-1 truncate text-body text-ink-primary">
                {p.userId === own ? s('bipe_app_you') : p.name}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Sheet>
  );
}

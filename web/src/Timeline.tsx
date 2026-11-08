// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// One chat: the inline bar, the timeline over the chat background, the composer
// (ChatTimelineView.swift, TimelineRowView.swift; specs chat-timeline, timeline-rows, composer).
// The column is at most 760 px wide, centred, like the frame of ChatTimelineView.

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  EditedContent,
  ReceiptType,
  TimelineDiff,
  messageEventContentFromMarkdown,
  type RoomLike as Room,
  type TaskHandleLike as TaskHandle,
  type TimelineLike as Timeline,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import {
  arrowBendUpLeft,
  magnifyingGlass,
  caretLeft,
  caretRight,
  check,
  paperPlaneRight,
  pencilSimple,
  x,
} from './phosphor/icons';
import { AttachButton, MAX_ITEMS, UploadPreview } from './Attach';
import { ChatInfo } from './ChatInfo';
import { BusinessChatBar, BusinessChatSheets, BusinessToolButtons } from './work/chat/BusinessChat';
import { useBusinessChat } from './work/chat/useBusinessChat';
import {
  TakenReplyBar,
  useReplyByName,
  useReplyBySender,
  useWorkChat,
  WorkChatBar,
  WorkNotesSheet,
} from './work/chat/WorkChatBar';
import { PinnedBanner, usePinnedMessages } from './Pins';
import { useRoomFace } from './room-face';
import { itemRow, markRead, TimelineRowView, withoutEmptyDays, type MessageRow, type RowHandlers } from './TimelineRows';
import { Alert } from './ui/Alert';
import { useAccounts } from './accounts-context';
import { Avatar, useIsCompany } from './ui/Avatar';
import { ChatBackground } from './ui/ChatBackground';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import {
  contentWithMentions,
  insertMention,
  mentionQuery,
  mentionsIn,
  useMentionDirectory,
  type ComposerMention,
  type MentionSuggestion,
} from './mentions';
import type { ChatSummary } from './chat-model';
import { EmojiPicker, ForwardSheet, forwardContentOf, ReactionSenders } from './MessageSheets';
import type { ReactionPill } from './Reactions';
import { messageLink, navigate } from './router';
import { readReceiptsOn } from './Settings';
import { ChatSearch, firstEventOfDay, FloatingDate } from './ChatSearch';
import { BlockAlert } from './ChatActions';
import { useClearedChats, useIgnoredUsers } from './chat-actions';
import { clearedChatKeepsEvent } from './bipe/generated/lib/registries.mjs';
import { useClient } from './media';
import { useIsNewsRoom } from './news-room';
import { isOfficial } from './registries';
import { useSenderFacts } from './sender-facts';
import { ChatOfficialBadge } from './ui/ChatOfficialBadge';
import { OfficialBadge } from './ui/List';
import { applyDiffs, describe, type DiffVariants } from './util';

// The composer's mode (specs message-reply, message-edit): a reply bar or an edit bar above the field.
type Mode = { kind: 'reply'; row: MessageRow } | { kind: 'edit'; row: MessageRow } | undefined;

export function RoomTimeline({
  room,
  chats,
  showsInfo,
  onInfo,
  focusEventId,
  onBack,
}: {
  room: Room;
  chats: ChatSummary[];
  showsInfo: boolean;
  onInfo: (show: boolean) => void;
  focusEventId?: string;
  onBack: () => void;
}) {
  // The sheets of the message menu: forward, the emoji picker, who reacted.
  const [sheet, setSheet] = useState<
    | { kind: 'forward'; content: Record<string, unknown> }
    | { kind: 'emoji'; row: MessageRow }
    | { kind: 'senders'; pill: ReactionPill }
  >();
  const [items, setItems] = useState<TimelineItem[]>([]);
  const [draft, setDraft] = useState('');
  const [note, setNote] = useState('');
  // Files picked, pasted or dropped, waiting in the upload preview.
  const [pending, setPending] = useState<File[]>();
  const [dropping, setDropping] = useState(false);
  const [mode, setMode] = useState<Mode>();
  const [mentions, setMentions] = useState<ComposerMention[]>([]);
  const [suggestionsHidden, setSuggestionsHidden] = useState(false);
  const [deleting, setDeleting] = useState<MessageRow>();
  const [toast, setToast] = useState('');
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 2000);
    return () => clearTimeout(timer);
  }, [toast]);
  const timeline = useRef<Timeline>(undefined);
  const listener = useRef<TaskHandle>(undefined);
  const scroller = useRef<HTMLDivElement>(null);
  // Action loadHistory (ChatTimelineView historyHeader): one page at a time, until the start of the chat.
  const history = useRef({ loading: false, reachedStart: false });
  const [loadingHistory, setLoadingHistory] = useState(false);
  const loadHistory = useCallback(async () => {
    const t = timeline.current;
    if (!t || history.current.loading || history.current.reachedStart) return;
    history.current.loading = true;
    setLoadingHistory(true);
    try {
      history.current.reachedStart = await t.paginateBackwards(30);
    } finally {
      history.current.loading = false;
      setLoadingHistory(false);
    }
  }, []);
  // The column is reversed: scrollTop is 0 at the newest message and negative above it.
  const [atBottom, setAtBottom] = useState(true);
  const [searching, setSearching] = useState(false);
  const [floating, setFloating] = useState<{ at?: number; visible: boolean }>({ visible: false });
  const floatTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const [jumpNotice, setJumpNotice] = useState('');
  useEffect(() => {
    if (!jumpNotice) return;
    const t = setTimeout(() => setJumpNotice(''), 2500);
    return () => clearTimeout(t);
  }, [jumpNotice]);
  const [missed, setMissed] = useState(0);

  useEffect(() => {
    let stopped = false;
    (async () => {
      const t = await room.timeline();
      timeline.current = t;
      listener.current = await t.addListener({
        onUpdate(diffs) {
          if (!stopped) setItems((prev) => applyDiffs(prev, diffs, TimelineDiff as unknown as DiffVariants));
        },
      });
      await loadHistory();
    })().catch((e) => setNote(describe(e)));
    return () => {
      stopped = true;
      listener.current?.cancel();
    };
  }, [room]);

  const ownUserId = room.ownUserId();
  const face = useRoomFace(room);
  // Group rows show the sender's name; DM rows do not. Until RoomInfo comes, no names.
  const isDm = face.isDm ?? true;
  const client = useClient();
  const business = useBusinessChat(client, face.isDirect ? face.peer?.userId : undefined, face.name);
  const clearedMarker = useClearedChats(client);
  // Spec dm-delete: the timeline drops every event at or below the cleared marker of the DM.
  const marker = clearedMarker(room.id());
  const itemRows = withoutEmptyDays(
    items
      .map((i) => itemRow(i, ownUserId))
      .filter((r) => r !== undefined)
      .filter((r) => r.kind !== 'message' || clearedChatKeepsEvent(marker, r.at)),
  );
  // Read ticks only in a DM (spec read-receipts); groups keep the sent tick.
  // With the read receipts switch off, DMs show no read ticks either (bipe_privacy_read_receipts_footer).
  const rows = face.isDirect && readReceiptsOn() ? markRead(itemRows) : itemRows;
  const senders = useSenderFacts(room, isDm, rows);
  const typing = useTyping(room, ownUserId);
  // Spec message-pins: groups only, with RoomPowerLevels.canOwnUserPinUnpin.
  const isGroup = face.isDirect === false;
  const pins = usePinnedMessages(room, isGroup);
  const pinnedIds = new Set(pins.map((p) => p.eventId));
  const [canPin, setCanPin] = useState(false);
  useEffect(() => {
    if (!isGroup) return setCanPin(false);
    let stopped = false;
    room
      .getPowerLevels()
      .then((levels) => !stopped && setCanPin(levels.canOwnUserPinUnpin()))
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [room, isGroup]);
  const ignored = useIgnoredUsers(client);
  const blocked = face.isDirect === true && face.peer !== undefined && ignored.has(face.peer.userId);
  const [unblocking, setUnblocking] = useState(false);
  const account = useAccounts().current;
  const isCompany = useIsCompany(ownUserId);
  const companyName = isCompany ? (account.name ?? undefined) : undefined;
  // Specs work-chat-bar, work-notes (bipe ADR 0284 phase A): a DM of a work account is a customer chat. All of it shows
  // only with two or more people.
  const customerChat = face.isDirect === true && face.peer !== undefined && face.peer.userId !== ownUserId;
  const work = useWorkChat(client, room.id(), isCompany, customerChat);
  useReplyBySender(client, room.id(), items, work);
  const replyByName = useReplyByName(client, room.id(), work);
  const [notesOpen, setNotesOpen] = useState(false);
  // Responder mesmo assim: once per open chat.
  const [replyAnyway, setReplyAnyway] = useState(false);
  const claiming = useRef(false);
  // A message link (/c/<room>/e/<event>): load older pages until the message is there, then bring it into
  // view and mark it for a moment, as the reply quote jump does.
  const focused = useRef<string>(undefined);
  useEffect(() => {
    if (!focusEventId || focused.current === focusEventId) return;
    const el = document.getElementById(`ev-${focusEventId}`);
    if (el) {
      focused.current = focusEventId;
      el.scrollIntoView({ block: 'center' });
      el.dataset.highlight = 'true';
      setTimeout(() => delete el.dataset.highlight, 2000);
    } else if (!history.current.reachedStart && timeline.current) {
      void loadHistory().catch(() => undefined);
    }
  }, [focusEventId, items, loadHistory]);
  // Spec composer mentions: groups only (ADR 0141).
  const suggest = useMentionDirectory(room, face.isDirect === false);
  const query =
    face.isDirect === false && mode?.kind !== 'edit' && !suggestionsHidden ? mentionQuery(draft) : undefined;
  const everyoneLabel = s('bipe_app_composer_mention_everyone');
  const suggestions = query === undefined ? [] : suggest(query, everyoneLabel);
  const pickSuggestion = (suggestion: MentionSuggestion) => {
    const inserted = insertMention(draft, suggestion, everyoneLabel);
    setDraft(inserted.text);
    setMentions((m) => [...m, inserted.mention]);
    field.current?.focus();
  };
  // Messages from others that arrive while the person reads older ones count on the jump button.
  const lastKey = useRef<string>(undefined);
  const newest = itemRows.length > 0 ? itemRows[itemRows.length - 1] : undefined;
  useEffect(() => {
    if (!newest || newest.key === lastKey.current) return;
    const first = lastKey.current === undefined;
    lastKey.current = newest.key;
    if (!first && !atBottom && newest.kind === 'message' && !newest.own) setMissed((n) => n + 1);
  }, [newest, atBottom]);
  // Action markAsRead (ChatTimelineModel.swift): a read receipt for the newest event while the newest rows
  // are on screen and the page is visible. Public, except in a DM with the read receipts switch off.
  useEffect(() => {
    if (!atBottom || !newest || document.visibilityState !== 'visible') return;
    const type = face.isDirect !== false && !readReceiptsOn() ? ReceiptType.ReadPrivate : ReceiptType.Read;
    timeline.current?.markAsRead(type).catch(() => undefined);
  }, [newest?.key, atBottom, face.isDirect]);
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible' && atBottom)
        timeline.current?.markAsRead(receiptType()).catch(() => undefined);
    };
    const receiptType = () =>
      face.isDirect !== false && !readReceiptsOn() ? ReceiptType.ReadPrivate : ReceiptType.Read;
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [atBottom, face.isDirect]);
  const lastTyping = useRef(0);
  const typingNotice = (text: string) => {
    // Spec typing-indicator: draftChanged; at most one notice every 4 s while typing, off when empty.
    const now = Date.now();
    if (text.trim() === '') {
      lastTyping.current = 0;
      room.typingNotice(false).catch(() => undefined);
    } else if (now - lastTyping.current > 4000) {
      lastTyping.current = now;
      room.typingNotice(true).catch(() => undefined);
    }
  };
  const field = useRef<HTMLTextAreaElement>(null);
  const loadReply = useCallback((eventId: string) => {
    timeline.current?.fetchDetailsForEvent(eventId).catch(() => undefined);
  }, []);
  const on: RowHandlers = {
    react: (row, key) =>
      timeline.current?.toggleReaction(row.itemId, key).catch((e: unknown) => {
        console.warn('[trace] reaction failed', e);
        setNote(s('bipe_app_reaction_failed'));
      }),
    reply: (row) => {
      setMode({ kind: 'reply', row });
      field.current?.focus();
    },
    edit: (row) => {
      setMode({ kind: 'edit', row });
      setDraft(row.text);
      field.current?.focus();
    },
    remove: (row) => setDeleting(row),
    copy: (row) => {
      void navigator.clipboard.writeText(row.media?.caption ?? row.text).then(() => setToast(s('bipe_app_copied')));
    },
    loadReply,
    forward: (row) => {
      const info = row.provider.debugInfo();
      const content = forwardContentOf(info.originalJson, info.latestEditJson);
      if (content) setSheet({ kind: 'forward', content });
    },
    moreReactions: (row) => setSheet({ kind: 'emoji', row }),
    vote: async (row, answerIds) => {
      if (row.eventId) await timeline.current?.sendPollResponse(row.eventId, answerIds);
    },
    endPoll: async (row) => {
      if (row.eventId) await timeline.current?.endPoll(row.eventId, s('bipe_app_poll_ended_text'));
    },
    copyLink: (row) => {
      if (!row.eventId) return;
      void navigator.clipboard
        .writeText(messageLink(room.id(), row.eventId))
        .then(() => setToast(s('bipe_app_copied')));
    },
    senders: (_row, pill) => setSheet({ kind: 'senders', pill }),
    pinState: (row) => {
      if (!canPin || !row.eventId || row.body === 'deleted') return undefined;
      return pinnedIds.has(row.eventId) ? 'unpin' : 'pin';
    },
    togglePin: (row) => {
      const t = timeline.current;
      if (!t || !row.eventId) return;
      (pinnedIds.has(row.eventId) ? t.unpinEvent(row.eventId) : t.pinEvent(row.eventId)).catch((e: unknown) => {
        console.warn('[trace] pin failed', e);
        setNote(s('bipe_error_generic'));
      });
    },
    retry: (row) => {
      row.provider
        .getSendHandle()
        ?.tryResend()
        .catch((e: unknown) => setNote(describe(e)));
    },
    cancelUpload: (row) => {
      row.provider
        .getSendHandle()
        ?.abort()
        .catch((e: unknown) => setNote(describe(e)));
    },
  };
  const name = face.name;
  const newsRoom = useIsNewsRoom(room.id());
  const newestLabel = s('bipe_a11y_scroll_to_newest');
  const canSend = draft.trim() !== '';

  return (
    <div className="flex h-full min-h-0">
      <section
        className="relative isolate flex h-full min-h-0 min-w-0 flex-1 flex-col"
        // Paste an image, or drop files onto the chat: both open the upload preview.
        onPaste={(e) => {
          const files = Array.from(e.clipboardData.files);
          if (files.length === 0) return;
          e.preventDefault();
          setPending(files.slice(0, MAX_ITEMS));
        }}
        onDragOver={(e) => {
          if (!e.dataTransfer.types.includes('Files')) return;
          e.preventDefault();
          setDropping(true);
        }}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDropping(false);
        }}
        onDrop={(e) => {
          const files = Array.from(e.dataTransfer.files);
          setDropping(false);
          if (files.length === 0) return;
          e.preventDefault();
          setPending(files.slice(0, MAX_ITEMS));
        }}
      >
        <ChatBackground />
        {dropping && (
          <div className="pointer-events-none absolute inset-2 z-30 rounded-2xl border-2 border-dashed border-accent-fill bg-accent-wash/60" />
        )}
        {pending && <UploadPreview files={pending} timeline={timeline.current} onClose={() => setPending(undefined)} />}
        <BusinessChatSheets chat={business} />
        {/* The inline navigation bar: back on a narrow window, the chat title in the middle. */}
        <header className="bipe-bar relative grid h-[52px] shrink-0 grid-cols-[44px_1fr_44px] items-center px-2">
          <button
            className="grid size-11 place-items-center rounded-full text-glyph-primary md:invisible"
            aria-label={s('bipe_a11y_back')}
            onClick={onBack}
          >
            <Icon path={caretLeft} size={22} />
          </button>
          {/* The title opens the info screen: the person in a DM, the group details in a group. */}
          <button
            type="button"
            className="flex min-w-0 items-center justify-center gap-2 justify-self-center rounded-full px-2"
            aria-expanded={showsInfo}
            onClick={() => onInfo(!showsInfo)}
          >
            <Avatar name={name} size={32} mxcUrl={face.avatarUrl} userId={face.peer?.userId} official={newsRoom} members={face.members} />
            <h2 className="truncate text-headline text-ink-primary">{name}</h2>
            <ChatOfficialBadge roomId={room.id()} peerId={face.peer?.userId} />
          </button>
          {/* Buscar na conversa (spec chat-search with roomId). */}
          <button
            type="button"
            className="grid size-11 place-items-center rounded-full text-glyph-primary"
            aria-label={s('bipe_search_in_chat')}
            title={s('bipe_search_in_chat')}
            aria-pressed={searching}
            onClick={() => setSearching((v) => !v)}
          >
            <Icon path={magnifyingGlass} size={22} />
          </button>
        </header>
        {isGroup && (
          <PinnedBanner
            pins={pins}
            jump={(eventId) => {
              const el = document.getElementById(`ev-${eventId}`);
              if (!el) return navigate({ kind: 'chat', roomId: room.id(), eventId });
              el.scrollIntoView({ block: 'center' });
              el.dataset.highlight = 'true';
              setTimeout(() => delete el.dataset.highlight, 2000);
            }}
          />
        )}
        <WorkChatBar chat={work} onNotes={() => setNotesOpen(true)} />
        <BusinessChatBar chat={business} />
        {searching && (
          <ChatSearch
            rows={rows}
            loadMore={async () => {
              await loadHistory();
              return !history.current.reachedStart;
            }}
            onOpen={(eventId) => {
              setSearching(false);
              navigate({ kind: 'chat', roomId: room.id(), eventId });
            }}
            onClose={() => setSearching(false)}
          />
        )}
        <FloatingDate
          at={floating.at}
          visible={floating.visible}
          notice={jumpNotice}
          onPick={(day) => {
            setJumpNotice('');
            firstEventOfDay(client, room.id(), day, marker)
              .then((eventId) => {
                if (eventId) navigate({ kind: 'chat', roomId: room.id(), eventId });
                else setJumpNotice(s('bipe_jump_to_date_empty'));
              })
              .catch(() => setJumpNotice(s('bipe_error_generic')));
          }}
        />
        {/* column-reverse keeps the newest message in view as rows come in. */}
        <div
          ref={scroller}
          className="flex min-h-0 flex-1 flex-col-reverse overflow-y-auto overscroll-contain"
          onScroll={(e) => {
            const el = e.currentTarget;
            // The floating date: the day of the top row on screen, hidden 1 s after scrolling stops.
            const box = el.getBoundingClientRect();
            const top = document.elementFromPoint(box.left + box.width / 2, box.top + 24)?.closest('[data-at]');
            const at = top ? Number((top as HTMLElement).dataset.at) : undefined;
            setFloating({ at, visible: true });
            if (floatTimer.current) clearTimeout(floatTimer.current);
            floatTimer.current = setTimeout(() => setFloating((f) => ({ ...f, visible: false })), 1000);
            const bottom = Math.abs(el.scrollTop) < 80;
            setAtBottom(bottom);
            if (bottom) setMissed(0);
            // Older messages load while the top of the list is near; the reversed column keeps the view still.
            if (el.scrollHeight - el.clientHeight - Math.abs(el.scrollTop) < 400)
              void loadHistory().catch(() => undefined);
          }}
        >
          <ol className="mx-auto flex w-full max-w-[760px] flex-col gap-1.5 px-3 py-2">
            {loadingHistory && (
              <li className="py-2 text-center text-footnote text-ink-secondary">
                {s('bipe_app_timeline_loading_history')}
              </li>
            )}
            {rows.map((r) => (
              <TimelineRowView
                key={r.key}
                row={r}
                isDm={isDm}
                dmPeerId={face.peer?.userId}
                senders={senders}
                on={on}
                replyByName={work.on ? replyByName : undefined}
              />
            ))}
          </ol>
        </div>
        {!atBottom && (
          // Back to the newest message, with the count of messages that came in meanwhile.
          <button
            type="button"
            className="bipe-glass absolute right-4 bottom-24 z-10 grid size-11 place-items-center rounded-full text-glyph-primary shadow"
            aria-label={newestLabel}
            onClick={() => scroller.current?.scrollTo({ top: 0, behavior: 'smooth' })}
          >
            <Icon path={caretRight} size={22} className="rotate-90" />
            {missed > 0 && (
              <span className="absolute -top-1 -right-1 rounded-full bg-accent-fill px-1.5 text-caption font-bold text-ink-on-accent">
                {missed.toLocaleString()}
              </span>
            )}
          </button>
        )}
        <div className="mx-auto w-full max-w-[760px] shrink-0">
          {note && <p className="px-4 pt-2 text-footnote break-words text-trust-danger-ink">{note}</p>}
          {toast && (
            <p
              role="status"
              className="mx-auto w-max rounded-full bg-surface-raised px-4 py-2 text-subheadline text-ink-primary shadow"
            >
              {toast}
            </p>
          )}
          {typing.length > 0 && <TypingLine names={typing} />}
          {companyName && (
            // ADR 0121: a company account writes as the company; the composer says so.
            <p className="px-4 pt-1 text-caption text-ink-secondary">{s('bipe_company_replying_as', companyName)}</p>
          )}
          {suggestions.length > 0 && <SuggestionList suggestions={suggestions} onPick={pickSuggestion} />}
          {mode && (
            <ComposerBar
              icon={mode.kind === 'edit' ? pencilSimple : arrowBendUpLeft}
              title={
                mode.kind === 'edit'
                  ? s('bipe_app_message_editing')
                  : s('bipe_app_message_replying_to', mode.row.own ? s('bipe_app_you') : mode.row.who)
              }
              preview={mode.row.media?.caption ?? mode.row.text}
              onClose={() => {
                if (mode.kind === 'edit') setDraft('');
                setMode(undefined);
              }}
            />
          )}
          {blocked ? (
            // Spec block-person: a blocked person's DM stays, with the composer off and a bar to unblock.
            <div className="flex items-center gap-3 px-4 py-3">
              <p className="min-w-0 flex-1 text-footnote text-ink-secondary">{s('bipe_app_blocked_bar', face.name)}</p>
              <button
                type="button"
                className="min-h-11 shrink-0 rounded-full bg-surface-sunken px-4 text-subheadline font-semibold text-accent-ink"
                onClick={() => setUnblocking(true)}
              >
                {s('bipe_action_unblock_person')}
              </button>
            </div>
          ) : work.on && work.takenByOther && !replyAnyway ? (
            <TakenReplyBar
              onReply={() => {
                setReplyAnyway(true);
                requestAnimationFrame(() => field.current?.focus());
              }}
            />
          ) : (
            <form
              className="flex items-end gap-2 px-3 py-2"
              onSubmit={(e) => {
                e.preventDefault();
                const text = draft.trim();
                const t = timeline.current;
                if (!text || !t || claiming.current) return;
                const send = () => {
                  setDraft('');
                  setMode(undefined);
                  typingNotice('');
                  const content =
                    mode?.kind === 'edit' ? messageEventContentFromMarkdown(text) : contentWithMentions(text, mentions);
                  setMentions([]);
                  const sent =
                    mode?.kind === 'edit'
                      ? t.edit(mode.row.itemId, new EditedContent.RoomMessage({ content }))
                      : mode?.kind === 'reply' && mode.row.eventId
                        ? t.sendReply(content, mode.row.eventId)
                        : t.send(content);
                  sent.catch((err: unknown) => setNote(describe(err)));
                };
                // Claim on reply (spec work-chat-bar reply): a send to a chat in the pool takes it first. The text stays
                // in the field while the claim runs; when another person took the chat meanwhile, nothing is sent and
                // the composer shows bipe_chat_taken_reply.
                if (work.on && work.assignee === undefined && mode?.kind !== 'edit') {
                  claiming.current = true;
                  void work.claim().then((ok) => {
                    claiming.current = false;
                    if (ok) send();
                  });
                  return;
                }
                send();
              }}
            >
              {mode?.kind !== 'edit' && <AttachButton onFiles={(files) => setPending(files.slice(0, MAX_ITEMS))} />}
              {/* The field: glass, 22 pt corners, 16 pt leading inset; one 22 pt line + 2 x 11 pt = 44 pt. */}
              <div className="bipe-glass flex min-w-0 flex-1 rounded-[22px] pr-1 pl-4">
                <textarea
                  ref={field}
                  className="max-h-[132px] min-h-11 w-full resize-none bg-transparent py-[11px] text-body text-ink-primary outline-none [field-sizing:content] placeholder:text-ink-disabled"
                  rows={1}
                  value={draft}
                  placeholder={s('bipe_app_timeline_composer_placeholder')}
                  aria-label={s('bipe_app_timeline_composer_placeholder')}
                  onChange={(e) => {
                    setDraft(e.target.value);
                    typingNotice(e.target.value);
                    setMentions((m) => mentionsIn(m, e.target.value));
                    setSuggestionsHidden(false);
                  }}
                  onKeyDown={(e) => {
                    // Tab or Return picks the first @ suggestion; Escape hides the list.
                    if (suggestions.length > 0 && (e.key === 'Tab' || e.key === 'Enter') && !e.shiftKey) {
                      e.preventDefault();
                      pickSuggestion(suggestions[0]);
                    } else if (suggestions.length > 0 && e.key === 'Escape') {
                      e.preventDefault();
                      setSuggestionsHidden(true);
                    }
                    // Return sends, Shift+Return makes a new line (desktop keyboards).
                    else if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                      e.preventDefault();
                      e.currentTarget.form?.requestSubmit();
                    } else if (e.key === 'Escape' && mode) {
                      // Escape ends a reply or an edit before it closes anything else.
                      e.preventDefault();
                      if (mode.kind === 'edit') setDraft('');
                      setMode(undefined);
                    } else if (e.key === 'ArrowUp' && draft === '' && !mode) {
                      // Up in an empty field edits the newest own text message, as on desktop chat apps.
                      const last = [...rows]
                        .reverse()
                        .find((r): r is MessageRow => r.kind === 'message' && r.own && r.canEdit);
                      if (last) {
                        e.preventDefault();
                        on.edit(last);
                      }
                    }
                  }}
                />
              </div>
              {/* The send button: a 44 pt circle in accent.fill when there is text. The apps show the record
              button in this place when the field is empty; the web has no voice messages yet. */}
              <button
                className={`grid size-11 shrink-0 place-items-center rounded-full ${canSend ? 'bg-accent-fill text-ink-on-accent hover:bg-accent-fill-pressed' : 'bipe-glass text-ink-disabled'}`}
                type="submit"
                disabled={!canSend}
                aria-label={mode?.kind === 'edit' ? s('bipe_app_message_save_edit') : s('bipe_app_timeline_send')}
              >
                <Icon path={mode?.kind === 'edit' ? check : paperPlaneRight} size={22} />
              </button>
            </form>
          )}
        </div>
      </section>
      {sheet?.kind === 'forward' && (
        <ForwardSheet content={sheet.content} chats={chats} onClose={() => setSheet(undefined)} onDone={setToast} />
      )}
      {sheet?.kind === 'emoji' && (
        <EmojiPicker onPick={(key) => on.react(sheet.row, key)} onClose={() => setSheet(undefined)} />
      )}
      {sheet?.kind === 'senders' && (
        <ReactionSenders
          room={room}
          reactionKey={sheet.pill.key}
          senders={sheet.pill.senders}
          onClose={() => setSheet(undefined)}
        />
      )}
      {showsInfo && (
        <ChatInfo
          room={room}
          face={face}
          chats={chats}
          latestTs={newest?.kind === 'message' ? newest.at : undefined}
          businessTools={<BusinessToolButtons chat={business} onPick={() => onInfo(false)} />}
          onClose={() => onInfo(false)}
          onGone={onBack}
        />
      )}
      {notesOpen && work.on && (
        <WorkNotesSheet
          client={client}
          roomId={room.id()}
          accountName={account.name ?? ''}
          onClose={() => setNotesOpen(false)}
        />
      )}
      {unblocking && face.peer && (
        <BlockAlert userId={face.peer.userId} name={face.name} blocked onClose={() => setUnblocking(false)} />
      )}
      {deleting && (
        <DeleteDialog
          onCancel={() => setDeleting(undefined)}
          onDelete={() => {
            const row = deleting;
            setDeleting(undefined);
            timeline.current?.redactEvent(row.itemId, undefined).catch((e: unknown) => setNote(describe(e)));
          }}
        />
      )}
    </div>
  );
}

// The reply and edit bar above the field (ChatTimelineView.swift bar): an 18 px icon in accent.ink, the
// title in caption bold accent.ink, one line of the message in caption ink.secondary, and a close button.
function ComposerBar({
  icon,
  title,
  preview,
  onClose,
}: {
  icon: string;
  title: string;
  preview: string;
  onClose: () => void;
}) {
  return (
    <div className="bipe-glass mx-3 mt-2 flex items-center gap-2.5 rounded-[22px] py-2 pr-2 pl-4">
      <Icon path={icon} size={18} className="shrink-0 text-accent-ink" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-caption font-bold text-accent-ink">{title}</span>
        <span className="truncate text-caption text-ink-secondary">{preview}</span>
      </div>
      <button
        type="button"
        className="grid size-11 place-items-center rounded-full text-glyph-secondary"
        aria-label={s('bipe_app_cancel')}
        onClick={onClose}
      >
        <Icon path={x} size={16} />
      </button>
    </div>
  );
}

// The delete alert (spec message-delete): bipe_app_message_delete_title and _body, Cancelar and Apagar.
function DeleteDialog({ onCancel, onDelete }: { onCancel: () => void; onDelete: () => void }) {
  return (
    <Alert
      title={s('bipe_app_message_delete_title')}
      action={s('bipe_app_message_action_delete')}
      onCancel={onCancel}
      onAction={onDelete}
    >
      {s('bipe_app_message_delete_body')}
    </Alert>
  );
}

// Spec typing-indicator: who is typing in this chat, by name (Room.subscribeToTypingNotifications).
function useTyping(room: Room, ownUserId: string): string[] {
  const [names, setNames] = useState<string[]>([]);
  useEffect(() => {
    let stopped = false;
    const handle = room.subscribeToTypingNotifications({
      call(ids) {
        const others = ids.filter((id) => id !== ownUserId);
        Promise.all(
          others.map((id) =>
            room
              .memberDisplayName(id)
              .catch(() => undefined)
              .then((n) => n ?? id),
          ),
        ).then((list) => !stopped && setNames(list));
      },
    });
    return () => {
      stopped = true;
      handle.cancel();
    };
  }, [room, ownUserId]);
  return names;
}

// TypingLine (ChatTimelineView.swift): three calm dots and the names in caption ink.secondary, one line.
// The dots stand still under reduced motion.
function TypingLine({ names }: { names: string[] }) {
  const text =
    names.length === 1
      ? s('bipe_app_typing_one', names[0])
      : names.length === 2
        ? s('bipe_app_typing_two', names[0], names[1])
        : s('bipe_app_typing_many', names[0]);
  return (
    <div role="status" className="flex items-center gap-1.5 px-4 py-1">
      <span aria-hidden className="flex gap-[3px]">
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="size-[5px] rounded-full bg-glyph-secondary motion-safe:animate-pulse"
            style={{ animationDelay: `${i * 200}ms` }}
          />
        ))}
      </span>
      <span className="truncate text-caption text-ink-secondary">{text}</span>
    </div>
  );
}

// The @ suggestions of a group (ChatTimelineView suggestionList): a 32 px avatar and the name, 16 x 6 px
// insets, at most 200 px high.
function SuggestionList({
  suggestions,
  onPick,
}: {
  suggestions: MentionSuggestion[];
  onPick: (s: MentionSuggestion) => void;
}) {
  return (
    <ul role="listbox" className="bipe-glass mx-3 mt-2 max-h-[200px] overflow-y-auto rounded-[22px] py-1">
      {suggestions.map((m) => (
        <li key={m.userId ?? '@room'}>
          <button
            type="button"
            role="option"
            aria-selected={false}
            className="flex w-full items-center gap-2.5 px-4 py-1.5 text-left hover:bg-surface-sunken"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => onPick(m)}
          >
            <Avatar name={m.name} size={32} mxcUrl={m.avatarUrl} userId={m.userId} />
            <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{m.name}</span>
            {isOfficial(m.userId) && <OfficialBadge />}
          </button>
        </li>
      ))}
    </ul>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// One chat: the inline bar, the timeline over the chat background, the composer
// (ChatTimelineView.swift, TimelineRowView.swift; specs chat-timeline, timeline-rows, composer).
// The column is at most 760 px wide, centred, like the frame of ChatTimelineView.

import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  EventSendState,
  MsgLikeKind,
  ProfileDetails,
  TimelineDiff,
  TimelineItemContent,
  VirtualTimelineItem,
  messageEventContentFromMarkdown,
  type EventOrTransactionId,
  type RoomLike as Room,
  type TaskHandleLike as TaskHandle,
  type TimelineLike as Timeline,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import { caretLeft, check, clock, lockSimple, paperPlaneRight, smiley, trash, warningCircle } from './phosphor/icons';
import { AttachButton, MAX_ITEMS, UploadPreview } from './Attach';
import { pillsOf, QuickReactions, ReactionPills, type ReactionPill } from './Reactions';
import { useRoomFace } from './room-face';
import { mediaOf, TimelineMediaRow, type MediaInfo } from './TimelineMedia';
import { Avatar } from './ui/Avatar';
import { ChatBackground } from './ui/ChatBackground';
import { dayText, s, timeText } from './ui/i18n';
import { Icon } from './ui/Icon';
import { applyDiffs, describe, type DiffVariants } from './util';

type Body = 'text' | 'media' | 'deleted' | 'undecryptable' | 'other';
type Send = 'sending' | 'failed' | 'sent';
type Row =
  | {
      kind: 'message';
      key: string;
      who: string;
      own: boolean;
      at: number;
      body: Body;
      text: string;
      media?: MediaInfo;
      send?: Send;
      itemId: EventOrTransactionId;
      pills: ReactionPill[];
      canReact: boolean;
    }
  | { kind: 'day'; key: string; at: number }
  | { kind: 'readMarker'; key: string }
  | { kind: 'start'; key: string };

function itemRow(item: TimelineItem, ownUserId: string): Row | undefined {
  const key = item.uniqueId().id;
  const virtual = item.asVirtual();
  if (virtual) {
    if (VirtualTimelineItem.DateDivider.instanceOf(virtual)) return { kind: 'day', key, at: Number(virtual.inner.ts) };
    if (VirtualTimelineItem.ReadMarker.instanceOf(virtual)) return { kind: 'readMarker', key };
    if (VirtualTimelineItem.TimelineStart.instanceOf(virtual)) return { kind: 'start', key };
    return undefined;
  }
  const event = item.asEvent();
  // State and membership events have no row here yet (the apps show some as state lines).
  if (!event || !TimelineItemContent.MsgLike.instanceOf(event.content)) return undefined;
  const who = ProfileDetails.Ready.instanceOf(event.senderProfile)
    ? (event.senderProfile.inner.displayName ?? event.sender)
    : event.sender;
  const kind = event.content.inner.content.kind;
  let body: Body = 'other';
  // TODO(string): '(event)' is spike text for a message kind this page does not draw (sticker, poll, ...).
  let text = '(event)';
  let media: MediaInfo | undefined;
  if (MsgLikeKind.Message.instanceOf(kind)) {
    media = mediaOf(kind.inner.content.msgType);
    body = media ? 'media' : 'text';
    text = kind.inner.content.body;
  } else if (MsgLikeKind.UnableToDecrypt.instanceOf(kind)) {
    body = 'undecryptable';
    // TODO(string): bipe_app_timeline_undecryptable says "neste celular"; the web needs a computer variant.
    text = 'Unable to decrypt this message.';
  } else if (MsgLikeKind.Redacted.instanceOf(kind)) {
    body = 'deleted';
    text = event.isOwn ? s('bipe_app_message_deleted_own') : s('bipe_app_message_deleted');
  }
  let send: Send | undefined;
  if (event.isOwn) {
    const state = event.localSendState;
    if (state && EventSendState.NotSentYet.instanceOf(state)) send = 'sending';
    else if (state && EventSendState.SendingFailed.instanceOf(state)) send = 'failed';
    else send = 'sent';
  }
  // Spec message-reactions: undecryptable, deleted and unsent own messages take no reactions.
  const canReact = (body === 'text' || body === 'media' || body === 'other') && (send === undefined || send === 'sent');
  return {
    kind: 'message',
    key,
    who,
    own: event.isOwn,
    at: Number(event.timestamp),
    body,
    text,
    media,
    send,
    itemId: event.eventOrTransactionId,
    pills: pillsOf(event.content.inner.content.reactions, ownUserId),
    canReact,
  };
}

export function RoomTimeline({ room, onBack }: { room: Room; onBack: () => void }) {
  const [items, setItems] = useState<TimelineItem[]>([]);
  const [draft, setDraft] = useState('');
  const [note, setNote] = useState('');
  // Files picked, pasted or dropped, waiting in the upload preview.
  const [pending, setPending] = useState<File[]>();
  const [dropping, setDropping] = useState(false);
  const timeline = useRef<Timeline>(undefined);
  const listener = useRef<TaskHandle>(undefined);

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
      await t.paginateBackwards(30);
    })().catch((e) => setNote(describe(e)));
    return () => {
      stopped = true;
      listener.current?.cancel();
    };
  }, [room]);

  const ownUserId = room.ownUserId();
  const rows = items.map((i) => itemRow(i, ownUserId)).filter((r) => r !== undefined);
  const react = (itemId: EventOrTransactionId, key: string) => {
    timeline.current?.toggleReaction(itemId, key).catch((e: unknown) => {
      console.warn('[trace] reaction failed', e);
      setNote(s('bipe_app_reaction_failed'));
    });
  };
  const face = useRoomFace(room);
  const name = face.name;
  // Group rows show the sender's name; DM rows do not. Until RoomInfo comes, no names.
  const isDm = face.isDirect ?? true;
  const canSend = draft.trim() !== '';

  return (
    <section
      className="relative isolate flex h-full min-h-0 flex-col"
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
      {/* The inline navigation bar: back on a narrow window, the chat title in the middle. */}
      <header className="bipe-bar relative grid h-[52px] shrink-0 grid-cols-[44px_1fr_44px] items-center px-2">
        <button
          className="grid size-11 place-items-center rounded-full text-glyph-primary md:invisible"
          aria-label={s('bipe_a11y_back')}
          onClick={onBack}
        >
          <Icon path={caretLeft} size={22} />
        </button>
        <div className="flex min-w-0 items-center justify-center gap-2">
          <Avatar name={name} size={32} mxcUrl={face.avatarUrl} />
          <h2 className="truncate text-headline text-ink-primary">{name}</h2>
        </div>
      </header>
      {/* column-reverse keeps the newest message in view as rows come in. */}
      <div className="flex min-h-0 flex-1 flex-col-reverse overflow-y-auto">
        <ol className="mx-auto flex w-full max-w-[760px] flex-col gap-1.5 px-3 py-2">
          {rows.map((r) => (
            <TimelineRowView key={r.key} row={r} isDm={isDm} onReact={react} />
          ))}
        </ol>
      </div>
      <div className="mx-auto w-full max-w-[760px] shrink-0">
        {note && <p className="px-4 pt-2 text-footnote break-words text-trust-danger-ink">{note}</p>}
        <form
          className="flex items-end gap-2 px-3 py-2"
          onSubmit={(e) => {
            e.preventDefault();
            const text = draft.trim();
            if (!text || !timeline.current) return;
            setDraft('');
            timeline.current
              .send(messageEventContentFromMarkdown(text))
              .catch((err: unknown) => setNote(describe(err)));
          }}
        >
          <AttachButton onFiles={(files) => setPending(files.slice(0, MAX_ITEMS))} />
          {/* The field: glass, 22 pt corners, 16 pt leading inset; one 22 pt line + 2 x 11 pt = 44 pt. */}
          <div className="bipe-glass flex min-w-0 flex-1 rounded-[22px] pr-1 pl-4">
            <textarea
              className="max-h-[132px] min-h-11 w-full resize-none bg-transparent py-[11px] text-body text-ink-primary outline-none [field-sizing:content] placeholder:text-ink-disabled"
              rows={1}
              value={draft}
              placeholder={s('bipe_app_timeline_composer_placeholder')}
              aria-label={s('bipe_app_timeline_composer_placeholder')}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                // Return sends, Shift+Return makes a new line (desktop keyboards).
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  e.currentTarget.form?.requestSubmit();
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
            aria-label={s('bipe_app_timeline_send')}
          >
            <Icon path={paperPlaneRight} size={22} />
          </button>
        </form>
      </div>
    </section>
  );
}

function TimelineRowView({
  row,
  isDm,
  onReact,
}: {
  row: Row;
  isDm: boolean;
  onReact: (itemId: EventOrTransactionId, key: string) => void;
}) {
  switch (row.kind) {
    case 'day':
      return (
        <li>
          <SystemLine text={dayText(row.at)} />
        </li>
      );
    case 'start':
      return (
        <li>
          <SystemLine text={s('bipe_app_timeline_start')} />
        </li>
      );
    case 'readMarker':
      return (
        <li className="flex items-center gap-2 py-1.5">
          <span className="h-px flex-1 bg-accent-fill" />
          <span className="text-caption font-bold text-accent-ink">{s('bipe_app_timeline_new_messages')}</span>
          <span className="h-px flex-1 bg-accent-fill" />
        </li>
      );
    case 'message':
      return (
        <Bubble own={row.own} pills={row.pills} onReact={row.canReact ? (key) => onReact(row.itemId, key) : undefined}>
          {!row.own && (!isDm || row.body === 'undecryptable') && (
            <span
              className={`text-caption font-bold ${row.body === 'undecryptable' ? 'text-ink-secondary' : 'text-accent-ink'}`}
            >
              {row.who}
            </span>
          )}
          {row.media ? (
            <TimelineMediaRow media={row.media} />
          ) : row.body === 'text' ? (
            <p className="text-body break-words whitespace-pre-wrap text-ink-primary">{row.text}</p>
          ) : (
            <p className="flex items-start gap-1.5 text-subheadline text-ink-secondary italic">
              {row.body === 'deleted' && (
                <Icon path={trash} size={14} className="mt-[3px] shrink-0 text-glyph-secondary" />
              )}
              {row.body === 'undecryptable' && (
                <Icon path={lockSimple} size={14} className="mt-[3px] shrink-0 text-glyph-secondary" />
              )}
              {row.text}
            </p>
          )}
          <Footer at={row.at} send={row.body === 'text' || row.body === 'media' ? row.send : undefined} />
        </Bubble>
      );
  }
}

// A quiet centred line: date separators and the chat start (SystemLine, TimelineRowView.swift).
function SystemLine({ text }: { text: string }) {
  return (
    <div className="flex justify-center py-1.5">
      <span className="rounded-full bg-surface-sunken px-2.5 py-1 text-center text-caption text-ink-secondary">
        {text}
      </span>
    </div>
  );
}

// Bubble (TimelineRowView.swift): 16 pt corners, 12 x 8 pt insets, bubble.own or bubble.other, at
// least 48 pt free on the far side. Its views stack with 4 pt between; the footer sits at the trailing edge.
// The reaction pills sit under the bubble, on its side.
function Bubble({
  own,
  pills,
  onReact,
  children,
}: {
  own: boolean;
  pills: ReactionPill[];
  onReact?: (key: string) => void;
  children: ReactNode;
}) {
  const [picking, setPicking] = useState(false);
  return (
    <li className={`flex flex-col gap-1 ${own ? 'items-end pl-12' : 'items-start pr-12'}`}>
      <div className={`group relative flex max-w-full items-center gap-1 ${own ? 'flex-row-reverse' : ''}`}>
        <div
          className={`flex max-w-full min-w-0 flex-col gap-1 rounded-2xl px-3 py-2 ${own ? 'bg-bubble-own' : 'bg-bubble-other'}`}
          onContextMenu={
            onReact
              ? (e) => {
                  e.preventDefault();
                  setPicking(true);
                }
              : undefined
          }
        >
          {children}
        </div>
        {onReact && (
          <button
            type="button"
            className="bipe-glass grid size-8 shrink-0 place-items-center rounded-full text-glyph-secondary opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
            aria-label={s('bipe_a11y_react')}
            aria-haspopup="menu"
            onClick={() => setPicking(true)}
          >
            <Icon path={smiley} size={18} />
          </button>
        )}
        {picking && onReact && <QuickReactions own={own} onPick={onReact} onClose={() => setPicking(false)} />}
      </div>
      {pills.length > 0 && <ReactionPills pills={pills} onToggle={onReact ?? (() => undefined)} />}
    </li>
  );
}

// The time and, on an own message, the send state (spec read-receipts): a clock while it sends, a tick
// once the server has it, a warning when it failed.
// TODO: delivered and read ticks need the receipt logic of DeliveredAcks; the web does not have it yet.
function Footer({ at, send }: { at: number; send?: Send }) {
  return (
    <span className="flex items-center gap-1 self-end text-caption2 whitespace-nowrap text-ink-secondary">
      <time className="tabular-nums" dateTime={new Date(at).toISOString()}>
        {timeText(at)}
      </time>
      {send === 'sending' && (
        <>
          <Icon path={clock} size={14} />
          {s('bipe_app_timeline_sending')}
        </>
      )}
      {send === 'sent' && <Icon path={check} size={14} label={s('bipe_app_receipt_sent')} />}
      {send === 'failed' && (
        <span className="flex items-center gap-1 text-trust-danger-ink">
          <Icon path={warningCircle} size={14} />
          {s('bipe_app_timeline_failed')}
        </span>
      )}
    </span>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The rows of the timeline (TimelineRowView.swift; specs timeline-rows, message-actions, message-reply,
// message-edit, message-delete, message-reactions): bubbles with the sender, the reply quote, the text or
// media, the footer and the reaction pills; date separators, the chat start and the new-messages line.

import { useEffect, useState, type ReactNode } from 'react';
import {
  EmbeddedEventDetails,
  EventOrTransactionId as EventOrTransactionIdValue,
  EventSendState,
  MsgLikeKind,
  ProfileDetails,
  TimelineItemContent,
  VirtualTimelineItem,
  type EventOrTransactionId,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import {
  arrowBendUpLeft,
  check,
  checks,
  clock,
  copy,
  dotsThree,
  lockSimple,
  pencilSimple,
  trash,
  warningCircle,
} from './phosphor/icons';
import { MessageMenu, pillsOf, ReactionPills, type MessageAction, type ReactionPill } from './Reactions';
import { previewText } from './row-preview';
import { mediaOf, TimelineMediaRow, type MediaInfo } from './TimelineMedia';
import { dayText, s, timeText } from './ui/i18n';
import { Icon } from './ui/Icon';

type Body = 'text' | 'media' | 'deleted' | 'undecryptable' | 'other';
export type Send = 'sending' | 'failed' | 'sent' | 'read';
export type ReplyQuoteData = {
  eventId: string;
  state: 'loading' | 'ready' | 'unavailable';
  senderName?: string;
  preview?: string;
};
export type MessageRow = {
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
  eventId?: string;
  pills: ReactionPill[];
  canReact: boolean;
  canReply: boolean;
  canEdit: boolean;
  canDelete: boolean;
  edited: boolean;
  reply?: ReplyQuoteData;
  /** People other than the account whose read receipt sits on this event. */
  readBy: string[];
};
export type Row =
  | MessageRow
  | { kind: 'day'; key: string; at: number }
  | { kind: 'readMarker'; key: string }
  | { kind: 'start'; key: string };

function nameOf(profile: ProfileDetails, userId: string): string {
  return ProfileDetails.Ready.instanceOf(profile) ? (profile.inner.displayName ?? userId) : userId;
}

export function itemRow(item: TimelineItem, ownUserId: string): Row | undefined {
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
  const who = nameOf(event.senderProfile, event.sender);
  const msgLike = event.content.inner.content;
  const kind = msgLike.kind;
  let body: Body = 'other';
  // TODO(string): '(event)' is spike text for a message kind this page does not draw (sticker, poll, ...).
  let text = '(event)';
  let media: MediaInfo | undefined;
  let edited = false;
  if (MsgLikeKind.Message.instanceOf(kind)) {
    media = mediaOf(kind.inner.content.msgType);
    body = media ? 'media' : 'text';
    text = kind.inner.content.body;
    edited = kind.inner.content.isEdited;
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
  const eventId = EventOrTransactionIdValue.EventId.instanceOf(event.eventOrTransactionId)
    ? event.eventOrTransactionId.inner.eventId
    : undefined;
  const live = body === 'text' || body === 'media' || body === 'other';
  let reply: ReplyQuoteData | undefined;
  if (msgLike.inReplyTo) {
    const details = msgLike.inReplyTo.event();
    const replyId = msgLike.inReplyTo.eventId();
    if (EmbeddedEventDetails.Ready.instanceOf(details)) {
      reply = {
        eventId: replyId,
        state: 'ready',
        senderName: nameOf(details.inner.senderProfile, details.inner.sender),
        preview: previewText(details.inner.content),
      };
    } else if (EmbeddedEventDetails.Pending.instanceOf(details)) {
      reply = { eventId: replyId, state: 'loading' };
    } else {
      reply = { eventId: replyId, state: 'unavailable' };
    }
  }
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
    eventId,
    pills: pillsOf(msgLike.reactions, ownUserId),
    // Spec message-reactions: undecryptable, deleted and unsent own messages take no reactions.
    canReact: live && (send === undefined || send === 'sent'),
    canReply: live && event.canBeRepliedTo && eventId !== undefined,
    canEdit: event.isOwn && event.isEditable && body === 'text',
    canDelete: event.isOwn && body !== 'deleted',
    edited,
    reply,
    readBy: [...event.readReceipts.keys()].filter((id) => id !== ownUserId),
  };
}

/**
 * Spec read-receipts (ADR 0222) in a DM: an own message is read when the other person's read receipt sits
 * on it or on any newer event. The SDK keeps each person's newest receipt only, so the rows are walked
 * from the newest back.
 */
export function markRead(rows: Row[]): Row[] {
  let seen = false;
  const out = [...rows];
  for (let i = out.length - 1; i >= 0; i--) {
    const row = out[i];
    if (row.kind !== 'message') continue;
    if (row.readBy.length > 0) seen = true;
    if (seen && row.own && row.send === 'sent') out[i] = { ...row, send: 'read' };
  }
  return out;
}

export type RowHandlers = {
  react: (row: MessageRow, key: string) => void;
  reply: (row: MessageRow) => void;
  edit: (row: MessageRow) => void;
  remove: (row: MessageRow) => void;
  copy: (row: MessageRow) => void;
  loadReply: (eventId: string) => void;
};

export function TimelineRowView({ row, isDm, on }: { row: Row; isDm: boolean; on: RowHandlers }) {
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
      return <MessageBubble row={row} isDm={isDm} on={on} />;
  }
}

function MessageBubble({ row, isDm, on }: { row: MessageRow; isDm: boolean; on: RowHandlers }) {
  const actions: MessageAction[] = [];
  if (row.canReply)
    actions.push({ label: s('bipe_app_message_action_reply'), icon: arrowBendUpLeft, run: () => on.reply(row) });
  if (row.canEdit)
    actions.push({ label: s('bipe_app_message_action_edit'), icon: pencilSimple, run: () => on.edit(row) });
  if (row.body === 'text' || row.media?.caption)
    actions.push({ label: s('bipe_app_copy'), icon: copy, run: () => on.copy(row) });
  if (row.canDelete)
    actions.push({ label: s('bipe_app_message_action_delete'), icon: trash, danger: true, run: () => on.remove(row) });
  return (
    <Bubble
      id={row.eventId ? `ev-${row.eventId}` : undefined}
      own={row.own}
      pills={row.pills}
      onReact={row.canReact ? (key) => on.react(row, key) : undefined}
      actions={actions}
    >
      {!row.own && (!isDm || row.body === 'undecryptable') && (
        <span
          className={`text-caption font-bold ${row.body === 'undecryptable' ? 'text-ink-secondary' : 'text-accent-ink'}`}
        >
          {row.who}
        </span>
      )}
      {row.reply && <ReplyQuote reply={row.reply} onLoad={on.loadReply} />}
      {row.media ? (
        <TimelineMediaRow media={row.media} />
      ) : row.body === 'text' ? (
        <p className="text-body break-words whitespace-pre-wrap text-ink-primary">{row.text}</p>
      ) : (
        <p className="flex items-start gap-1.5 text-subheadline text-ink-secondary italic">
          {row.body === 'deleted' && <Icon path={trash} size={14} className="mt-[3px] shrink-0 text-glyph-secondary" />}
          {row.body === 'undecryptable' && (
            <Icon path={lockSimple} size={14} className="mt-[3px] shrink-0 text-glyph-secondary" />
          )}
          {row.text}
        </p>
      )}
      <Footer
        at={row.at}
        edited={row.edited}
        send={row.body === 'text' || row.body === 'media' ? row.send : undefined}
      />
    </Bubble>
  );
}

// ReplyQuote (TimelineRowView.swift): a 3 px accent.fill bar, the sender in caption bold accent.ink and one
// line of the original in ink.secondary, on surface.sunken at 60% with 8 px corners. A tap scrolls to the
// original when it is loaded.
function ReplyQuote({ reply, onLoad }: { reply: ReplyQuoteData; onLoad: (eventId: string) => void }) {
  useEffect(() => {
    if (reply.state === 'unavailable') onLoad(reply.eventId);
  }, [reply.state, reply.eventId, onLoad]);
  return (
    <button
      type="button"
      className="flex min-w-0 gap-2 rounded-lg bg-surface-sunken/60 p-1.5 text-left text-caption"
      aria-label={s('bipe_a11y_open_original')}
      onClick={() => document.getElementById(`ev-${reply.eventId}`)?.scrollIntoView({ block: 'center' })}
    >
      <span className="w-[3px] shrink-0 rounded-[1.5px] bg-accent-fill" />
      <span className="flex min-w-0 flex-col gap-0.5">
        {reply.state === 'ready' ? (
          <>
            <span className="font-bold text-accent-ink">{reply.senderName}</span>
            <span className="truncate text-ink-secondary">{reply.preview ?? ''}</span>
          </>
        ) : (
          <span className="text-ink-secondary">{s('bipe_app_message_reply_loading')}</span>
        )}
      </span>
    </button>
  );
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
// The reaction pills sit under the bubble, on its side. The message menu opens from the button that shows
// on hover or focus, or with a right-click on the bubble (the long press of the apps).
function Bubble({
  id,
  own,
  pills,
  onReact,
  actions,
  children,
}: {
  id?: string;
  own: boolean;
  pills: ReactionPill[];
  onReact?: (key: string) => void;
  actions: MessageAction[];
  children: ReactNode;
}) {
  const [menu, setMenu] = useState(false);
  const hasMenu = onReact !== undefined || actions.length > 0;
  return (
    <li id={id} className={`flex flex-col gap-1 ${own ? 'items-end pl-12' : 'items-start pr-12'}`}>
      <div className={`group relative flex max-w-full items-center gap-1 ${own ? 'flex-row-reverse' : ''}`}>
        <div
          className={`flex max-w-full min-w-0 flex-col gap-1 rounded-2xl px-3 py-2 ${own ? 'bg-bubble-own' : 'bg-bubble-other'}`}
          onContextMenu={
            hasMenu
              ? (e) => {
                  e.preventDefault();
                  setMenu(true);
                }
              : undefined
          }
        >
          {children}
        </div>
        {hasMenu && (
          <button
            type="button"
            className="bipe-glass grid size-8 shrink-0 place-items-center rounded-full text-glyph-secondary opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
            aria-label={s('bipe_message_actions')}
            aria-haspopup="menu"
            onClick={() => setMenu(true)}
          >
            <Icon path={dotsThree} size={18} />
          </button>
        )}
        {menu && <MessageMenu own={own} onPick={onReact} actions={actions} onClose={() => setMenu(false)} />}
      </div>
      {pills.length > 0 && <ReactionPills pills={pills} onToggle={onReact ?? (() => undefined)} />}
    </li>
  );
}

// The time, the edited label (message-edit) and, on an own message, the send state (spec read-receipts):
// a clock while it sends, a tick once the server has it, a warning when it failed.
function Footer({ at, edited, send }: { at: number; edited: boolean; send?: Send }) {
  return (
    <span className="flex items-center gap-1 self-end text-caption2 whitespace-nowrap text-ink-secondary">
      <time className="tabular-nums" dateTime={new Date(at).toISOString()}>
        {timeText(at)}
      </time>
      {edited && <span>{s('bipe_app_message_edited')}</span>}
      {send === 'sending' && (
        <>
          <Icon path={clock} size={14} />
          {s('bipe_app_timeline_sending')}
        </>
      )}
      {send === 'sent' && <Icon path={check} size={14} label={s('bipe_app_receipt_sent')} />}
      {send === 'read' && (
        <Icon path={checks} size={14} label={s('bipe_app_receipt_read')} className="text-accent-ink" />
      )}
      {send === 'failed' && (
        <span className="flex items-center gap-1 text-trust-danger-ink">
          <Icon path={warningCircle} size={14} />
          {s('bipe_app_timeline_failed')}
        </span>
      )}
    </span>
  );
}

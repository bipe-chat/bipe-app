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
  MessageType,
  MsgLikeKind,
  ProfileDetails,
  TimelineItemContent,
  VirtualTimelineItem,
  type EventOrTransactionId,
  type LazyTimelineItemProviderLike,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import {
  arrowBendUpLeft,
  check,
  checks,
  clock,
  copy,
  dotsThree,
  link,
  lockSimple,
  pencilSimple,
  pushPin,
  pushPinSlash,
  smiley,
  trash,
  warningCircle,
} from './phosphor/icons';
import { MessageMenu, pillsOf, ReactionPills, type MessageAction, type ReactionPill } from './Reactions';
import { voiceMedia } from './bipe/generated/registries.json';
import { isOfficial } from './registries';
import { linkify } from './linkify';
import { mentionLinksOf, type MentionLink } from './mentions';
import { internalRoute, navigate } from './router';
import { pauseLeft, ScamWarningCard, scamKindOf, type ScamKind } from './ScamWarning';
import { forwardCountOf } from './MessageSheets';
import { previewText } from './row-preview';
import {
  LocationView,
  parseGeoUri,
  PollCard,
  pollKindDisclosed,
  StickerView,
  type LocationInfo,
  type PollInfo,
  type StickerInfo,
} from './Received';
import { mediaOf, TimelineMediaRow, type MediaInfo } from './TimelineMedia';
import { dayText, s, timeText } from './ui/i18n';
import { Icon } from './ui/Icon';
import { OfficialBadge } from './ui/List';
import { ROLE_BADGE, senderLabel, type SenderFacts } from './sender-facts';

type Body = 'text' | 'media' | 'sticker' | 'poll' | 'location' | 'deleted' | 'undecryptable' | 'other';
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
  whoId: string;
  /** EventTimelineItem.senderProfile displayNameAmbiguous (spec timeline-rows). */
  whoAmbiguous: boolean;
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
  /** Upload progress of an own media message in the send queue, 0 to 100. */
  uploadPercent?: number;
  /** The SDK's handle to the item: its send handle retries or aborts a local echo. */
  provider: LazyTimelineItemProviderLike;
  /** People other than the account whose read receipt sits on this event. */
  readBy: string[];
  /** Spec scam-warning: the kind of a received message that looks like a scam pattern. */
  scamKind?: ScamKind;
  /** The chat.bipe.forward count of the content, for the forward line. */
  forwardCount: number;
  sticker?: StickerInfo;
  poll?: PollInfo;
  location?: LocationInfo;
  /** Spec message-forward: a sent message (with an event ID) of kind message. */
  canForward: boolean;
  /** Mention links in the HTML body, drawn as pills. */
  mentions: MentionLink[];
  ownUserId: string;
};
export type Row =
  | MessageRow
  | { kind: 'day'; key: string; at: number }
  | { kind: 'readMarker'; key: string }
  | { kind: 'start'; key: string };

/** Spec dm-delete: a day whose messages are all cleared shows no date, so the old date does not stay on screen. */
export function withoutEmptyDays(rows: Row[]): Row[] {
  return rows.filter((row, i) => {
    if (row.kind !== 'day') return true;
    const next = rows.slice(i + 1).find((r) => r.kind === 'message' || r.kind === 'day');
    return next?.kind === 'message';
  });
}

/** The display name, or bipe_person_no_name. Never the Matrix ID or its localpart (People names). */
function nameOf(profile: ProfileDetails, userId: string): string {
  const name = ProfileDetails.Ready.instanceOf(profile) ? profile.inner.displayName?.trim() : undefined;
  const hidden = !name || name === userId || name === userId.slice(1).split(':')[0] || /^@[^\s:]+:\S+$/.test(name);
  return hidden ? s('bipe_person_no_name') : name;
}

// Rows are made once per SDK item: the timeline hands over a new item when it changes.
const made = new WeakMap<TimelineItem, Row | null>();

export function itemRow(item: TimelineItem, ownUserId: string): Row | undefined {
  const hit = made.get(item);
  if (hit !== undefined) return hit ?? undefined;
  const row = makeRow(item, ownUserId);
  made.set(item, row ?? null);
  return row;
}

function makeRow(item: TimelineItem, ownUserId: string): Row | undefined {
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
  // A message kind this page does not draw (sticker, poll, ...).
  let text = s('bipe_app_timeline_unsupported');
  let media: MediaInfo | undefined;
  let sticker: StickerInfo | undefined;
  let poll: PollInfo | undefined;
  let location: LocationInfo | undefined;
  let mentions: MentionLink[] = [];
  let edited = false;
  if (MsgLikeKind.Message.instanceOf(kind)) {
    const type = kind.inner.content.msgType;
    media = mediaOf(type);
    mentions = mentionLinksOf(type);
    body = media ? 'media' : 'text';
    text = kind.inner.content.body;
    edited = kind.inner.content.isEdited;
    if (MessageType.Location.instanceOf(type)) {
      const c = type.inner.content;
      const point = parseGeoUri(c.geoUri);
      if (point) {
        body = 'location';
        location = { ...point, zoom: c.zoomLevel, description: c.description, body: c.body };
      }
    }
  } else if (MsgLikeKind.Sticker.instanceOf(kind)) {
    body = 'sticker';
    const c = kind.inner;
    text = c.body;
    sticker = {
      source: c.source,
      body: c.body,
      width: c.info.width === undefined ? undefined : Number(c.info.width),
      height: c.info.height === undefined ? undefined : Number(c.info.height),
      mimetype: c.info.mimetype,
    };
  } else if (MsgLikeKind.Poll.instanceOf(kind)) {
    body = 'poll';
    const c = kind.inner;
    text = c.question;
    poll = {
      question: c.question,
      disclosed: pollKindDisclosed(c.kind),
      maxSelections: Number(c.maxSelections),
      answers: c.answers.map((a) => ({ id: a.id, text: a.text })),
      votes: c.votes,
      ended: c.endTime !== undefined,
    };
  } else if (MsgLikeKind.UnableToDecrypt.instanceOf(kind)) {
    body = 'undecryptable';
    text = s('bipe_web_timeline_undecryptable');
  } else if (MsgLikeKind.Redacted.instanceOf(kind)) {
    body = 'deleted';
    text = event.isOwn ? s('bipe_app_message_deleted_own') : s('bipe_app_message_deleted');
  }
  let send: Send | undefined;
  let uploadPercent: number | undefined;
  if (event.isOwn) {
    const state = event.localSendState;
    if (state && EventSendState.NotSentYet.instanceOf(state)) {
      send = 'sending';
      const p = state.inner.progress?.progress;
      if (p && p.total > 0n) uploadPercent = Math.min(100, Math.round((Number(p.current) / Number(p.total)) * 100));
    } else if (state && EventSendState.SendingFailed.instanceOf(state)) send = 'failed';
    else send = 'sent';
  }
  const eventId = EventOrTransactionIdValue.EventId.instanceOf(event.eventOrTransactionId)
    ? event.eventOrTransactionId.inner.eventId
    : undefined;
  const live = body !== 'deleted' && body !== 'undecryptable';
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
    whoId: event.sender,
    whoAmbiguous: ProfileDetails.Ready.instanceOf(event.senderProfile) && event.senderProfile.inner.displayNameAmbiguous,
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
    uploadPercent,
    provider: event.lazyProvider,
    edited,
    reply,
    readBy: [...event.readReceipts.keys()].filter((id) => id !== ownUserId),
    mentions,
    ownUserId,
    // The check reads the text and its links (safetyText), on received messages only.
    scamKind: !event.isOwn && body === 'text' ? scamKindOf(text) : undefined,
    forwardCount: live && eventId ? forwardCountOf(event.lazyProvider.debugInfo().originalJson) : 0,
    // Spec message-forward: kind message only; polls and stickers have no forward.
    canForward: (body === 'text' || body === 'media' || body === 'location') && eventId !== undefined,
    sticker,
    poll,
    location,
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
  forward: (row: MessageRow) => void;
  vote: (row: MessageRow, answerIds: string[]) => Promise<void>;
  endPoll: (row: MessageRow) => Promise<void>;
  copyLink: (row: MessageRow) => void;
  /** Spec message-pins: 'pin' or 'unpin' when the account may change the pins of this message, else undefined. */
  pinState: (row: MessageRow) => 'pin' | 'unpin' | undefined;
  togglePin: (row: MessageRow) => void;
  moreReactions: (row: MessageRow) => void;
  senders: (row: MessageRow, pill: ReactionPill) => void;
  /** Spec read-receipts failed tick: send again (SendHandle.tryResend). */
  retry: (row: MessageRow) => void;
  /** Spec timeline-media upload overlay: stop the upload (SendHandle.abort). */
  cancelUpload: (row: MessageRow) => void;
};

export function TimelineRowView({
  row,
  isDm,
  dmPeerId,
  senders,
  on,
}: {
  row: Row;
  isDm: boolean;
  dmPeerId?: string;
  senders: SenderFacts;
  on: RowHandlers;
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
      return <MessageBubble row={row} isDm={isDm} dmPeerId={dmPeerId} senders={senders} on={on} />;
  }
}

function MessageBubble({
  row,
  isDm,
  dmPeerId,
  senders,
  on,
}: {
  row: MessageRow;
  isDm: boolean;
  dmPeerId?: string;
  senders: SenderFacts;
  on: RowHandlers;
}) {
  // Only the one other person of a DM goes without a name. A room flagged direct can hold messages from people who left.
  const showsSender = !isDm || dmPeerId === undefined || row.whoId !== dmPeerId || row.body === 'undecryptable';
  const role = isDm ? undefined : senders.roles.get(row.whoId);
  const actions: MessageAction[] = [];
  if (row.canReply)
    actions.push({ label: s('bipe_app_message_action_reply'), icon: arrowBendUpLeft, run: () => on.reply(row) });
  if (row.canEdit)
    actions.push({ label: s('bipe_app_message_action_edit'), icon: pencilSimple, run: () => on.edit(row) });
  if (row.canForward)
    actions.push({
      label: s('bipe_forward_action'),
      icon: arrowBendUpLeft,
      iconClass: '-scale-x-100',
      run: () => on.forward(row),
    });
  if (row.canReact)
    actions.push({
      label: s('bipe_app_message_action_more_reactions'),
      icon: smiley,
      run: () => on.moreReactions(row),
    });
  // Copy stays off while a money pause runs (MessageContextActions on iOS).
  if ((row.body === 'text' || row.media?.caption) && pauseLeft(row.key) === 0)
    actions.push({ label: s('bipe_app_copy'), icon: copy, run: () => on.copy(row) });
  if (row.eventId)
    actions.push({ label: s('bipe_app_message_action_copy_link'), icon: link, run: () => on.copyLink(row) });
  const pin = on.pinState(row);
  if (pin)
    actions.push({
      label: pin === 'pin' ? s('bipe_pin_action') : s('bipe_unpin_action'),
      icon: pin === 'pin' ? pushPin : pushPinSlash,
      run: () => on.togglePin(row),
    });
  if (row.canDelete)
    actions.push({ label: s('bipe_app_message_action_delete'), icon: trash, danger: true, run: () => on.remove(row) });
  return (
    <Bubble
      id={row.eventId ? `ev-${row.eventId}` : undefined}
      at={row.at}
      own={row.own}
      pills={row.pills}
      onSenders={(pill) => on.senders(row, pill)}
      onReact={row.canReact ? (key) => on.react(row, key) : undefined}
      actions={actions}
      plain={row.body === 'sticker'}
    >
      {!row.own && showsSender && (
        <span
          className={`flex items-center gap-1 text-caption font-bold ${row.body === 'undecryptable' ? 'text-ink-secondary' : 'text-accent-ink'}`}
        >
          {isDm ? row.who : senderLabel(row.who, row.whoId, row.whoAmbiguous, senders)}
          {role && <span className="text-accent-ink">· {s(ROLE_BADGE[role])}</span>}
          {isOfficial(row.whoId) && <OfficialBadge />}
        </span>
      )}
      {row.forwardCount > 0 && <ForwardLine count={row.forwardCount} />}
      {row.reply && <ReplyQuote reply={row.reply} onLoad={on.loadReply} />}
      {row.sticker ? (
        <StickerView sticker={row.sticker} />
      ) : row.poll ? (
        <PollCard
          poll={row.poll}
          ownUserId={row.ownUserId}
          canEnd={row.own && row.eventId !== undefined}
          onVote={(ids) => on.vote(row, ids)}
          onEnd={() => on.endPoll(row)}
        />
      ) : row.location ? (
        <LocationView location={row.location} />
      ) : row.media ? (
        <TimelineMediaRow
          media={row.media}
          upload={
            row.send === 'sending' && row.uploadPercent !== undefined
              ? { percent: row.uploadPercent, cancel: () => on.cancelUpload(row) }
              : undefined
          }
        />
      ) : row.body === 'text' ? (
        <p className="text-body break-words whitespace-pre-wrap text-ink-primary">
          <RichText text={row.text} mentions={row.mentions} canOpen={() => pauseLeft(row.key) === 0} />
        </p>
      ) : (
        <p className="flex items-start gap-1.5 text-subheadline text-ink-secondary italic">
          {row.body === 'deleted' && <Icon path={trash} size={14} className="mt-[3px] shrink-0 text-glyph-secondary" />}
          {row.body === 'undecryptable' && (
            <Icon path={lockSimple} size={14} className="mt-[3px] shrink-0 text-glyph-secondary" />
          )}
          {row.text}
        </p>
      )}
      {row.scamKind && <ScamWarningCard rowKey={row.key} kind={row.scamKind} />}
      <Footer
        at={row.at}
        edited={row.edited}
        send={row.body !== 'deleted' && row.body !== 'undecryptable' && row.body !== 'other' ? row.send : undefined}
        onRetry={() => on.retry(row)}
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
      onClick={() => {
        const el = document.getElementById(`ev-${reply.eventId}`);
        if (!el) return;
        el.scrollIntoView({ block: 'center' });
        el.dataset.highlight = 'true';
        setTimeout(() => delete el.dataset.highlight, 2000);
      }}
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

// The information pill: date separators and the chat start (SystemLine, TimelineRowView.swift). The web has no action rows yet.
function SystemLine({ text }: { text: string }) {
  return (
    <div className="flex justify-center py-1.5">
      <span className="rounded-full bg-bubble-own px-2.5 py-1 text-center text-caption text-accent-ink">
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
  onSenders,
  onReact,
  actions,
  plain,
  at,
  children,
}: {
  id?: string;
  /** The message time, for the floating date. */
  at?: number;
  own: boolean;
  pills: ReactionPill[];
  onSenders: (pill: ReactionPill) => void;
  onReact?: (key: string) => void;
  actions: MessageAction[];
  /** A sticker has no bubble (spec timeline-media). */
  plain?: boolean;
  children: ReactNode;
}) {
  const [menu, setMenu] = useState(false);
  const hasMenu = onReact !== undefined || actions.length > 0;
  return (
    <li id={id} data-at={at} className={`flex flex-col gap-1 ${own ? 'items-end pl-12' : 'items-start pr-12'}`}>
      <div className={`group relative flex max-w-full items-center gap-1 ${own ? 'flex-row-reverse' : ''}`}>
        <div
          className={`flex max-w-full min-w-0 flex-col gap-1 rounded-2xl ${plain ? '' : `px-3 py-2 ${own ? 'bg-bubble-own' : 'bg-bubble-other'}`}`}
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
      {pills.length > 0 && (
        <ReactionPills pills={pills} onToggle={onReact ?? (() => undefined)} onSenders={onSenders} />
      )}
    </li>
  );
}

// The time, the edited label (message-edit) and, on an own message, the send state (spec read-receipts):
// a clock while it sends, a tick once the server has it, a warning when it failed.
function Footer({ at, edited, send, onRetry }: { at: number; edited: boolean; send?: Send; onRetry: () => void }) {
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
        // A tap sends again, as bipe_app_timeline_failed says (ReceiptTick .failed on iOS).
        <button type="button" className="flex items-center gap-1 text-trust-danger-ink" onClick={onRetry}>
          <Icon path={warningCircle} size={14} />
          {s('bipe_app_timeline_failed')}
        </button>
      )}
    </span>
  );
}

// The text with each mention as a pill: accent.wash with accent.ink, or accent.fill when it names the account.
// The text of a message (MessageRichText.swift): links in accent.ink that open in a new tab (Bipe web
// links open in place), matrix.to mentions as pills on surface.sunken in accent.ink. Links stay shut while
// a money pause runs (canOpen).
function RichText({ text, mentions, canOpen }: { text: string; mentions: MentionLink[]; canOpen: () => boolean }) {
  const parts: ReactNode[] = [];
  let key = 0;
  const pushLinked = (chunk: string) => {
    for (const run of linkify(chunk)) {
      if (!run.link) {
        parts.push(run.text);
        continue;
      }
      const href = run.link;
      parts.push(
        <a
          key={key++}
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          className="text-accent-ink hover:underline"
          onClick={(e) => {
            if (!canOpen()) return e.preventDefault();
            const route = internalRoute(href);
            if (route && /^https?:/i.test(href)) {
              e.preventDefault();
              navigate(route);
            }
          }}
        >
          {run.text}
        </a>,
      );
    }
  };
  let rest = text;
  for (const m of mentions) {
    const at = rest.indexOf(m.text);
    if (at === -1) continue;
    pushLinked(rest.slice(0, at));
    parts.push(
      <span key={key++} className="rounded-md bg-surface-sunken px-1 text-accent-ink">
        {m.text}
      </span>,
    );
    rest = rest.slice(at + m.text.length);
  }
  pushLinked(rest);
  return <>{parts}</>;
}

// The forward line (ForwardLine, TimelineRowView.swift; spec chat-timeline, bipe-chat/bipe#933): a forward
// arrow and Encaminhada, or Encaminhada muitas vezes, in caption italic ink.secondary.
function ForwardLine({ count }: { count: number }) {
  const many = count >= voiceMedia.forwardedManyTimes;
  return (
    <span className="flex items-center gap-1 text-caption text-ink-secondary italic">
      <Icon path={arrowBendUpLeft} size={12} className="-scale-x-100" />
      {many ? s('bipe_forward_line_many') : s('bipe_forward_line')}
    </span>
  );
}

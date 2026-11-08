// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Specs work-chat-bar and work-notes on the web (bipe ADR 0284 phase A): in a customer chat of a work account with two
// or more people, a line with who has the chat and its status, Pegar, Passar para outra pessoa, Devolver and Notas;
// the banner when another person has it; claim on reply; the reply-by event after each send; the notes sheet. The
// customer sees nothing of it. With one person, none of this shows.

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatAssignmentStatus } from '../../bipe/generated/business-api';
import {
  EventOrTransactionId,
  EventSendState,
  type ClientLike as Client,
  type TimelineItemLike as TimelineItem,
} from '../../generated/matrix_sdk_ffi';
import { Sheet } from '../../MessageSheets';
import { paperPlaneRight } from '../../phosphor/icons';
import { Icon } from '../../ui/Icon';
import { locale, s } from '../../ui/i18n';
import { WorkError } from '../business-client';
import { assigneeOf, chatQueueFor, statusOf, useChatQueue } from '../chat-queue';
import { NOTE_MAX_LENGTH, useChatNotes, useReplyBy, workNotesFor } from '../work-notes';

const STATUSES: { value: ChatAssignmentStatus; key: 'bipe_chat_status_open' | 'bipe_chat_status_waiting' | 'bipe_chat_status_solved' }[] = [
  { value: 'open', key: 'bipe_chat_status_open' },
  { value: 'waiting', key: 'bipe_chat_status_waiting' },
  { value: 'solved', key: 'bipe_chat_status_solved' },
];

const noteTime = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' });

const isTaken = (e: unknown) => e instanceof WorkError && e.code === 'chat_taken';

/** The state and actions of spec work-chat-bar for one chat. customer: a DM of a company account with a customer. */
export function useWorkChat(client: Client, roomId: string, isCompany: boolean, customer: boolean) {
  const queue = useChatQueue(client, isCompany);
  const on = queue.on && customer;
  const assignee = on ? assigneeOf(queue, roomId) : undefined;
  const [phase, setPhase] = useState<'idle' | 'updating' | 'failed'>('idle');
  const run = useCallback(
    (next: string | null, status?: ChatAssignmentStatus) => {
      setPhase('updating');
      chatQueueFor(client)
        .update(roomId, next, status)
        .then(
          () => setPhase('idle'),
          // chat_taken: the queue read the chats again, and the banner says who has it now.
          (e: unknown) => setPhase(isTaken(e) ? 'idle' : 'failed'),
        );
    },
    [client, roomId],
  );
  /** Claim on reply: takes a chat in the pool before the first send. false only when another person took it first. */
  const claim = useCallback(async (): Promise<boolean> => {
    if (!on || assignee !== undefined || !queue.mySeat) return true;
    try {
      await chatQueueFor(client).update(roomId, queue.mySeat);
      return true;
    } catch (e) {
      // The record is a help, not a lock: only a chat that another person has stops the send.
      return !isTaken(e);
    }
  }, [client, roomId, on, assignee, queue.mySeat]);
  return {
    on,
    queue,
    assignee,
    mine: assignee !== undefined && assignee === queue.mySeat,
    takenByOther: assignee !== undefined && assignee !== queue.mySeat,
    assigneeName: assignee === undefined ? undefined : queue.names.get(assignee),
    status: statusOf(queue, roomId),
    phase,
    take: () => run(queue.mySeat ?? null),
    give: (seat: string) => run(seat),
    release: () => run(null),
    setStatus: (status: ChatAssignmentStatus) => run(assignee ?? null, status),
    claim,
  };
}

export type WorkChat = ReturnType<typeof useWorkChat>;

/** The line above the timeline. */
export function WorkChatBar({ chat, onNotes }: { chat: WorkChat; onNotes: () => void }) {
  const [giving, setGiving] = useState(false);
  if (!chat.on) return null;
  const busy = chat.phase === 'updating';
  const line = chat.mine
    ? s('bipe_chat_assignee_me')
    : chat.assigneeName
      ? s('bipe_chat_assignee_other', chat.assigneeName)
      : s('bipe_chat_assignee_none');
  const chip = (title: string, run: () => void, pressed?: boolean) => (
    <button
      key={title}
      type="button"
      disabled={busy}
      aria-pressed={pressed}
      className={`min-h-9 shrink-0 rounded-full px-3.5 text-subheadline font-semibold disabled:opacity-50 ${pressed ? 'bg-accent-fill text-ink-on-accent' : 'bg-surface-sunken text-ink-primary'}`}
      onClick={run}
    >
      {title}
    </button>
  );
  const others = chat.queue.people.filter((seat) => seat.id !== chat.assignee);
  return (
    <div className="flex shrink-0 flex-col gap-2 border-b-[0.5px] border-line-subtle bg-surface-raised px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-subheadline font-semibold text-ink-primary">{line}</span>
        {!chat.mine && chip(s('bipe_chat_take'), chat.take)}
        {others.length > 0 && chip(s('bipe_chat_give'), () => setGiving(true))}
        {chat.assignee !== undefined && chip(s('bipe_chat_release'), chat.release)}
        {chip(s('bipe_chat_notes'), onNotes)}
      </div>
      <div role="group" className="flex flex-wrap gap-2">
        {STATUSES.map((st) => chip(s(st.key), () => chat.setStatus(st.value), chat.status === st.value))}
      </div>
      {chat.takenByOther && chat.assigneeName && (
        <p role="status" className="text-footnote text-ink-secondary">
          {s('bipe_chat_taken_banner', chat.assigneeName)}
        </p>
      )}
      {chat.phase === 'failed' && <p className="text-footnote text-trust-danger-ink">{s('bipe_chat_assign_error')}</p>}
      {giving && (
        <Sheet title={s('bipe_chat_give_title')} onClose={() => setGiving(false)}>
          <ul className="flex flex-col">
            {others.map((seat) => (
              <li key={seat.id}>
                <button
                  type="button"
                  className="flex min-h-11 w-full items-center border-b-[0.5px] border-line-subtle px-4 text-left text-body text-ink-primary hover:bg-surface-sunken"
                  onClick={() => {
                    setGiving(false);
                    chat.give(seat.id);
                  }}
                >
                  {seat.id === chat.queue.mySeat ? s('bipe_team_you') : seat.name}
                </button>
              </li>
            ))}
          </ul>
        </Sheet>
      )}
    </div>
  );
}

/** Spec work-chat-bar reply: in a chat another person has, the composer first shows bipe_chat_taken_reply. */
export function TakenReplyBar({ onReply }: { onReply: () => void }) {
  return (
    <div className="flex items-center justify-center px-4 py-3">
      <button
        type="button"
        className="min-h-11 rounded-full bg-surface-sunken px-5 text-subheadline font-semibold text-accent-ink"
        onClick={onReply}
      >
        {s('bipe_chat_taken_reply')}
      </button>
    </div>
  );
}

function sentEventId(item: TimelineItem): string | undefined {
  const event = item.asEvent();
  if (!event) return undefined;
  if (EventOrTransactionId.EventId.instanceOf(event.eventOrTransactionId)) return event.eventOrTransactionId.inner.eventId;
  const state = event.localSendState;
  return state && EventSendState.Sent.instanceOf(state) ? state.inner.eventId : undefined;
}

/**
 * Spec work-chat-bar reply: after each send from this browser, one reply-by event to the notes room. A send from this
 * browser is a local echo of the send queue; once the server gave it an event ID, the event goes out once.
 */
export function useReplyBySender(client: Client, roomId: string, items: TimelineItem[], chat: WorkChat) {
  const tracked = useRef(new Set<string>());
  const done = useRef(new Set<string>());
  const seat = chat.on ? chat.queue.mySeat : undefined;
  useEffect(() => {
    for (const item of items) {
      const event = item.asEvent();
      if (!event || !event.isOwn) continue;
      const key = item.uniqueId().id;
      if (done.current.has(key)) continue;
      if (event.localSendState !== undefined) tracked.current.add(key);
      if (!tracked.current.has(key)) continue;
      const eventId = sentEventId(item);
      if (!eventId) continue;
      tracked.current.delete(key);
      done.current.add(key);
      if (seat)
        workNotesFor(client)
          .sendReplyBy(roomId, eventId, seat)
          .catch((e: unknown) => console.warn('[trace] reply-by failed', e));
    }
  }, [client, roomId, items, seat]);
}

/** Spec work-chat-bar notes: the seat name on a reply with a valid reply-by event, inside the account. */
export function useReplyByName(client: Client, roomId: string, chat: WorkChat): (eventId: string) => string | undefined {
  const names = chat.queue.names;
  const replyBy = useReplyBy(client, roomId, [...names.keys()], chat.on);
  return (eventId) => {
    const seat = replyBy.get(eventId);
    return seat === undefined ? undefined : names.get(seat);
  };
}

/** Spec work-notes: the notes of this chat, oldest first, and a field to write one. */
export function WorkNotesSheet({
  client,
  roomId,
  accountName,
  onClose,
}: {
  client: Client;
  roomId: string;
  accountName: string;
  onClose: () => void;
}) {
  const notes = useChatNotes(client, roomId, true);
  const [body, setBody] = useState('');
  const [phase, setPhase] = useState<'ready' | 'sending' | 'failed'>('ready');
  const end = useRef<HTMLLIElement>(null);
  useEffect(() => end.current?.scrollIntoView({ block: 'end' }), [notes.length]);
  const text = body.trim();
  const send = () => {
    if (!text || phase === 'sending') return;
    setPhase('sending');
    workNotesFor(client)
      .sendNote(roomId, text)
      .then(
        () => {
          setBody('');
          setPhase('ready');
        },
        (e: unknown) => {
          console.warn('[trace] work note failed', e);
          setPhase('failed');
        },
      );
  };
  return (
    <Sheet
      title={s('bipe_chat_notes_title')}
      onClose={onClose}
      footer={
        <form
          className="flex flex-col gap-1 border-t-[0.5px] border-line-subtle px-3 py-2"
          onSubmit={(e) => {
            e.preventDefault();
            send();
          }}
        >
          {phase === 'failed' && <p className="px-1 text-footnote text-trust-danger-ink">{s('bipe_chat_notes_error')}</p>}
          <div className="flex items-end gap-2">
            <div className="bipe-glass flex min-w-0 flex-1 rounded-[22px] pr-1 pl-4">
              <textarea
                className="max-h-[132px] min-h-11 w-full resize-none bg-transparent py-[11px] text-body text-ink-primary outline-none [field-sizing:content] placeholder:text-ink-disabled"
                rows={1}
                maxLength={NOTE_MAX_LENGTH}
                value={body}
                placeholder={s('bipe_chat_notes_placeholder')}
                aria-label={s('bipe_chat_notes_placeholder')}
                onChange={(e) => setBody(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    send();
                  }
                }}
              />
            </div>
            <button
              type="submit"
              disabled={!text || phase === 'sending'}
              className={`grid size-11 shrink-0 place-items-center rounded-full ${text ? 'bg-accent-fill text-ink-on-accent' : 'bipe-glass text-ink-disabled'}`}
              aria-label={s('bipe_app_timeline_send')}
            >
              <Icon path={paperPlaneRight} size={22} />
            </button>
          </div>
        </form>
      }
    >
      <p className="px-4 pt-1 pb-3 text-footnote text-ink-secondary">{s('bipe_chat_notes_hint', accountName)}</p>
      {notes.length === 0 ? (
        <p className="px-6 py-8 text-center text-subheadline text-ink-secondary">{s('bipe_chat_notes_empty')}</p>
      ) : (
        <ol className="flex flex-col gap-2 px-4 pb-4">
          {notes.map((note) => (
            <li key={note.eventId} className="flex flex-col gap-1 rounded-2xl bg-surface-raised px-3 py-2">
              <p className="text-body break-words whitespace-pre-wrap text-ink-primary">{note.body}</p>
              <time className="self-end text-caption2 text-ink-secondary" dateTime={new Date(note.at).toISOString()}>
                {noteTime.format(note.at)}
              </time>
            </li>
          ))}
          <li ref={end} aria-hidden />
        </ol>
      )}
    </Sheet>
  );
}

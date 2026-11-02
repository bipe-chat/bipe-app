// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// One chat: the inline bar, the timeline over the chat background, the composer
// (ChatTimelineView.swift, TimelineRowView.swift; specs chat-timeline, timeline-rows, composer).
// The column is at most 760 px wide, centred, like the frame of ChatTimelineView.

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  EditedContent,
  TimelineDiff,
  messageEventContentFromMarkdown,
  type RoomLike as Room,
  type TaskHandleLike as TaskHandle,
  type TimelineLike as Timeline,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import { arrowBendUpLeft, caretLeft, check, paperPlaneRight, pencilSimple, x } from './phosphor/icons';
import { AttachButton, MAX_ITEMS, UploadPreview } from './Attach';
import { ChatInfo } from './ChatInfo';
import { useRoomFace } from './room-face';
import { itemRow, TimelineRowView, type MessageRow, type RowHandlers } from './TimelineRows';
import { Avatar } from './ui/Avatar';
import { ChatBackground } from './ui/ChatBackground';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { applyDiffs, describe, type DiffVariants } from './util';

// The composer's mode (specs message-reply, message-edit): a reply bar or an edit bar above the field.
type Mode = { kind: 'reply'; row: MessageRow } | { kind: 'edit'; row: MessageRow } | undefined;

export function RoomTimeline({ room, onBack }: { room: Room; onBack: () => void }) {
  const [items, setItems] = useState<TimelineItem[]>([]);
  const [draft, setDraft] = useState('');
  const [note, setNote] = useState('');
  // Files picked, pasted or dropped, waiting in the upload preview.
  const [pending, setPending] = useState<File[]>();
  const [dropping, setDropping] = useState(false);
  const [showsInfo, setShowsInfo] = useState(false);
  const [mode, setMode] = useState<Mode>();
  const [deleting, setDeleting] = useState<MessageRow>();
  const [toast, setToast] = useState('');
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 2000);
    return () => clearTimeout(timer);
  }, [toast]);
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
  };
  const face = useRoomFace(room);
  const name = face.name;
  // Group rows show the sender's name; DM rows do not. Until RoomInfo comes, no names.
  const isDm = face.isDirect ?? true;
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
            onClick={() => setShowsInfo((v) => !v)}
          >
            <Avatar name={name} size={32} mxcUrl={face.avatarUrl} />
            <h2 className="truncate text-headline text-ink-primary">{name}</h2>
          </button>
        </header>
        {/* column-reverse keeps the newest message in view as rows come in. */}
        <div className="flex min-h-0 flex-1 flex-col-reverse overflow-y-auto">
          <ol className="mx-auto flex w-full max-w-[760px] flex-col gap-1.5 px-3 py-2">
            {rows.map((r) => (
              <TimelineRowView key={r.key} row={r} isDm={isDm} on={on} />
            ))}
          </ol>
        </div>
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
          <form
            className="flex items-end gap-2 px-3 py-2"
            onSubmit={(e) => {
              e.preventDefault();
              const text = draft.trim();
              const t = timeline.current;
              if (!text || !t) return;
              setDraft('');
              setMode(undefined);
              const content = messageEventContentFromMarkdown(text);
              const sent =
                mode?.kind === 'edit'
                  ? t.edit(mode.row.itemId, new EditedContent.RoomMessage({ content }))
                  : mode?.kind === 'reply' && mode.row.eventId
                    ? t.sendReply(content, mode.row.eventId)
                    : t.send(content);
              sent.catch((err: unknown) => setNote(describe(err)));
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
              aria-label={mode?.kind === 'edit' ? s('bipe_app_message_save_edit') : s('bipe_app_timeline_send')}
            >
              <Icon path={mode?.kind === 'edit' ? check : paperPlaneRight} size={22} />
            </button>
          </form>
        </div>
      </section>
      {showsInfo && <ChatInfo room={room} face={face} onClose={() => setShowsInfo(false)} />}
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
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);
  return (
    // The dim behind an alert is black at 40% in both schemes, as the system draws it; no token covers it.
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-6" onClick={onCancel}>
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="delete-title"
        className="flex w-full max-w-[300px] flex-col overflow-hidden rounded-[14px] bg-surface-raised text-center"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-col gap-1 p-4">
          <h3 id="delete-title" className="text-headline text-ink-primary">
            {s('bipe_app_message_delete_title')}
          </h3>
          <p className="text-footnote text-ink-primary">{s('bipe_app_message_delete_body')}</p>
        </div>
        <div className="grid grid-cols-2 border-t-[0.5px] border-line-subtle">
          <button type="button" autoFocus className="min-h-11 text-body text-accent-ink" onClick={onCancel}>
            {s('bipe_app_cancel')}
          </button>
          <button
            type="button"
            className="min-h-11 border-l-[0.5px] border-line-subtle text-headline text-trust-danger-ink"
            onClick={onDelete}
          >
            {s('bipe_app_message_action_delete')}
          </button>
        </div>
      </div>
    </div>
  );
}

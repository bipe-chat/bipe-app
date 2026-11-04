// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The confirm sheets and alerts of the chat actions (specs chat-notifications, dm-delete, block-person,
// report-chat, group-leave), the same steps and strings as the apps.

import { useEffect, useState } from 'react';
import { deleteDm, notifyMode, reportChat, setNotifyMode, type NotifyMode } from './chat-actions';
import type { RoomLike as Room } from './generated/matrix_sdk_ffi';
import { useClient } from './media';
import { check } from './phosphor/icons';
import { Alert } from './ui/Alert';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';

/** A sheet card over the page: title bar with Cancelar, Escape and a click outside close it. */
function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    // The dim behind a sheet is black at 40% in both schemes, as the system draws it; no token covers it.
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 md:p-6" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="flex h-full w-full max-w-[440px] flex-col bg-grouped-screen md:h-auto md:max-h-[85vh] md:rounded-2xl"
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
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">{children}</div>
      </div>
    </div>
  );
}

// Spec chat-notifications: a group has quiet (the default), all, mute; a DM all and mute (ADR 0026, 0141).
export function NotifySheet({ room, isDm, onClose }: { room: Room; isDm: boolean; onClose: () => void }) {
  const client = useClient();
  const [mode, setMode] = useState<NotifyMode>();
  const [error, setError] = useState(false);
  useEffect(() => {
    let stopped = false;
    notifyMode(client, room, isDm)
      .then((m) => !stopped && setMode(m))
      .catch(() => !stopped && setError(true));
    return () => {
      stopped = true;
    };
  }, [client, room, isDm]);
  const options: { mode: Exclude<NotifyMode, 'mentionsLegacy'>; title: string; footer?: string }[] = isDm
    ? [
        { mode: 'all', title: s('bipe_app_notify_all') },
        { mode: 'mute', title: s('bipe_app_notify_mute') },
      ]
    : [
        { mode: 'quiet', title: s('bipe_app_notify_quiet'), footer: s('bipe_app_notify_quiet_footer') },
        { mode: 'all', title: s('bipe_app_notify_all') },
        { mode: 'mute', title: s('bipe_app_notify_mute') },
      ];
  const choose = (to: Exclude<NotifyMode, 'mentionsLegacy'>) => {
    if (!mode || to === mode) return;
    const before = mode;
    setMode(to);
    setError(false);
    setNotifyMode(client, room, isDm, before, to).catch(() => {
      setMode(before);
      setError(true);
    });
  };
  return (
    <Sheet title={s('bipe_settings_notifications')} onClose={onClose}>
      {!mode && !error ? (
        <div className="grid place-items-center py-8">
          <Spinner />
        </div>
      ) : (
        <ul role="radiogroup" className="overflow-hidden rounded-xl bg-grouped-cell">
          {mode === 'mentionsLegacy' && (
            <li className="flex min-h-11 items-center gap-2 px-4 text-body text-ink-primary">
              <span className="flex-1">{s('bipe_app_notify_mentions_legacy')}</span>
              <Icon path={check} size={18} className="text-accent-ink" />
            </li>
          )}
          {options.map((o) => (
            <li key={o.mode} className="border-t-[0.5px] border-line-subtle first:border-t-0">
              <button
                type="button"
                role="radio"
                aria-checked={mode === o.mode}
                className="flex min-h-11 w-full items-center gap-2 px-4 py-2 text-left"
                onClick={() => choose(o.mode)}
              >
                <span className="flex flex-1 flex-col">
                  <span className="text-body text-ink-primary">{o.title}</span>
                  {o.footer && <span className="text-footnote text-ink-secondary">{o.footer}</span>}
                </span>
                {mode === o.mode && <Icon path={check} size={18} className="text-accent-ink" />}
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && <p className="text-footnote text-trust-danger-ink">{s('bipe_app_notify_error')}</p>}
    </Sheet>
  );
}

// Spec dm-delete: clears the DM on all of the user's devices; the user stays in the room.
export function DmDeleteAlert({
  room,
  latestTs,
  onDone,
  onClose,
}: {
  room: Room;
  latestTs: number;
  onDone: () => void;
  onClose: () => void;
}) {
  const client = useClient();
  const [phase, setPhase] = useState<'confirm' | 'working' | 'failed'>('confirm');
  return (
    <Alert
      title={s('bipe_dm_delete_title')}
      action={phase === 'working' ? s('bipe_app_dm_deleting') : s('bipe_dm_delete_confirm')}
      busy={phase === 'working'}
      onCancel={onClose}
      onAction={() => {
        setPhase('working');
        deleteDm(client, room, latestTs)
          .then(onDone)
          .catch(() => setPhase('failed'));
      }}
    >
      <p>{s('bipe_dm_delete_body')}</p>
      {phase === 'failed' && <p className="text-trust-danger-ink">{s('bipe_dm_delete_error')}</p>}
    </Alert>
  );
}

// Spec block-person: block (destructive) or unblock (normal), the Matrix ignore list.
export function BlockAlert({
  userId,
  name,
  blocked,
  onClose,
}: {
  userId: string;
  name: string;
  blocked: boolean;
  onClose: () => void;
}) {
  const client = useClient();
  const [phase, setPhase] = useState<'confirm' | 'working' | 'failed'>('confirm');
  return (
    <Alert
      title={blocked ? s('bipe_app_unblock_title', name) : s('bipe_app_block_title', name)}
      action={blocked ? s('bipe_app_unblock_confirm') : s('bipe_app_block_confirm')}
      destructive={!blocked}
      busy={phase === 'working'}
      onCancel={onClose}
      onAction={() => {
        setPhase('working');
        (blocked ? client.unignoreUser(userId) : client.ignoreUser(userId))
          .then(onClose)
          .catch(() => setPhase('failed'));
      }}
    >
      <p>{blocked ? s('bipe_app_unblock_body') : s('bipe_dm_decline_block_description')}</p>
      {phase === 'failed' && (
        <p className="text-trust-danger-ink">{blocked ? s('bipe_app_unblock_error') : s('bipe_app_block_error')}</p>
      )}
    </Alert>
  );
}

// Spec report-chat: an optional reason, then also block or delete (DM) or also leave (group).
export function ReportSheet({
  room,
  isDm,
  peer,
  latestTs,
  onDone,
  onClose,
}: {
  room: Room;
  isDm: boolean;
  peer?: string;
  latestTs?: number;
  onDone: (left: boolean) => void;
  onClose: () => void;
}) {
  const client = useClient();
  const [reason, setReason] = useState('');
  const [alsoBlock, setAlsoBlock] = useState(false);
  const [alsoDelete, setAlsoDelete] = useState(false);
  const [alsoLeave, setAlsoLeave] = useState(false);
  const [phase, setPhase] = useState<'editing' | 'sending' | 'sent' | 'failed' | 'leaveFailed'>('editing');
  const toggle = (label: string, on: boolean, set: (v: boolean) => void) => (
    <label className="flex min-h-11 items-center gap-3 px-4">
      <span className="flex-1 text-body text-ink-primary">{label}</span>
      <input
        type="checkbox"
        className="size-5 accent-[var(--bipe-accent-fill)]"
        checked={on}
        onChange={(e) => set(e.target.checked)}
      />
    </label>
  );
  return (
    <Sheet title={isDm ? s('bipe_dm_report') : s('bipe_app_report_group')} onClose={onClose}>
      {phase === 'sent' || phase === 'leaveFailed' ? (
        <div className="flex flex-col gap-2 text-center">
          <p className="text-body text-ink-primary">{isDm ? s('bipe_dm_reported') : s('bipe_app_group_reported')}</p>
          {phase === 'leaveFailed' && (
            <p className="text-footnote text-trust-danger-ink">{s('bipe_dm_report_leave_failed_body')}</p>
          )}
        </div>
      ) : (
        <>
          <label className="flex flex-col gap-1.5">
            <span className="px-4 text-footnote text-ink-secondary">{s('bipe_app_report_reason')}</span>
            <textarea
              className="min-h-24 resize-none rounded-xl bg-grouped-cell p-3 text-body text-ink-primary outline-none focus-visible:outline-2 focus-visible:outline-accent-fill"
              placeholder={s('bipe_app_report_reason_placeholder')}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <div className="overflow-hidden rounded-xl bg-grouped-cell">
            {isDm && peer && toggle(s('bipe_app_report_also_block'), alsoBlock, setAlsoBlock)}
            {isDm && latestTs !== undefined && toggle(s('bipe_app_report_also_delete'), alsoDelete, setAlsoDelete)}
            {!isDm && toggle(s('bipe_app_report_also_leave'), alsoLeave, setAlsoLeave)}
          </div>
          <p className="px-4 text-footnote text-ink-secondary">
            {isDm ? s('bipe_dm_report_footer') : s('bipe_app_report_group_footer')}
          </p>
          {phase === 'failed' && (
            <p className="text-footnote text-trust-danger-ink">{s('bipe_app_report_chat_error')}</p>
          )}
          <button
            type="button"
            disabled={phase === 'sending'}
            className="min-h-[50px] rounded-full bg-surface-sunken px-6 text-headline text-trust-danger-ink disabled:opacity-50"
            onClick={() => {
              setPhase('sending');
              reportChat(client, room, {
                reason,
                block: alsoBlock ? peer : undefined,
                deleteAt: alsoDelete ? latestTs : undefined,
                leave: alsoLeave,
              })
                .then(({ leaveFailed }) => {
                  setPhase(leaveFailed ? 'leaveFailed' : 'sent');
                  if (alsoDelete || (alsoLeave && !leaveFailed)) setTimeout(() => onDone(true), 1200);
                })
                .catch(() => setPhase('failed'));
            }}
          >
            {s('bipe_app_report_send_action')}
          </button>
        </>
      )}
    </Sheet>
  );
}

// Spec group-leave: warns when the user is alone in the group. TODO: the last-admin warning needs the
// members' roles (group-members); the web does not read them yet.
export function LeaveAlert({ room, onDone, onClose }: { room: Room; onDone: () => void; onClose: () => void }) {
  const [alone, setAlone] = useState(false);
  const [phase, setPhase] = useState<'confirm' | 'working' | 'failed'>('confirm');
  useEffect(() => {
    let stopped = false;
    room
      .roomInfo()
      .then((i) => !stopped && setAlone(Number(i.joinedMembersCount) <= 1))
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [room]);
  return (
    <Alert
      title={s('bipe_app_group_leave_title')}
      action={phase === 'working' ? s('bipe_app_group_leaving') : s('bipe_app_group_leave_confirm')}
      busy={phase === 'working'}
      onCancel={onClose}
      onAction={() => {
        setPhase('working');
        room
          .leave()
          .then(onDone)
          .catch(() => setPhase('failed'));
      }}
    >
      <p>{alone ? s('bipe_app_group_leave_alone') : s('bipe_app_group_leave_body')}</p>
      {phase === 'failed' && <p className="text-trust-danger-ink">{s('bipe_app_group_leave_error')}</p>}
    </Alert>
  );
}

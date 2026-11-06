// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Specs chat-requests and chat-request (ChatRequestsView.swift). The box lists DM invites from people
// with no chat yet, newest first; a request shows the person and Aceitar, Recusar, Recusar e bloquear and
// Denunciar conversa. Photos stay hidden until the person accepts (bipe_privacy_requests_hide_photos is
// a phone setting the web cannot read, so the web keeps the safe value: initials only).

import { useState } from 'react';
import type { ChatSummary } from './chat-model';
import { useClient } from './media';
import { ProfileFactsLines, RequestTrust, useHandle } from './Trust';
import { Avatar } from './ui/Avatar';
import { s } from './ui/i18n';
import { isOfficial } from './registries';
import { NavBar, OfficialBadge } from './ui/List';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

/** The person behind a request: the inviter, else the room's face. */
export function requesterOf(request: ChatSummary): { userId?: string; name: string } {
  const inviter = request.info?.inviter;
  return { userId: inviter?.userId, name: inviter?.displayName ?? request.face.name };
}

export function RequestsList({
  requests,
  onOpen,
  onBack,
}: {
  requests: ChatSummary[];
  onOpen: (request: ChatSummary) => void;
  onBack: () => void;
}) {
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_requests_title')} onBack={onBack} />
      {requests.length === 0 ? (
        <p className="px-6 py-12 text-center text-ink-secondary">{s('bipe_app_requests_empty')}</p>
      ) : (
        <ul className="min-h-0 flex-1 overflow-y-auto">
          {requests.map((r) => {
            const who = requesterOf(r);
            return (
              <li key={r.roomId}>
                <button
                  type="button"
                  className="flex w-full items-center gap-3 pl-4 text-left hover:bg-surface-raised"
                  onClick={() => onOpen(r)}
                >
                  <Avatar name={who.name} size={48} userId={who.userId} />
                  <span className="flex min-h-[72px] min-w-0 flex-1 flex-col justify-center border-b-[0.5px] border-line-subtle pr-4">
                    <span className="flex items-center gap-1.5">
                      <span className="min-w-0 truncate text-headline text-ink-primary">{who.name}</span>
                      {isOfficial(who.userId) && <OfficialBadge />}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

type Outcome = { kind: 'idle' } | { kind: 'working' } | { kind: 'error' } | { kind: 'reported' };

// The trust block (spec chat-request): the @handle, the profile facts and Nome não verificado or the
// external line from bipe-api and the generated rules (Trust.tsx), and the contact collision line.
export function RequestScreen({
  request,
  contactNames,
  onAccepted,
  onDone,
  onBack,
}: {
  request: ChatSummary;
  /** The names of people with a joined DM, for the contact collision check. */
  contactNames: string[];
  onAccepted: () => void;
  onDone: () => void;
  onBack: () => void;
}) {
  const client = useClient();
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'idle' });
  const [confirmBlock, setConfirmBlock] = useState(false);
  const who = requesterOf(request);
  const handle = useHandle(who.userId);
  const run = (work: () => Promise<void>, after: () => void) => {
    setOutcome({ kind: 'working' });
    work()
      .then(after)
      .catch((e: unknown) => {
        console.warn('[trace] request action failed', describe(e));
        setOutcome({ kind: 'error' });
      });
  };
  const busy = outcome.kind === 'working';
  const button = 'min-h-[50px] w-full rounded-full px-6 text-headline disabled:opacity-50';
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_requests_title')} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[480px] min-h-0 flex-1 flex-col items-center gap-4 overflow-y-auto p-6 text-center">
        <Avatar name={who.name} size={72} userId={who.userId} />
        <div className="flex items-center gap-1.5">
          <h3 className="text-title2 text-ink-primary">{who.name}</h3>
          {isOfficial(who.userId) && <OfficialBadge />}
        </div>
        {handle && <p className="text-subheadline text-ink-secondary">@{handle}</p>}
        <ProfileFactsLines userId={who.userId} />
        <RequestTrust userId={who.userId} name={who.name} contactNames={contactNames} />
        {outcome.kind === 'error' && (
          <p className="text-subheadline text-trust-danger-ink">{s('bipe_error_generic')}</p>
        )}
        {outcome.kind === 'reported' ? (
          <p className="text-body text-ink-primary">{s('bipe_dm_reported_and_left')}</p>
        ) : (
          <div className="flex w-full flex-col gap-3 pt-2">
            {busy && <Spinner />}
            <button
              type="button"
              disabled={busy}
              className={`${button} bg-accent-fill text-ink-on-accent hover:bg-accent-fill-pressed`}
              onClick={() => run(() => request.room.join(), onAccepted)}
            >
              {s('bipe_app_request_accept')}
            </button>
            <button
              type="button"
              disabled={busy}
              className={`${button} bg-surface-sunken text-accent-ink`}
              onClick={() => run(() => request.room.leave(), onDone)}
            >
              {s('bipe_app_request_decline')}
            </button>
            {who.userId && (
              <>
                {confirmBlock ? (
                  <div className="flex flex-col gap-3 rounded-2xl bg-surface-raised p-4">
                    <p className="text-subheadline text-ink-primary">{s('bipe_dm_decline_block_alert', who.name)}</p>
                    <button
                      type="button"
                      disabled={busy}
                      className={`${button} bg-surface-sunken text-trust-danger-ink`}
                      onClick={() =>
                        run(async () => {
                          await request.room.leave();
                          await client.ignoreUser(who.userId!);
                        }, onDone)
                      }
                    >
                      {s('bipe_app_request_decline_block')}
                    </button>
                    <button
                      type="button"
                      className="min-h-11 text-body text-accent-ink"
                      onClick={() => setConfirmBlock(false)}
                    >
                      {s('bipe_app_cancel')}
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    disabled={busy}
                    className={`${button} bg-surface-sunken text-trust-danger-ink`}
                    onClick={() => setConfirmBlock(true)}
                  >
                    {s('bipe_app_request_decline_block')}
                  </button>
                )}
                <p className="text-footnote text-ink-secondary">{s('bipe_dm_decline_block_description')}</p>
              </>
            )}
            <button
              type="button"
              disabled={busy}
              className={`${button} bg-surface-sunken text-trust-danger-ink`}
              onClick={() =>
                run(
                  async () => {
                    await request.room.reportRoom('');
                    await request.room.leave();
                  },
                  () => setOutcome({ kind: 'reported' }),
                )
              }
            >
              {s('bipe_dm_report')}
            </button>
            <p className="text-footnote text-ink-secondary">{s('bipe_dm_decline_report_description')}</p>
          </div>
        )}
      </div>
    </div>
  );
}

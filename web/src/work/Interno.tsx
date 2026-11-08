// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The Interno screen (bipe-shared product/screens/interno.json, bipe ADR 0284 phase B): Geral first, then the direct
// chats newest first, and Nova conversa with a picker of the other members (GET /v1/me/business/members without me).
// The rooms open in the normal timeline on the member session (Chats.tsx). Chats renders this inside the member
// session's ClientContext, so avatars and rooms come from that session. The logic is in interno-session.ts.

import { useEffect, useState } from 'react';
import type { ChatSummary } from '../chat-model';
import { ChatRow, EntryRow } from '../Chats';
import { openOrCreateDm } from '../NewChat';
import { pencilSimple, usersThree } from '../phosphor/icons';
import { Avatar } from '../ui/Avatar';
import { s } from '../ui/i18n';
import { NavBar } from '../ui/List';
import { Spinner } from '../ui/Spinner';
import { describe } from '../util';
import { internoRooms, type Interno, type InternoState } from './interno-session';

export function InternoView({
  interno,
  state,
  chats,
  accountName,
  openId,
  onOpen,
  onClose,
}: {
  interno: Interno;
  state: InternoState;
  /** The summaries of the member session's rooms. */
  chats: ChatSummary[];
  accountName: string;
  openId?: string;
  onOpen: (roomId: string) => void;
  onClose: () => void;
}) {
  const [picking, setPicking] = useState(false);
  // Spec interno open: the first open signs in; later opens read the members and keep Geral in step.
  useEffect(() => {
    void interno.open();
  }, [interno]);

  if (state.phase !== 'ready' || !state.member) {
    return (
      <div className="flex h-full min-h-0 flex-col bg-surface-base">
        <NavBar title={s('bipe_interno_title')} onBack={onClose} />
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
          {state.phase === 'failed' ? (
            <>
              <p className="text-body text-ink-secondary">{s('bipe_interno_error')}</p>
              <button
                type="button"
                className="min-h-11 rounded-full bg-accent-fill px-5 text-subheadline font-semibold text-ink-on-accent"
                onClick={() => void interno.open()}
              >
                {s('bipe_app_retry')}
              </button>
            </>
          ) : (
            <>
              <Spinner label={s('bipe_interno_setup')} />
              <p className="text-body text-ink-secondary">{s('bipe_interno_setup')}</p>
            </>
          )}
        </div>
      </div>
    );
  }

  const me = state.members?.me ?? state.member.userId();
  const people = (state.members?.members ?? []).filter((m) => m.user_id !== me);
  if (picking)
    return (
      <MemberPicker
        member={state.member}
        people={people}
        onOpen={(roomId) => {
          setPicking(false);
          onOpen(roomId);
        }}
        onBack={() => setPicking(false)}
      />
    );

  const { geral, direct } = internoRooms(chats, state.members?.geral_room_id);
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_interno_title')} onBack={onClose} />
      <ul className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {geral && (
          <EntryRow
            icon={usersThree}
            title={s('bipe_interno_geral')}
            detail={geral.row.preview ?? s('bipe_interno_geral_body', accountName)}
            count={Number(geral.info?.numUnreadMessages ?? 0)}
            selected={openId === geral.roomId}
            onOpen={() => onOpen(geral.roomId)}
          />
        )}
        {people.length > 0 && (
          <EntryRow icon={pencilSimple} title={s('bipe_interno_new')} onOpen={() => setPicking(true)} />
        )}
        {direct.map((chat) => (
          <ChatRow
            key={chat.roomId}
            chat={chat}
            selected={openId === chat.roomId}
            onOpen={() => onOpen(chat.roomId)}
            onMenu={() => undefined}
          />
        ))}
        {people.length === 0 && state.members && (
          <li className="px-6 py-12 text-center text-ink-secondary">{s('bipe_interno_empty')}</li>
        )}
      </ul>
    </div>
  );
}

// Spec interno newChat: only members, because the homeserver refuses any other invite (ADR 0284 point 8).
function MemberPicker({
  member,
  people,
  onOpen,
  onBack,
}: {
  member: NonNullable<InternoState['member']>;
  people: { user_id: string; name: string }[];
  onOpen: (roomId: string) => void;
  onBack: () => void;
}) {
  const [busy, setBusy] = useState<string>();
  const [failed, setFailed] = useState(false);
  const pick = (userId: string) => {
    setBusy(userId);
    setFailed(false);
    // The direct chat: private, invite only, encrypted, is_direct (NewChat openOrCreateDm).
    openOrCreateDm(member, userId)
      .then(onOpen)
      .catch((e: unknown) => {
        console.warn('[trace] interno chat failed', describe(e));
        setFailed(true);
      })
      .finally(() => setBusy(undefined));
  };
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_interno_pick_title')} onBack={onBack} />
      {failed && <p className="px-4 py-2 text-footnote text-trust-danger-ink">{s('bipe_error_generic')}</p>}
      <ul className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {people.length === 0 && (
          <li className="px-6 py-12 text-center text-ink-secondary">{s('bipe_interno_empty')}</li>
        )}
        {people.map((p) => (
          <li key={p.user_id}>
            <button
              type="button"
              className="flex w-full items-center gap-3 pl-4 text-left hover:bg-surface-raised disabled:opacity-60"
              disabled={busy !== undefined}
              onClick={() => pick(p.user_id)}
            >
              <Avatar name={p.name} size={48} userId={p.user_id} />
              <span className="flex min-h-[72px] min-w-0 flex-1 items-center gap-2 border-b-[0.5px] border-line-subtle py-2 pr-4">
                <span className="min-w-0 flex-1 truncate text-headline text-ink-primary">{p.name}</span>
                {busy === p.user_id && <Spinner />}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

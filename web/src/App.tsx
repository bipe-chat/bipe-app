// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Bipe web (bipe-chat/bipe#900, ADR 0216): QR sign-in from the phone, the chat list, one timeline,
// send a text. The screens follow the apps' design language: bipe-shared colour tokens and strings,
// the iOS type scale, Phosphor icons. Light and dark follow the system.
//
// Several accounts (ADR 0225): the list lives in sdk.ts; this component holds the current account's client
// and gives the switcher and settings their actions through AccountsContext.

import { useCallback, useEffect, useRef, useState } from 'react';
import { AccountsContext, type AccountsApi } from './accounts-context';
import { type ClientLike as Client } from './generated/matrix_sdk_ffi';
import { Chats } from './Chats';
import { QrLogin } from './QrLogin';
import {
  addAccount,
  forgetSlot,
  loadAccountList,
  newSlot,
  restoreClient,
  saveAccountList,
  signOutAccount,
  signOutAll,
  type Accounts,
  type Slot,
} from './sdk';
import { pending, s } from './ui/i18n';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

type Phase =
  | { kind: 'loading'; note: string }
  | { kind: 'error'; message: string }
  | { kind: 'login'; slot: Slot; back?: Slot }
  | { kind: 'chats'; client: Client; slot: Slot };

export function App() {
  // Screen readers read the note; the page shows a spinner.
  const [phase, setPhase] = useState<Phase>({ kind: 'loading', note: pending('bipe_web_loading') });
  const [accounts, setAccounts] = useState<Accounts>({ list: [] });
  const accountsRef = useRef(accounts);
  accountsRef.current = accounts;

  const open = useCallback(async (list: Accounts, userId: string | undefined) => {
    const slot = list.list.find((s) => s.userId === userId) ?? list.list[0];
    if (!slot) return setPhase({ kind: 'login', slot: newSlot() });
    setPhase({ kind: 'loading', note: pending('bipe_web_loading') });
    const client = await restoreClient(slot);
    if (!client) {
      // The session of this slot is gone: drop the slot, go on with the others.
      await forgetSlot(slot);
      const rest = { current: undefined, list: list.list.filter((s) => s.id !== slot.id) };
      await saveAccountList(rest);
      setAccounts(rest);
      return open(rest, rest.list[0]?.userId);
    }
    const next = { ...list, current: slot.userId };
    if (list.current !== slot.userId) await saveAccountList(next);
    setAccounts(next);
    setPhase({ kind: 'chats', client, slot });
  }, []);

  useEffect(() => {
    let cancelled = false;
    loadAccountList()
      .then((list) => {
        if (cancelled) return;
        setAccounts(list);
        return open(list, list.current);
      })
      .catch((e) => !cancelled && setPhase({ kind: 'error', message: describe(e) }));
    return () => {
      cancelled = true;
    };
  }, [open]);

  const remember = useCallback((patch: Partial<Slot>) => {
    const list = accountsRef.current;
    const current = list.list.find((s) => s.userId === list.current);
    if (!current || Object.entries(patch).every(([k, v]) => current[k as keyof Slot] === v)) return;
    const next = { ...list, list: list.list.map((s) => (s === current ? { ...s, ...patch } : s)) };
    setAccounts(next);
    void saveAccountList(next);
  }, []);

  const api: AccountsApi | undefined =
    phase.kind === 'chats'
      ? {
          accounts,
          current: accounts.list.find((s) => s.userId === accounts.current) ?? phase.slot,
          // A switch unmounts the chat list, which stops the old sync; the old client is dropped.
          switchTo: (userId) => {
            if (userId !== accounts.current)
              void open(accounts, userId).catch((e) => setPhase({ kind: 'error', message: describe(e) }));
          },
          add: () => setPhase({ kind: 'login', slot: newSlot(), back: phase.slot }),
          signOut: () => signOutAccount(accounts, phase.slot, phase.client),
          signOutAll: () => signOutAll(accounts, phase.client),
          remember,
        }
      : undefined;

  return (
    <div className="h-dvh overflow-hidden bg-surface-base text-ink-primary">
      {phase.kind === 'loading' && (
        <div className="grid h-full place-items-center">
          <Spinner label={phase.note} />
        </div>
      )}
      {phase.kind === 'error' && (
        <div className="mx-auto flex h-full max-w-[560px] flex-col justify-center gap-2 p-4 text-center">
          <p className="text-headline text-trust-danger-ink">{s('bipe_error_generic')}</p>
          {/* The technical detail stays on screen while the web client is in testing. */}
          <pre className="font-mono text-footnote break-words whitespace-pre-wrap text-ink-secondary">
            {phase.message}
          </pre>
        </div>
      )}
      {phase.kind === 'login' && (
        <QrLogin
          key={phase.slot.id}
          slot={phase.slot}
          onDone={(client) => {
            addAccount(accountsRef.current, phase.slot, client)
              .then(({ accounts: next, slot }) => {
                setAccounts(next);
                // An account already in this browser: open its own slot, not the new client.
                if (slot.id !== phase.slot.id) return open(next, slot.userId);
                setPhase({ kind: 'chats', client, slot });
              })
              .catch((e) => setPhase({ kind: 'error', message: describe(e) }));
          }}
          onError={(message) => setPhase({ kind: 'error', message })}
          onCancel={
            phase.back
              ? () => {
                  void forgetSlot(phase.slot);
                  void open(accountsRef.current, phase.back?.userId);
                }
              : undefined
          }
        />
      )}
      {phase.kind === 'chats' && api && (
        <AccountsContext value={api}>
          <Chats key={phase.slot.id} client={phase.client} />
        </AccountsContext>
      )}
    </div>
  );
}

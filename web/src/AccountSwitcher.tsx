// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec account-switcher, adapted to the web (ADR 0225; AccountRows and AccountSwitcherPanel in AccountMenu.swift).
// The apps open it with a hold on the Você tab; the web has no tab bar, so the current account's photo in the
// chat-list toolbar opens it (click, Enter or Space). Every account, oldest first, with its photo, name and
// @handle, a dot when its last sync left unread messages (not live for the other accounts), a check on the
// current one; then Nova conta, which starts a QR sign-in for another account.

import { useEffect, useState } from 'react';
import { useAccounts } from './accounts-context';
import { apiFor } from './api';
import { accounts as accountRules } from './bipe/generated/registries.json';
import { useClient } from './media';
import { check, plus } from './phosphor/icons';
import { Avatar } from './ui/Avatar';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';

export function AccountSwitcher() {
  const client = useClient();
  const { accounts, current, switchTo, add } = useAccounts();
  const [open, setOpen] = useState(false);
  const [handles, setHandles] = useState<Map<string, string>>(new Map());
  const ids = accounts.list.flatMap((a) => (a.userId ? [a.userId] : []));
  useEffect(() => {
    if (!open) return;
    let stopped = false;
    void apiFor(client)
      .resolveHandles(ids)
      .then((h) => !stopped && setHandles(h));
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => {
      stopped = true;
      window.removeEventListener('keydown', onKey);
    };
    // ids changes only with the account list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, client, ids.join()]);
  // Company accounts do not count against the cap (ADR 0086).
  const personal = accounts.list.filter((a) => !a.isCompany).length;
  const canAdd = personal < accountRules.maxPersonalAccounts;
  const othersUnread = accounts.list.some((a) => a.userId !== current.userId && (a.unread ?? 0) > 0);
  return (
    <div className="relative">
      <button
        type="button"
        className="relative grid size-11 place-items-center rounded-full"
        aria-haspopup="menu"
        aria-expanded={open}
        // TODO(string): bipe_web_account_switcher_title, "Contas neste navegador" (the app says "neste celular").
        aria-label={SWITCHER_TITLE}
        title={SWITCHER_TITLE}
        onClick={() => setOpen((v) => !v)}
      >
        <Avatar
          name={current.name ?? current.userId ?? ''}
          size={32}
          mxcUrl={current.avatarUrl}
          userId={current.userId}
        />
        {othersUnread && (
          <span className="absolute top-1 right-1 size-2.5 rounded-full border-2 border-surface-base bg-accent-fill" />
        )}
      </button>
      {open && (
        <>
          <button
            type="button"
            className="fixed inset-0 z-40 cursor-default"
            tabIndex={-1}
            aria-hidden
            onClick={() => setOpen(false)}
          />
          <div
            role="menu"
            aria-label={SWITCHER_TITLE}
            className="bipe-glass absolute top-full left-0 z-50 mt-1 flex w-[300px] flex-col overflow-hidden rounded-[28px] pb-1.5 shadow-lg"
          >
            <p className="px-4 pt-3 pb-0.5 text-footnote text-ink-secondary">{SWITCHER_TITLE}</p>
            {accounts.list.map((a) => {
              const isCurrent = a.userId === current.userId;
              const name = a.name ?? a.userId ?? '';
              const handle = a.userId ? handles.get(a.userId) : undefined;
              return (
                <button
                  key={a.id}
                  type="button"
                  role="menuitemradio"
                  aria-checked={isCurrent}
                  autoFocus={isCurrent}
                  className="flex items-center gap-3 px-4 py-1.5 text-left hover:bg-surface-sunken"
                  onClick={() => {
                    setOpen(false);
                    if (!isCurrent && a.userId) switchTo(a.userId);
                  }}
                >
                  <Avatar name={name} size={32} mxcUrl={a.avatarUrl} userId={a.userId} />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-body text-ink-primary">{name}</span>
                    {handle && <span className="truncate text-footnote text-ink-secondary">@{handle}</span>}
                  </span>
                  {!isCurrent && (a.unread ?? 0) > 0 && (
                    // The count of the last sync of that account, not live (ADR 0225 point 4).
                    <span
                      className="size-2 shrink-0 rounded-full bg-accent-fill"
                      // TODO(string): bipe_web_account_unread_last_sync, "Mensagens não lidas na última sincronização".
                      title={UNREAD_LAST_SYNC}
                      aria-label={UNREAD_LAST_SYNC}
                    />
                  )}
                  {isCurrent && <Icon path={check} size={20} className="shrink-0 text-accent-ink" />}
                </button>
              );
            })}
            <div className="ml-[60px] border-t-[0.5px] border-line-subtle" />
            <button
              type="button"
              role="menuitem"
              disabled={!canAdd}
              className="flex items-center gap-3 px-4 py-1.5 text-left text-ink-primary hover:bg-surface-sunken disabled:opacity-50"
              onClick={() => {
                setOpen(false);
                add();
              }}
            >
              <span className="grid size-8 place-items-center">
                <Icon path={plus} size={20} />
              </span>
              <span className="text-body">{s('bipe_app_account_new')}</span>
            </button>
            {!canAdd && (
              // TODO(string): bipe_web_account_cap_reached, the browser variant of bipe_account_cap_reached ("Este celular").
              <p className="px-4 pb-1 text-footnote text-ink-secondary">
                {`Este navegador já tem ${accountRules.maxPersonalAccounts} contas, o limite.`}
              </p>
            )}
          </div>
        </>
      )}
    </div>
  );
}

const SWITCHER_TITLE = 'Contas neste navegador';
const UNREAD_LAST_SYNC = 'Mensagens não lidas na última sincronização';

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The accounts of this browser for the screens (ADR 0225): App provides it, the switcher and settings use it.

import { createContext, useContext } from 'react';
import type { Accounts, Slot } from './sdk';

export type AccountsApi = {
  accounts: Accounts;
  current: Slot;
  switchTo: (userId: string) => void;
  add: () => void;
  signOut: () => Promise<void>;
  signOutAll: () => Promise<void>;
  /** Keeps the last known name, photo, unread badge and type of the current account for the switcher. */
  remember: (patch: Partial<Pick<Slot, 'name' | 'avatarUrl' | 'unread' | 'isCompany'>>) => void;
};

export const AccountsContext = createContext<AccountsApi | undefined>(undefined);

export function useAccounts(): AccountsApi {
  const api = useContext(AccountsContext);
  if (!api) throw new Error('useAccounts outside AccountsContext');
  return api;
}

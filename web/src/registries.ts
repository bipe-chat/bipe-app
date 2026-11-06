// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The parts of bipe-shared registries.json that the web reads. Vite imports each top-level key on its
// own, so the bundle holds only these.

import { homeScore, officialAccounts, settings } from './bipe/generated/registries.json';

/** registries/official-accounts.json: the full Matrix user ID is the only proof (ADR 0117). */
export function isOfficial(userId: string | undefined): boolean {
  return userId !== undefined && Object.values(officialAccounts.accounts).some((a) => a.userId === userId);
}

/** The groups service account (security.md rule 17): the member list says what it does. */
export function isGroupsAccount(userId: string | undefined): boolean {
  return userId === officialAccounts.accounts.groups.userId;
}

export { homeScore, settings };

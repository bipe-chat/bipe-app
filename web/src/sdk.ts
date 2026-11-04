// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// matrix-sdk-ffi in the browser (ubrn wasm2 bindings from CI, web.yml), and the accounts of this browser.
// Provenance: matrix-sdk-ffi ClientBuilder, IndexedDbStoreBuilder,
// LoginWithQrCodeHandler.generate (MSC4108), SyncService, RoomListService.
// The encryption settings are the phones' (AccountSession.swift builder, AccountSession.kt).
//
// Several accounts (ADR 0225): each account is a slot with its own SDK stores (IndexedDB prefix), its own
// sealed session record and its own store passphrase (session-store.ts). Only the current account has a
// client; a switch drops the old client (its sync stops when the chat list unmounts) and builds the new one.
// The first account keeps the store and record names it had with one account, so it needs no new sign-in.

import { uniffiInitAsync } from './generated';
import { BackupDownloadStrategy } from './generated/matrix_sdk';
import { CollectStrategy, TrustRequirement } from './generated/matrix_sdk_crypto';
import {
  ClientBuilder,
  IndexedDbStoreBuilder,
  SlidingSyncVersionBuilder,
  type ClientLike as Client,
  type OAuthConfiguration,
  type Session,
} from './generated/matrix_sdk_ffi';
import {
  clearSecrets,
  deleteSecrets,
  FIRST_PASSPHRASE_KEY,
  FIRST_SESSION_KEY,
  loadAccounts,
  loadSession,
  saveAccounts,
  saveSession,
  storePassphrase,
} from './session-store';
import { archiveKey } from './archive';
import { muralKey } from './mural-plan';

// Server name of the Bipe homeserver (ADR 0062).
export const SERVER_NAME = 'bipe.net.br';
// The IndexedDB prefix of the first account. The SDK names its databases "<prefix>::matrix-sdk-<store>".
const FIRST_STORE = 'bipe-web';
const SDK_STORES = ['crypto', 'state', 'media', 'event-cache'];

/** One account in this browser. name, avatarUrl, unread and isCompany are the last known, for the switcher. */
export type Slot = {
  id: string;
  userId?: string;
  storeName: string;
  sessionKey: string;
  passphraseKey: string;
  name?: string;
  avatarUrl?: string;
  /** The unread badge at the last sync of this account; not live for other accounts (ADR 0225 point 4). */
  unread?: number;
  isCompany?: boolean;
};
export type Accounts = { current?: string; list: Slot[] };

let ready: Promise<void> | undefined;

export function initSdk(): Promise<void> {
  ready ??= uniffiInitAsync(new URL('./generated/matrix_sdk_ffi.wasm', import.meta.url)).then(() => undefined);
  return ready;
}

async function buildClient(slot: Slot, restored?: Session): Promise<Client> {
  await initSdk();
  const passphrase = await storePassphrase(slot.passphraseKey);
  // The SDK asks for the session synchronously; keep the last saved one of this slot here.
  let current = restored;
  return new ClientBuilder()
    .serverNameOrHomeserverUrl(SERVER_NAME)
    .indexeddbStore(new IndexedDbStoreBuilder(slot.storeName).passphrase(passphrase))
    .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DiscoverNative)
    // Spec encryption-status: cross-signing and key backup with no screen, as on the phones.
    .autoEnableCrossSigning(true)
    .autoEnableBackups(true)
    // Security rule 13: a server-added device receives no keys without its owner's signature.
    .roomKeyRecipientStrategy(CollectStrategy.IdentityBasedStrategy)
    // Keep pre-trust-metadata history readable; current senders must be cross-signed.
    .decryptionSettings({ senderDeviceTrustRequirement: TrustRequirement.CrossSignedOrLegacy })
    // #903: the QR sign-in brings the backup key (syncingSecrets), so old messages decrypt from the backup.
    .backupDownloadStrategy(BackupDownloadStrategy.AfterDecryptionFailure)
    .setSessionDelegate({
      retrieveSessionFromKeychain(userId: string): Session {
        if (current && current.userId === userId) return current;
        throw new Error(`no session for ${userId}`);
      },
      saveSessionInKeychain(session: Session): void {
        current = session;
        void saveSession(slot.sessionKey, session);
      },
    })
    .build();
}

// The SDK registers a native client (ApplicationType::Native) with MAS, so
// the values match the phones (AccountSession+QRLogin.swift, QrLogin.kt): a
// private-use redirect scheme and the public site as client URI. The QR flow
// uses the device grant and never opens the redirect URI.
export function oauthConfiguration(): OAuthConfiguration {
  return {
    clientName: 'Bipe Web',
    redirectUri: 'chat.bipe:/callback',
    clientUri: 'https://bipe.chat',
    logoUri: undefined,
    tosUri: undefined,
    policyUri: undefined,
    staticRegistrations: new Map(),
  };
}

/** A new, empty slot for a sign-in. Nothing is stored until the sign-in finishes. */
export function newSlot(): Slot {
  const id = Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => b.toString(16).padStart(2, '0')).join('');
  return { id, storeName: `bipe-web-${id}`, sessionKey: `session:${id}`, passphraseKey: `passphrase:${id}` };
}

/** A client for a new sign-in in this slot. The caller runs the QR flow on it. */
export const newClient = (slot: Slot) => buildClient(slot);

/**
 * The accounts of this browser. The first time, the one account of #381 becomes the first slot, under its
 * old names. A session record from before encryption signs in again (see session-store.ts).
 */
export async function loadAccountList(): Promise<Accounts> {
  await wipeIfPending();
  const saved = await loadAccounts<Accounts>();
  if (saved.kind === 'session') {
    await deleteOrphanStores(saved.session);
    return saved.session;
  }
  if (saved.kind === 'legacy') {
    await wipeAll();
    return { list: [] };
  }
  const first = await loadSession<Session>(FIRST_SESSION_KEY);
  if (first.kind === 'legacy') {
    console.info('[trace] unencrypted store from an older version: signing in again');
    await wipeAll();
    return { list: [] };
  }
  const accounts: Accounts = { list: [] };
  if (first.kind === 'session') {
    accounts.list.push({
      id: 'first',
      userId: first.session.userId,
      storeName: FIRST_STORE,
      sessionKey: FIRST_SESSION_KEY,
      passphraseKey: FIRST_PASSPHRASE_KEY,
    });
    accounts.current = first.session.userId;
  }
  await saveAccounts(accounts);
  await deleteOrphanStores(accounts);
  return accounts;
}

export const saveAccountList = (accounts: Accounts) => saveAccounts(accounts);

/** The client of a slot's saved session, or undefined when the session is gone. */
export async function restoreClient(slot: Slot): Promise<Client | undefined> {
  const saved = await loadSession<Session>(slot.sessionKey);
  if (saved.kind !== 'session') return undefined;
  const client = await buildClient(slot, saved.session);
  await client.restoreSession(saved.session);
  return client;
}

/**
 * Keeps a finished sign-in: its session, and the slot in the list as the current account. An account that is
 * already in this browser keeps its old slot; the new one is wiped. Returns the slot that holds the account.
 */
export async function addAccount(
  accounts: Accounts,
  slot: Slot,
  client: Client,
): Promise<{ accounts: Accounts; slot: Slot }> {
  const session = client.session();
  const existing = accounts.list.find((s) => s.userId === session.userId);
  if (existing) {
    await forgetSlot(slot);
    const next = { ...accounts, current: existing.userId };
    await saveAccounts(next);
    return { accounts: next, slot: existing };
  }
  await saveSession(slot.sessionKey, session);
  const added = { ...slot, userId: session.userId };
  const next = { current: session.userId, list: [...accounts.list, added] };
  await saveAccounts(next);
  return { accounts: next, slot: added };
}

/** Drops an unfinished or unwanted slot: its records now, its stores on the next load. */
export async function forgetSlot(slot: Slot): Promise<void> {
  await deleteSecrets(slot.sessionKey, slot.passphraseKey);
  markWipe([slot.storeName]);
}

/**
 * Sign out of one account (spec sign-out, ADR 0225 point 3): Client.logout ends its session on the server,
 * then only its records and stores go. The next account becomes current; with none left, the QR code shows.
 */
export async function signOutAccount(accounts: Accounts, slot: Slot, client: Client): Promise<void> {
  await client.logout();
  await deleteSecrets(slot.sessionKey, slot.passphraseKey);
  if (slot.userId) {
    localStorage.removeItem(archiveKey(slot.userId));
    localStorage.removeItem(muralKey(slot.userId));
  }
  const list = accounts.list.filter((s) => s.id !== slot.id);
  await saveAccounts({ current: list[0]?.userId, list });
  markWipe([slot.storeName]);
  window.location.reload();
}

/**
 * Sair de todas as contas deste navegador (ADR 0225 consequences): every account's session ends on the
 * server (the current one through its client, each other one through a client restored for that call
 * only), then every record, the browser key and every store go.
 */
export async function signOutAll(accounts: Accounts, current: Client): Promise<void> {
  for (const slot of accounts.list) {
    try {
      if (slot.userId === accounts.current) await current.logout();
      else await (await restoreClient(slot))?.logout();
    } catch (e) {
      console.warn('[trace] sign-out of one account failed', slot.userId, e);
    }
  }
  // #903: no Bipe data stays in the browser. Every page key starts with "bipe."; the wipe mark is set after.
  for (const k of Object.keys(localStorage)) if (k.startsWith('bipe.')) localStorage.removeItem(k);
  markWipe(accounts.list.map((s) => s.storeName));
  await clearSecrets();
  window.location.reload();
}

// Deleting a database waits for its open connections, and the SDK keeps them open while the page runs.
// So a sign-out marks the stores to wipe, reloads, and the wipe runs before any client opens them.
const WIPE = 'bipe.web.wipe';

function markWipe(storeNames: string[]) {
  let pending: string[] = [];
  try {
    const raw = localStorage.getItem(WIPE);
    // "1" was the one-account mark of #381.
    pending = raw === '1' ? [FIRST_STORE] : raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    pending = [];
  }
  localStorage.setItem(WIPE, JSON.stringify([...new Set([...pending, ...storeNames])]));
}

async function storeDatabases(): Promise<string[]> {
  try {
    return (await indexedDB.databases()).map((d) => d.name ?? '').filter((n) => /^bipe-web(-[0-9a-f]+)?::/.test(n));
  } catch {
    // Older browsers have no databases().
    return [];
  }
}

async function deleteStores(storeNames: string[]): Promise<void> {
  const known = storeNames.flatMap((name) => SDK_STORES.map((s) => `${name}::matrix-sdk-${s}`));
  const found = (await storeDatabases()).filter((db) => storeNames.some((name) => db.startsWith(`${name}::`)));
  await Promise.all(
    [...new Set([...known, ...found])].map(
      (name) =>
        new Promise<void>((resolve) => {
          const req = indexedDB.deleteDatabase(name);
          req.onsuccess = req.onerror = req.onblocked = () => resolve();
        }),
    ),
  );
}

async function wipeIfPending(): Promise<void> {
  const raw = localStorage.getItem(WIPE);
  if (!raw) return;
  let names: string[];
  try {
    names = raw === '1' ? [FIRST_STORE] : (JSON.parse(raw) as string[]);
  } catch {
    names = [];
  }
  await deleteStores(names);
  if (raw === '1') await clearSecrets();
  localStorage.removeItem(WIPE);
}

async function wipeAll(): Promise<void> {
  const prefixes = [...new Set((await storeDatabases()).map((db) => db.slice(0, db.indexOf('::'))))];
  await deleteStores([FIRST_STORE, ...prefixes]);
  await clearSecrets();
}

// Stores of a sign-in that never finished (the page closed during the QR code) belong to no slot.
async function deleteOrphanStores(accounts: Accounts): Promise<void> {
  const used = new Set(accounts.list.map((s) => s.storeName));
  const prefixes = [...new Set((await storeDatabases()).map((db) => db.slice(0, db.indexOf('::'))))];
  await deleteStores(prefixes.filter((p) => !used.has(p)));
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// matrix-sdk-ffi in the browser (ubrn wasm2 bindings from CI, web.yml).
// Provenance: matrix-sdk-ffi ClientBuilder, IndexedDbStoreBuilder,
// LoginWithQrCodeHandler.generate (MSC4108), SyncService, RoomListService.

import { uniffiInitAsync } from './generated';
import {
  ClientBuilder,
  IndexedDbStoreBuilder,
  SlidingSyncVersionBuilder,
  type ClientLike as Client,
  type ClientSessionDelegate,
  type OAuthConfiguration,
  type Session,
} from './generated/matrix_sdk_ffi';
import { clearSecrets, loadSession, saveSession, storePassphrase } from './session-store';

// Server name of the Bipe homeserver (ADR 0062).
export const SERVER_NAME = 'bipe.net.br';
// IndexedDB prefix of the SDK stores. One account per browser (ADR 0121). The stores are encrypted with the
// passphrase of session-store.ts. The SDK names its databases "<prefix>::matrix-sdk-<store>".
const STORE_NAME = 'bipe-web';
const SDK_STORES = ['crypto', 'state', 'media', 'event-cache'].map((s) => `${STORE_NAME}::matrix-sdk-${s}`);

let ready: Promise<void> | undefined;

export function initSdk(): Promise<void> {
  ready ??= uniffiInitAsync(new URL('./generated/matrix_sdk_ffi.wasm', import.meta.url)).then(() => undefined);
  return ready;
}

// The SDK asks for the session synchronously; keep the last saved one here.
let current: Session | undefined;

const sessionDelegate: ClientSessionDelegate = {
  retrieveSessionFromKeychain(userId: string): Session {
    if (current && current.userId === userId) return current;
    throw new Error(`no session for ${userId}`);
  },
  saveSessionInKeychain(session: Session): void {
    current = session;
    void saveSession(session);
  },
};

async function buildClient(): Promise<Client> {
  await initSdk();
  const passphrase = await storePassphrase();
  return new ClientBuilder()
    .serverNameOrHomeserverUrl(SERVER_NAME)
    .indexeddbStore(new IndexedDbStoreBuilder(STORE_NAME).passphrase(passphrase))
    .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DiscoverNative)
    .setSessionDelegate(sessionDelegate)
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

// A client for a new sign-in. The caller runs the QR flow on it.
export const newClient = buildClient;

// The client of the saved session, or undefined when this browser has none.
export async function restoreClient(): Promise<Client | undefined> {
  await wipeIfPending();
  const saved = await loadSession<Session>();
  if (saved.kind === 'none') {
    // Stores without a session (an interrupted sign-in or sign-out) cannot be used: start clean.
    await deleteSdkStores();
    return undefined;
  }
  if (saved.kind === 'legacy') {
    // A store from before the passphrase: this browser signs in again (see session-store.ts).
    console.info('[trace] unencrypted store from an older version: signing in again');
    await deleteSdkStores();
    await clearSecrets();
    return undefined;
  }
  current = saved.session;
  const client = await buildClient();
  await client.restoreSession(saved.session);
  return client;
}

// Deleting a database waits for its open connections, and the SDK keeps them open while the page runs.
// So sign-out marks the wipe, reloads, and the wipe runs here before any client opens the stores.
const WIPE = 'bipe.web.wipe';

async function deleteSdkStores(): Promise<void> {
  let names = SDK_STORES;
  try {
    const all = await indexedDB.databases();
    names = [...new Set([...names, ...all.map((d) => d.name ?? '').filter((n) => n.startsWith(`${STORE_NAME}::`))])];
  } catch {
    // Older browsers have no databases(); the known names remain.
  }
  await Promise.all(
    names.map(
      (name) =>
        new Promise<void>((resolve) => {
          const req = indexedDB.deleteDatabase(name);
          req.onsuccess = req.onerror = req.onblocked = () => resolve();
        }),
    ),
  );
}

async function wipeIfPending(): Promise<void> {
  if (localStorage.getItem(WIPE) !== '1') return;
  await deleteSdkStores();
  await clearSecrets();
  localStorage.removeItem(WIPE);
}

export async function rememberSession(client: Client): Promise<void> {
  const session = client.session();
  current = session;
  await saveSession(session);
}

/** Forgets this browser's session: the stores go on the next load, before any client opens them. */
export async function forgetSession(): Promise<void> {
  current = undefined;
  localStorage.setItem(WIPE, '1');
  await clearSecrets();
}

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
import { clearSession, loadSession, saveSession } from './session-store';

// Server name of the Bipe homeserver (ADR 0062).
export const SERVER_NAME = 'bipe.net.br';
// IndexedDB prefix of the SDK stores. One account per browser (ADR 0121).
const STORE_NAME = 'bipe-web';

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
  return new ClientBuilder()
    .serverNameOrHomeserverUrl(SERVER_NAME)
    .indexeddbStore(new IndexedDbStoreBuilder(STORE_NAME))
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
  const saved = await loadSession<Session>();
  if (!saved) return undefined;
  current = saved;
  const client = await buildClient();
  await client.restoreSession(saved);
  return client;
}

export async function rememberSession(client: Client): Promise<void> {
  const session = client.session();
  current = session;
  await saveSession(session);
}

export async function forgetSession(): Promise<void> {
  current = undefined;
  await clearSession();
}

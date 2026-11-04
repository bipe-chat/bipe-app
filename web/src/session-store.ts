// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The browser's secrets for its accounts: per account the Matrix session (tokens, user, device) and the
// passphrase of its SDK stores, and the list of accounts, all encrypted at rest with one browser key. Several
// accounts per browser (ADR 0225); no account reads another's records.
//
// Why this design (bipe-chat/bipe#903 follow-up, item C):
// - The SDK keeps the account's state, message keys and media in IndexedDB. Without a passphrase those
//   stores are plain records that any tool reading the profile folder can open. IndexedDbStoreBuilder takes
//   a passphrase and encrypts its values with it.
// - The passphrase is 32 random bytes made once per browser. It is stored only encrypted (AES-GCM) with a
//   WebCrypto key generated with extractable: false. That key is a CryptoKey object kept in IndexedDB: page
//   script can use it, but neither page script nor an injected script can read its bytes, so stolen data
//   leaves with ciphertext only, and the key works only in this origin and this browser profile.
// - The session record is encrypted with the same key, so the access token is not plain either.
// - Limits: someone with the unlocked profile and the browser itself (the same OS account) can run the page
//   and use the key. This protects data at rest against copies of the stores and against exfiltration of
//   their contents; it is not protection against an attacker who controls the running browser.
// - Migration: stores made before this change have no passphrase and the SDK cannot open them with one.
//   A browser whose session record is not in the encrypted form signs in again: sdk.ts deletes the old
//   stores and shows the QR code.

const DB = 'bipe-web-session';
const VERSION = 2;
const SESSION = 'session';
const KEYS = 'keys';
const DEVICE_KEY = 'device';
const ACCOUNTS_KEY = 'accounts';
/** The record keys of the first account, from before several accounts (#381): kept, so nobody signs in again. */
export const FIRST_SESSION_KEY = 'current';
export const FIRST_PASSPHRASE_KEY = 'passphrase';

type Bytes = Uint8Array<ArrayBuffer>;
type Sealed = { v: 2; iv: Bytes; data: ArrayBuffer };

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(SESSION)) db.createObjectStore(SESSION);
      if (!db.objectStoreNames.contains(KEYS)) db.createObjectStore(KEYS);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function run<T>(store: string, mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  const db = await open();
  try {
    return await new Promise<T>((resolve, reject) => {
      const req = op(db.transaction(store, mode).objectStore(store));
      req.onsuccess = () => resolve(req.result as T);
      req.onerror = () => reject(req.error);
    });
  } finally {
    db.close();
  }
}

/** The browser key: AES-GCM 256, not extractable, made on first use. */
async function deviceKey(): Promise<CryptoKey> {
  const saved = await run<CryptoKey | undefined>(KEYS, 'readonly', (s) => s.get(DEVICE_KEY));
  if (saved) return saved;
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  await run<void>(KEYS, 'readwrite', (s) => s.put(key, DEVICE_KEY));
  return key;
}

async function seal(bytes: Bytes): Promise<Sealed> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await deviceKey(), bytes);
  return { v: 2, iv, data };
}

async function unseal(sealed: Sealed): Promise<Bytes> {
  return new Uint8Array(
    await crypto.subtle.decrypt({ name: 'AES-GCM', iv: sealed.iv }, await deviceKey(), sealed.data),
  );
}

function isSealed(value: unknown): value is Sealed {
  return typeof value === 'object' && value !== null && (value as { v?: unknown }).v === 2;
}

/** The passphrase of one account's SDK stores, as base64 of 32 random bytes; made on first use. */
export async function storePassphrase(passphraseKey: string): Promise<string> {
  const saved = await run<unknown>(KEYS, 'readonly', (s) => s.get(passphraseKey));
  let raw: Bytes;
  if (isSealed(saved)) {
    raw = await unseal(saved);
  } else {
    raw = crypto.getRandomValues(new Uint8Array(32));
    const sealed = await seal(raw);
    await run<void>(KEYS, 'readwrite', (s) => s.put(sealed, passphraseKey));
  }
  return btoa(String.fromCharCode(...raw));
}

export type Loaded<T> = { kind: 'none' } | { kind: 'legacy' } | { kind: 'session'; session: T };

async function loadSealed<T>(store: string, key: string): Promise<Loaded<T>> {
  const saved = await run<unknown>(store, 'readonly', (s) => s.get(key));
  if (saved === undefined) return { kind: 'none' };
  if (!isSealed(saved)) return { kind: 'legacy' };
  const json = new TextDecoder().decode(await unseal(saved));
  return { kind: 'session', session: JSON.parse(json) as T };
}

async function saveSealed<T>(store: string, key: string, value: T): Promise<void> {
  const sealed = await seal(new TextEncoder().encode(JSON.stringify(value)));
  await run<void>(store, 'readwrite', (s) => s.put(sealed, key));
}

/** One account's saved session. A record from before encryption comes back as legacy. */
export const loadSession = <T>(sessionKey: string) => loadSealed<T>(SESSION, sessionKey);
export const saveSession = <T>(sessionKey: string, session: T) => saveSealed(SESSION, sessionKey, session);

/** The list of accounts in this browser (sdk.ts Accounts). */
export const loadAccounts = <T>() => loadSealed<T>(SESSION, ACCOUNTS_KEY);
export const saveAccounts = <T>(accounts: T) => saveSealed(SESSION, ACCOUNTS_KEY, accounts);

/** Deletes one account's session and passphrase. */
export async function deleteSecrets(sessionKey: string, passphraseKey: string): Promise<void> {
  await run<void>(SESSION, 'readwrite', (s) => s.delete(sessionKey));
  await run<void>(KEYS, 'readwrite', (s) => s.delete(passphraseKey));
}

/** Deletes every session, passphrase, the account list and the browser key: the next sign-in makes new ones. */
export async function clearSecrets(): Promise<void> {
  await run<void>(SESSION, 'readwrite', (s) => s.clear());
  await run<void>(KEYS, 'readwrite', (s) => s.clear());
}

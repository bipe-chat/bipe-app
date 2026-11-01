// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The Matrix session (tokens, user, device) in IndexedDB. The SDK keeps its
// own state and crypto stores in IndexedDB too (ClientBuilder.indexeddbStore).
// One account per browser (ADR 0121): one record under one key.

const DB = 'bipe-web-session';
const STORE = 'session';
const KEY = 'current';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function run<T>(mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  const db = await open();
  try {
    return await new Promise<T>((resolve, reject) => {
      const req = op(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result as T);
      req.onerror = () => reject(req.error);
    });
  } finally {
    db.close();
  }
}

export const loadSession = <T>() => run<T | undefined>('readonly', (s) => s.get(KEY));
export const saveSession = <T>(session: T) => run<void>('readwrite', (s) => s.put(session, KEY));
export const clearSession = () => run<void>('readwrite', (s) => s.delete(KEY));

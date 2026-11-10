// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec registries/synced-settings.json (bipe-chat/bipe#1161): keeps this browser's copy and the account's settings
// together. Read at start and again when the page comes back to the front. A failure changes nothing on the server,
// and the next read tries again.

import { useEffect } from 'react';
import { readSetting, writeSetting } from './appearance';
import { syncedSettingsReconcile } from './shared-rules';
import { syncedSettings } from './bipe/generated/registries.json';
import type { ClientLike as Client } from './generated/matrix_sdk_ffi';
import { readMural, writeMural } from './mural-plan';

const READ_RECEIPTS = 'bipe.settings.readReceipts';
const firstKey = (userId: string) => `bipe.settings.synced.${userId}`;
const pendingKey = (userId: string) => `bipe.settings.syncPending.${userId}`;

type Settings = Record<string, unknown>;

let current: Client | undefined;

function item(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function setItem(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Without storage the next read starts again.
  }
}

function pending(userId: string): string[] {
  try {
    const list: unknown = JSON.parse(item(pendingKey(userId)) ?? '[]');
    return Array.isArray(list) ? list.filter((k): k is string => typeof k === 'string') : [];
  } catch {
    return [];
  }
}

function parse(json: string | undefined | null): Settings {
  try {
    const content: unknown = json ? JSON.parse(json) : {};
    return content && typeof content === 'object' && !Array.isArray(content) ? (content as Settings) : {};
  } catch {
    return {};
  }
}

function localValues(userId: string): Settings {
  const local: Settings = { mural: readMural(userId) };
  const receipts = item(READ_RECEIPTS);
  if (receipts !== null) local.readReceipts = receipts !== 'false';
  if (item('bipe.settings.palette') !== null) local.palette = readSetting('palette');
  if (item('bipe.settings.chatBackground') !== null) local.chatBackground = readSetting('chatBackground');
  return local;
}

function apply(settings: Settings, userId: string) {
  if (typeof settings.readReceipts === 'boolean') setItem(READ_RECEIPTS, String(settings.readReceipts));
  if (typeof settings.mural === 'boolean' && settings.mural !== readMural(userId)) writeMural(userId, settings.mural);
  if (typeof settings.palette === 'string' && settings.palette !== readSetting('palette')) {
    writeSetting('palette', settings.palette);
  }
  if (typeof settings.chatBackground === 'string' && settings.chatBackground !== readSetting('chatBackground')) {
    writeSetting('chatBackground', settings.chatBackground);
  }
}

async function sync(client: Client) {
  const userId = client.userId();
  const changed = pending(userId);
  const server = parse(await client.accountData(syncedSettings.eventType));
  const first = item(firstKey(userId)) !== 'true';
  const result = syncedSettingsReconcile(server, localValues(userId), changed, first);
  if (JSON.stringify(result) !== JSON.stringify(server)) {
    await client.setAccountData(syncedSettings.eventType, JSON.stringify(result));
  }
  setItem(firstKey(userId), 'true');
  setItem(pendingKey(userId), JSON.stringify(pending(userId).filter((k) => !changed.includes(k))));
  if (current === client) apply(result, userId);
}

/** The person changed a setting in this browser. */
export function syncedSettingChanged(key: 'readReceipts' | 'palette' | 'chatBackground' | 'mural') {
  const client = current;
  if (!client) return;
  const userId = client.userId();
  const list = pending(userId);
  if (!list.includes(key)) setItem(pendingKey(userId), JSON.stringify([...list, key]));
  void sync(client).catch(() => undefined);
}

/** Follows the account's settings while this client is the current one. */
export function useSyncedSettings(client: Client) {
  useEffect(() => {
    current = client;
    const read = () => void sync(client).catch(() => undefined);
    const onVisible = () => {
      if (document.visibilityState === 'visible') read();
    };
    read();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      if (current === client) current = undefined;
    };
  }, [client]);
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Aparelhos e assistentes for this browser (bipe ADR 0291, bipe-chat/bipe#1482; bipe-shared docs/drafts-room.md "Device
// entries", registries/device-kinds.json, spec linked-devices). The browser writes its own device entry (source self)
// in the account's drafts room after the link and again on load when a field changed. The list reads the devices
// (GET /_matrix/client/v3/devices) and the entries of the drafts room, and builds the rows with the core rule
// device_rows. The web shows the list read-only: only a phone signs devices out.

import { useEffect, useState, useSyncExternalStore } from 'react';
import { drafts, deviceKinds } from './bipe/generated/registries.json';
import {
  EventOrTransactionId,
  Membership,
  MsgLikeKind,
  TimelineDiff,
  TimelineItemContent,
  type ClientLike as Client,
  type TaskHandleLike as TaskHandle,
  type TimelineItemLike as TimelineItem,
  type TimelineLike as Timeline,
} from './generated/matrix_sdk_ffi';
import { openOwnRoom, readOwnRoomId, type OwnRoom } from './notes';
import { deviceInfoFromEvent, deviceRows, userAgentInfo, type DeviceEntry, type DeviceRow, type MatrixDevice } from './shared-rules';
import { s } from './ui/i18n';
import { applyDiffs, type DiffVariants } from './util';

const DRAFTS_ROOM: OwnRoom = {
  accountDataType: drafts.accountDataType,
  pendingKey: (userId) => `bipe.web.drafts.pending.${userId}`,
  name: () => s('bipe_app_drafts_title'),
  joinedHistoryMuted: true,
};

type State = { roomId?: string; entries: DeviceEntry[] };

/** The drafts room of one account: its ID and the valid device entries in it, followed live. */
class DraftsRoom {
  private state: State = { entries: [] };
  private listeners = new Set<() => void>();
  private items: TimelineItem[] = [];
  private timeline?: Timeline;
  // The listener lives as long as the page: the room is small and silent.
  private handle?: TaskHandle;
  private starting?: Promise<void>;

  constructor(private readonly client: Client) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  snapshot = () => this.state;

  private set(next: State) {
    this.state = next;
    for (const listener of this.listeners) listener();
  }

  /** Reads account data and, when the room exists, follows its timeline. Never creates the room. */
  start(): Promise<void> {
    if (this.timeline) return Promise.resolve();
    if (this.starting) return this.starting;
    this.starting = readOwnRoomId(this.client, DRAFTS_ROOM.accountDataType)
      .then((roomId) => (roomId ? this.watch(roomId) : undefined))
      .catch((e: unknown) => console.warn('[trace] drafts room read failed', e))
      .finally(() => {
        this.starting = undefined;
      });
    return this.starting;
  }

  async watch(roomId: string) {
    if (this.timeline) return;
    const room = this.client.getRoom(roomId);
    if (!room || (await room.roomInfo()).membership !== Membership.Joined) return;
    const timeline = await room.timeline();
    if (this.timeline) return;
    this.timeline = timeline;
    this.set({ ...this.state, roomId });
    const ownUserId = this.client.userId();
    this.handle = await timeline.addListener({
      onUpdate: (diffs) => {
        this.items = applyDiffs(this.items, diffs, TimelineDiff as unknown as DiffVariants);
        this.set({ roomId, entries: this.items.map((item) => entryOf(item, ownUserId)).filter((e) => e !== null) });
      },
    });
    // The drafts room is small: read back to its start, a few pages at most.
    for (let page = 0; page < 5; page++) {
      if (await timeline.paginateBackwards(50).catch(() => true)) break;
    }
  }
}

/** A timeline item as a valid device entry, or null. An entry counts only when the SDK shows no shield on it. */
function entryOf(item: TimelineItem, ownUserId: string): DeviceEntry | null {
  const event = item.asEvent();
  if (!event || !EventOrTransactionId.EventId.instanceOf(event.eventOrTransactionId)) return null;
  const redacted =
    TimelineItemContent.MsgLike.instanceOf(event.content) && MsgLikeKind.Redacted.instanceOf(event.content.inner.content.kind);
  let raw: Record<string, unknown> | undefined;
  try {
    const json = event.lazyProvider.debugInfo().originalJson;
    raw = json ? (JSON.parse(json) as Record<string, unknown>) : undefined;
  } catch {
    raw = undefined;
  }
  if (!raw) return null;
  const verified = event.lazyProvider.getShields(false) === undefined;
  return deviceInfoFromEvent({ ...raw, sender: event.sender, redacted }, ownUserId, verified);
}

const rooms = new WeakMap<Client, DraftsRoom>();

function draftsFor(client: Client): DraftsRoom {
  let room = rooms.get(client);
  if (!room) {
    room = new DraftsRoom(client);
    rooms.set(client, room);
  }
  return room;
}

/** The drafts room state of the account, followed while on is true. */
function useDraftsState(client: Client, on: boolean): State {
  const room = draftsFor(client);
  useEffect(() => {
    if (on) void room.start();
  }, [room, on]);
  return useSyncExternalStore(room.subscribe, room.snapshot);
}

/** The ID of the drafts room once known, so the chat list leaves it out. */
export function useDraftsRoomId(client: Client, on: boolean): string | undefined {
  return useDraftsState(client, on).roomId;
}

const writing = new WeakSet<Client>();
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * The self entry of this browser: kind browser, the browser and its version, the system. Written after the link and
 * again when a field changed (a new browser version). Runs once sync is ready. A drafts room that account data does
 * not show yet may still be on its way from the first sync, so account data is read a few times before this browser
 * creates the room.
 */
export async function writeSelfEntry(client: Client): Promise<void> {
  if (writing.has(client)) return;
  writing.add(client);
  try {
    const session = client.session();
    const userId = client.userId();
    const id = `${userId}|${session.deviceId}`;
    const info = userAgentInfo(navigator.userAgent);
    const linkedKey = `bipe.web.devices.linkedAt.${id}`;
    const sentKey = `bipe.web.devices.sent.${id}`;
    const linkedAt = Number(readStorage(linkedKey)) || Date.now();
    const app = info?.kind === 'browser' ? info.app : null;
    const os = info?.kind === 'browser' ? info.os : null;
    const fingerprint = [app ?? '', os ?? '', String(linkedAt)].join('|');
    if (readStorage(sentKey) === fingerprint) return;
    let roomId: string | undefined;
    for (let attempt = 0; attempt < 5 && !roomId; attempt++) {
      if (attempt > 0) await pause(3000);
      // A failed read throws: it never counts as "no drafts room yet".
      roomId = await readOwnRoomId(client, DRAFTS_ROOM.accountDataType);
    }
    roomId = await openOwnRoom(client, DRAFTS_ROOM);
    const room = client.getRoom(roomId);
    if (!room) return;
    const field: Record<string, unknown> = { device_id: session.deviceId, source: 'self', kind: 'browser', linked_at: linkedAt };
    if (app) field.app = app;
    if (os) field.os = os;
    const body = [app, os].filter(Boolean).join(' · ') || 'browser';
    await room.sendRaw('m.room.message', JSON.stringify({ msgtype: deviceKinds.msgtype, body, [deviceKinds.field]: field }));
    writeStorage(linkedKey, String(linkedAt));
    writeStorage(sentKey, fingerprint);
    void draftsFor(client)
      .watch(roomId)
      .catch((e: unknown) => console.warn('[trace] drafts room watch failed', e));
  } catch (e) {
    console.warn('[trace] device entry write failed', e);
  } finally {
    writing.delete(client);
  }
}

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Private windows can refuse storage; the entry is then written again on the next load.
  }
}

/** This browser as a self entry, from its user agent. ts is now, so it beats an older entry of this device. */
function selfEntry(userId: string, deviceId: string): DeviceEntry | null {
  const info = userAgentInfo(navigator.userAgent);
  if (info?.kind !== 'browser') return null;
  const linkedAt = Number(readStorage(`bipe.web.devices.linkedAt.${userId}|${deviceId}`)) || null;
  return { deviceId, source: 'self', kind: 'browser', model: null, os: info.os, app: info.app, linkedAt, linkedBy: null, attested: false, ts: Date.now() };
}

/** GET /_matrix/client/v3/devices (Matrix spec 10.10.1): matrix-sdk-ffi has no device list. */
async function fetchDevices(client: Client): Promise<MatrixDevice[]> {
  const session = client.session();
  const base = session.homeserverUrl.replace(/\/$/, '');
  const res = await fetch(`${base}/_matrix/client/v3/devices`, { headers: { Authorization: `Bearer ${session.accessToken}` } });
  if (!res.ok) throw new Error(`devices ${res.status}`);
  const body = (await res.json()) as { devices?: unknown };
  if (!Array.isArray(body.devices)) throw new Error('devices: bad body');
  return body.devices.filter(
    (d): d is MatrixDevice => typeof d === 'object' && d !== null && typeof (d as MatrixDevice).device_id === 'string',
  );
}

export type DeviceList =
  | { phase: 'loading' }
  | { phase: 'failed' }
  | { phase: 'loaded'; rows: DeviceRow[]; counts: { devices: number; programs: number; unknown: number } };

/** Spec linked-devices appear: the devices and the entries of the drafts room, through device_rows. */
export function useDeviceList(client: Client, on = true): { list: DeviceList; retry: () => void } {
  const { entries } = useDraftsState(client, on);
  const [attempt, setAttempt] = useState(0);
  const [devices, setDevices] = useState<{ attempt: number; devices?: MatrixDevice[]; failed?: boolean }>({ attempt: -1 });
  useEffect(() => {
    if (!on) return;
    let stopped = false;
    fetchDevices(client)
      .then((found) => !stopped && setDevices({ attempt, devices: found }))
      .catch((e: unknown) => {
        console.warn('[trace] devices read failed', e);
        if (!stopped) setDevices({ attempt, failed: true });
      });
    return () => {
      stopped = true;
    };
  }, [client, attempt, on]);
  const retry = () => setAttempt((n) => n + 1);
  if (devices.attempt !== attempt) return { list: { phase: 'loading' }, retry };
  if (!devices.devices) return { list: { phase: 'failed' }, retry };
  const thisId = client.session().deviceId;
  // This browser knows itself: its row shows the browser and system before its own entry comes back from the server.
  const own = selfEntry(client.userId(), thisId);
  const { rows, counts } = deviceRows(thisId, devices.devices, own ? [...entries, own] : entries);
  return { list: { phase: 'loaded', rows, counts }, retry };
}

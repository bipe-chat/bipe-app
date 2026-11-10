// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The shared Bipe rules from bipe-core (ADR 0267, bipe-chat/bipe#1458): the same code that iOS, Mac, Android and
// Windows run. scripts/fetch-core.sh puts the pinned release into ./core, and signed-lists.ts loads it (coreReady).
// Each function keeps the signature of the bipe-shared reference it replaces (tools/lib/registries.mjs), so a call
// site changes only its import. The last `reg` argument of the reference is accepted and ignored: the core carries
// the registries. main.tsx waits for coreReady before the first render.
import * as core from './core';
import { currentList } from './signed-lists';

const big = (n: number) => BigInt(Math.trunc(n));
const optionalBig = (n: number | null | undefined) => (n === null || n === undefined ? undefined : big(n));
const json = (value: unknown) => (value === null || value === undefined ? undefined : JSON.stringify(value));

// --- Text and names ------------------------------------------------------------------------------------------------

/** normalize(): with the name homoglyph map when one is given, else without folding. */
export function normalize(input: string, homoglyphMap?: Record<string, string>): string {
  const folding = homoglyphMap !== undefined && Object.keys(homoglyphMap).length > 0;
  return core.normalizeText(input, folding ? core.TextFold.Names : core.TextFold.Plain);
}

/** Security rule 8: the contact name that `name` copies after normalize(), or null. */
export function contactCollision(name: string, contactNames: string[], _reg?: unknown): string | null {
  return core.contactCollision(name, contactNames) ?? null;
}

export function socialHandleClean(input: string): string {
  return core.socialHandleClean(input);
}

export function socialUrl(platform: string, input: string, _reg?: unknown): string {
  const url = core.socialUrl(platform, input);
  if (url === undefined) throw new Error(`socialUrl: unknown platform ${platform}`);
  return url;
}

// --- Scam warning --------------------------------------------------------------------------------------------------

/** The reference ids, in the order of core.ScamKind. */
const SCAM_KINDS = [
  'extortion', 'pixCode', 'boleto', 'remoteApp', 'pinRequest', 'pixKey',
  'deliveryFee', 'lookalikeLink', 'bankAlert', 'receipt', 'newNumber', 'urgency',
] as const;

/** The one warning kind of a message, or null. Kinds in `skip` are passed over. */
export function scamWarningKind(text: string, _reg?: unknown, skip: string[] = []): string | null {
  const skipping = skip.map((id) => SCAM_KINDS.indexOf(id as (typeof SCAM_KINDS)[number])).filter((i) => i >= 0) as core.ScamKind[];
  const kind = core.scamWarningKind(text, skipping);
  return kind === undefined ? null : SCAM_KINDS[kind];
}

// --- Profile facts -------------------------------------------------------------------------------------------------

type ApiProfileFacts = {
  created_at?: string | null;
  name_changes?: string[];
  photo_changes?: string[];
  number_moved_out?: string[];
  number_moved_in?: string[];
};

const PROFILE_FACTS = ['created', 'photo', 'name', 'numberOut', 'numberIn'] as const;

/** The profile fact lines (ADR 0049) in the reference shape: { lines: [{ fact, count?, at }], notice, numberNotice? }. */
export function profileFactLines(facts: ApiProfileFacts, nowMs: number, _reg?: unknown, { alwaysCreated = false } = {}) {
  const result = core.profileFactLines(
    {
      createdAt: facts.created_at ?? undefined,
      nameChanges: facts.name_changes ?? [],
      photoChanges: facts.photo_changes ?? [],
      numberMovedOut: facts.number_moved_out ?? [],
      numberMovedIn: facts.number_moved_in ?? [],
    },
    big(nowMs),
    alwaysCreated,
  );
  const lines = result.lines.map((line) => ({
    fact: PROFILE_FACTS[line.fact],
    ...(line.count === undefined ? {} : { count: line.count }),
    at: new Date(Number(line.atMs)).toISOString(),
  }));
  if (result.numberNotice === undefined) return { lines, notice: result.notice };
  return { lines, notice: result.notice, numberNotice: result.numberNotice === core.NumberNotice.In ? 'in' : 'out' };
}

// --- Cleared chats -------------------------------------------------------------------------------------------------

export function clearedChatMarker(content: unknown, roomId: string): number | null {
  const marker = core.clearedChatMarker(json(content), roomId);
  return marker === undefined ? null : Number(marker);
}

export function clearedChatHidden(marker: number | null, latestTs: number | null): boolean {
  return core.clearedChatHidden(optionalBig(marker), optionalBig(latestTs));
}

export function clearedChatKeepsEvent(marker: number | null, eventTs: number): boolean {
  return core.clearedChatKeepsEvent(optionalBig(marker), big(eventTs));
}

export function clearedChatsWithClear(content: unknown, roomId: string, ts: number): Record<string, unknown> {
  return JSON.parse(core.clearedChatsWithClear(json(content), roomId, big(ts))) as Record<string, unknown>;
}

// --- Forward marks -------------------------------------------------------------------------------------------------

export function forwardCount(content: unknown, _reg?: unknown): number {
  return core.forwardCount(JSON.stringify(content ?? null));
}

export function forwardedContent(original: unknown, newContent: unknown, _reg?: unknown): Record<string, unknown> {
  const out = core.forwardedContent(JSON.stringify(original ?? null), json(newContent));
  return out === undefined ? (original as Record<string, unknown>) : (JSON.parse(out) as Record<string, unknown>);
}

export function forwardChatLimit(sentCounts: number[], _reg?: unknown): number {
  return core.forwardChatLimit(sentCounts.map((c) => Math.max(0, Math.trunc(c))));
}

// --- Work notes ----------------------------------------------------------------------------------------------------

type NotesContext = { ownUserId: string; joinedRoomIds?: string[]; seatIds?: string[] };
type Hidden = { show: false; reason: string };
export type NoteResult = { show: true; note: { roomId: string; body: string; seatId: string | null } } | Hidden;
export type ReplyByResult = { show: true; replyBy: { roomId: string; eventId: string; seatId: string } } | Hidden;

export function workNoteFromEvent(event: unknown, ctx: NotesContext, _reg?: unknown): NoteResult {
  const result = core.workNoteFromEvent(JSON.stringify(event ?? null), ctx.ownUserId, ctx.joinedRoomIds ?? [], ctx.seatIds ?? []);
  if (result.tag === core.WorkNoteResult_Tags.Show) {
    const { note } = result.inner;
    return { show: true, note: { roomId: note.roomId, body: note.body, seatId: note.seatId ?? null } };
  }
  return { show: false, reason: result.inner.reason };
}

export function replyByFromEvent(event: unknown, ctx: NotesContext, _reg?: unknown): ReplyByResult {
  const result = core.replyByFromEvent(JSON.stringify(event ?? null), ctx.ownUserId, ctx.seatIds ?? []);
  if (result.tag === core.ReplyByResult_Tags.Show) {
    const { replyBy } = result.inner;
    return { show: true, replyBy: { roomId: replyBy.roomId, eventId: replyBy.eventId, seatId: replyBy.seatId } };
  }
  return { show: false, reason: result.inner.reason };
}

// --- Account and chat-list state ----------------------------------------------------------------------------------

/** Settings that follow the account (bipe-chat/bipe#1161): the content after this device reads it. */
export function syncedSettingsReconcile(
  server: Record<string, unknown>,
  local: Record<string, unknown>,
  pending: string[],
  first: boolean,
): Record<string, unknown> {
  return JSON.parse(core.syncedSettingsReconcile(JSON.stringify(server), JSON.stringify(local), pending, first)) as Record<string, unknown>;
}

type DmCandidate = { roomId: string; peerUserId: string; latestActivityMs: number | null; eligible: boolean; cleared?: boolean };

/** One eligible DM per peer: peer user ID to room ID. */
export function newestDmRooms(candidates: DmCandidate[], { includingCleared = false } = {}): Record<string, string> {
  const rooms = core.newestDmRooms(
    candidates.map((c) => ({
      roomId: c.roomId,
      peerUserId: c.peerUserId,
      eligible: c.eligible,
      cleared: c.cleared ?? false,
      latestActivityMs: optionalBig(c.latestActivityMs),
    })),
    includingCleared,
  );
  return Object.fromEntries(rooms);
}

/** Reconciles the Matrix archive tag with the local baseline (bipe-chat/bipe#1121 rule chatArchiveSync). */
export function chatArchiveSync(
  marker: number | null,
  synced: boolean,
  serverArchived: boolean,
  activity: number | null,
  incoming: number | null,
): { marker: number | null; synced: boolean; tagWrite: boolean | null } {
  const r = core.chatArchiveSync(optionalBig(marker), synced, serverArchived, optionalBig(activity), optionalBig(incoming));
  return { marker: r.marker === undefined ? null : Number(r.marker), synced: r.synced, tagWrite: r.tagWrite ?? null };
}

/** ADR 0168: a Bipe user ID, checked against the current signed operator list, as the apps do. */
export function isBipeUserID(userId: string, _reg?: unknown): boolean {
  return core.isBipeUserId(userId, currentList(core.ListType.Operators), big(Date.now() / 1000));
}

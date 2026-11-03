// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// A small client for bipe-api (https://api.bipe.net.br; bipe-shared api/openapi.yaml), only the calls the web
// uses. Auth as the apps (BipeAPIClient, BipeOpenIDTokens): the core routes take a Matrix OpenID token from
// the account's homeserver (Client.requestOpenidToken) as a bearer token with X-Matrix-Server-Name, renewed
// a minute before it expires; the account-type route takes none. Every failure is quiet: the caller gets
// undefined and the screen keeps its fail-closed default (a circle, no handle, no facts). Until the API
// allows the web origin (CORS, bipe-chat/bipe-api#269) the browser refuses every call the same way.

import type { ClientLike as Client } from './generated/matrix_sdk_ffi';

export const API_BASE = 'https://api.bipe.net.br';

type OpenId = { token: string; server: string; renewAt: number };

export type HandleRecord = { handle: string; user_id: string };
export type ProfileFacts = { user_id: string; created_at?: string; name_changes: string[]; photo_changes: string[] };

async function call<T>(path: string, init: RequestInit = {}): Promise<T | undefined> {
  try {
    const res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...init.headers },
    });
    if (!res.ok) {
      console.info(`[trace] bipe-api ${path} → ${res.status}`);
      return undefined;
    }
    return (await res.json()) as T;
  } catch (e) {
    console.info(`[trace] bipe-api ${path} failed`, e);
    return undefined;
  }
}

// Account types are public and do not change for an account (ADR 0132): one answer per user ID per page.
const types = new Map<string, Promise<'personal' | 'company' | undefined>>();

/** GET /v1/accounts/{userId}/type. No auth. A failure or 404 counts as personal (fail closed: a circle). */
export function accountType(userId: string): Promise<'personal' | 'company' | undefined> {
  let hit = types.get(userId);
  if (!hit) {
    hit = call<{ account_type: string }>(`/v1/accounts/${encodeURIComponent(userId)}/type`).then((r) =>
      r?.account_type === 'company' ? 'company' : r?.account_type === 'personal' ? 'personal' : undefined,
    );
    types.set(userId, hit);
  }
  return hit;
}

/** Contract PublicProfile: what anyone with the exact handle sees. socials: network id → handle without @ (ADR 0228). */
export type PublicProfile = { handle: string; name: string; bio?: string; photo_url?: string; socials?: Record<string, string> };

/** GET /v1/profiles/{handle}. No auth. A 404 (no such handle, or the profile is off) is undefined. */
export function publicProfile(handle: string): Promise<PublicProfile | undefined> {
  return call<PublicProfile>(`/v1/profiles/${encodeURIComponent(handle)}`);
}

/** The OpenID-authenticated core routes of one account. */
export class BipeApi {
  private openId?: OpenId;
  private pending?: Promise<OpenId | undefined>;
  private handles = new Map<string, string | null>();

  constructor(private readonly client: Client) {}

  private async token(): Promise<OpenId | undefined> {
    if (this.openId && Date.now() < this.openId.renewAt) return this.openId;
    this.pending ??= this.client
      .requestOpenidToken()
      .then((t) => {
        this.openId = {
          token: t.accessToken,
          server: t.matrixServerName,
          renewAt: Date.now() + Math.max(0, Number(t.expiresInSeconds) - 60) * 1000,
        };
        return this.openId;
      })
      .catch((e: unknown) => {
        console.info('[trace] openid token failed', e);
        return undefined;
      })
      .finally(() => (this.pending = undefined));
    return this.pending;
  }

  private async authed<T>(path: string, init: RequestInit = {}): Promise<T | undefined> {
    const t = await this.token();
    if (!t) return undefined;
    return call<T>(path, {
      ...init,
      headers: { Authorization: `Bearer ${t.token}`, 'X-Matrix-Server-Name': t.server, ...init.headers },
    });
  }

  /** GET /v1/handles/{handle}: the exact holder of a handle (no search, no directory). */
  lookupHandle(handle: string): Promise<HandleRecord | undefined> {
    return this.authed<HandleRecord>(`/v1/handles/${encodeURIComponent(handle.replace(/^@/, '').toLowerCase())}`);
  }

  /** POST /v1/handles/resolve: user ID → handle (without @). Cached for the page; IDs without a handle stay null. */
  async resolveHandles(userIds: string[]): Promise<Map<string, string>> {
    const missing = [...new Set(userIds)].filter((id) => !this.handles.has(id)).slice(0, 100);
    if (missing.length > 0) {
      const r = await this.authed<{ handles: Record<string, string> }>('/v1/handles/resolve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ user_ids: missing }),
      });
      if (r) for (const id of missing) this.handles.set(id, r.handles[id] ?? null);
    }
    const out = new Map<string, string>();
    for (const id of userIds) {
      const h = this.handles.get(id);
      if (h) out.set(id, h);
    }
    return out;
  }

  /** POST /v1/profile-facts (ADR 0049). */
  async profileFacts(userId: string): Promise<ProfileFacts | undefined> {
    const r = await this.authed<{ facts: ProfileFacts[] }>('/v1/profile-facts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user_ids: [userId] }),
    });
    return r?.facts.find((f) => f.user_id === userId);
  }
}

const apis = new WeakMap<Client, BipeApi>();

/** The API of one account's client, made once per client. */
export function apiFor(client: Client): BipeApi {
  let api = apis.get(client);
  if (!api) {
    api = new BipeApi(client);
    apis.set(client, api);
  }
  return api;
}

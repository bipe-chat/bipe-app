// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Media through the SDK (MediaLoader in AvatarView.swift). The SDK fetches and, for encrypted media,
// decrypts; the page shows the bytes from a Blob object URL (CSP img-src 'self' data: blob:). Each
// component makes its own object URL and revokes it on unmount; the bytes stay in a small memory cache.

import { createContext, useContext, useEffect, useState } from 'react';
import { MediaSource, type ClientLike as Client, type MediaSourceLike } from './generated/matrix_sdk_ffi';

export const ClientContext = createContext<Client | undefined>(undefined);

export function useClient(): Client {
  const client = useContext(ClientContext);
  if (!client) throw new Error('useClient outside ClientContext');
  return client;
}

// Most recent last. Thumbnails and avatars are small; full images and files are not cached.
const cache = new Map<string, Promise<ArrayBuffer>>();
const CACHE_SIZE = 300;

function remember(key: string, load: () => Promise<ArrayBuffer>): Promise<ArrayBuffer> {
  const hit = cache.get(key);
  if (hit) {
    cache.delete(key);
    cache.set(key, hit);
    return hit;
  }
  const next = load();
  next.catch(() => cache.delete(key));
  cache.set(key, next);
  if (cache.size > CACHE_SIZE) cache.delete(cache.keys().next().value!);
  return next;
}

/** The thumbnail of an mxc URL at size x size device pixels (avatars; iOS asks for 3 x the point size). */
export function thumbnailOfUrl(client: Client, mxcUrl: string, size: number): Promise<ArrayBuffer> {
  return remember(`${mxcUrl}#${size}`, () => {
    const source = MediaSource.fromUrl(mxcUrl);
    return client.getMediaThumbnail(source, BigInt(size), BigInt(size));
  });
}

/**
 * The preview of a timeline image or video: the sender's thumbnail file when there is one (encrypted
 * media carry their own), else a server thumbnail, else the file itself (an encrypted file has no
 * server thumbnail).
 */
export function previewOf(
  client: Client,
  source: MediaSourceLike,
  thumbnail: MediaSourceLike | undefined,
  size: number,
): Promise<ArrayBuffer> {
  return remember(`${source.toJson()}#preview${size}`, async () => {
    if (thumbnail) return client.getMediaContent(thumbnail);
    try {
      return await client.getMediaThumbnail(source, BigInt(size), BigInt(size));
    } catch {
      return client.getMediaContent(source);
    }
  });
}

/** An object URL for bytes that load, revoked on unmount or when the key changes. */
export function useObjectUrl(
  key: string | undefined,
  load: () => Promise<ArrayBuffer>,
  type?: string,
): { url?: string; failed: boolean; retry: () => void } {
  const [url, setUrl] = useState<string>();
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!key) return;
    let made: string | undefined;
    let stopped = false;
    setFailed(false);
    load()
      .then((bytes) => {
        if (stopped) return;
        made = URL.createObjectURL(new Blob([bytes], type ? { type } : undefined));
        setUrl(made);
      })
      .catch((e: unknown) => {
        console.warn('[trace] media load failed', key, e);
        if (!stopped) setFailed(true);
      });
    return () => {
      stopped = true;
      if (made) URL.revokeObjectURL(made);
      setUrl(undefined);
    };
    // load is a new closure on each render; key names what it loads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, type, attempt]);
  return { url, failed, retry: () => setAttempt((n) => n + 1) };
}

/** The full file, for the viewer and for a download. Not cached. */
export async function objectUrlOfContent(client: Client, source: MediaSourceLike, type?: string): Promise<string> {
  const bytes = await client.getMediaContent(source);
  return URL.createObjectURL(new Blob([bytes], type ? { type } : undefined));
}

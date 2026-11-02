// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Desktop notifications while the page is open (Notification API; no push service worker yet) and the
// unread count in the tab title. They follow the app rules: DMs notify, groups notify only for mentions
// (quiet groups, ADR 0026 and ADR 0053), muted chats never notify, own messages never notify, and the chat
// that is open in a visible page does not notify.

import { useEffect, useRef, useState } from 'react';
import { RoomNotificationMode, type RoomLike as Room } from './generated/matrix_sdk_ffi';
import type { ChatSummary } from './chat-model';
import { rowPreview } from './row-preview';

export function notificationsSupported(): boolean {
  return typeof Notification !== 'undefined';
}

/** The permission, kept in state so the banner goes away once the person answers. */
export function useNotificationPermission(): [NotificationPermission | 'unsupported', () => void] {
  const [permission, setPermission] = useState<NotificationPermission | 'unsupported'>(
    notificationsSupported() ? Notification.permission : 'unsupported',
  );
  // Only after a click: browsers refuse a request that no user action started.
  const ask = () => {
    if (!notificationsSupported()) return;
    void Notification.requestPermission().then(setPermission);
  };
  return [permission, ask];
}

export function useDesktopNotifications(
  summaries: ChatSummary[],
  openRoomId: string | undefined,
  ownUserId: string,
  onOpen: (room: Room) => void,
) {
  const seen = useRef(new Map<string, number>());
  useEffect(() => {
    for (const chat of summaries) {
      if (chat.kind !== 'dm' && chat.kind !== 'group') continue;
      const latest = chat.latest;
      if (!latest) continue;
      const before = seen.current.get(chat.roomId);
      if (before !== undefined && latest.at <= before) continue;
      seen.current.set(chat.roomId, latest.at);
      // The first sight of a chat is its baseline, not news.
      if (before === undefined || latest.isOwn) continue;
      if (!notificationsSupported() || Notification.permission !== 'granted') continue;
      if (chat.roomId === openRoomId && document.visibilityState === 'visible') continue;
      if (chat.info?.cachedUserDefinedNotificationMode === RoomNotificationMode.Mute) continue;
      if (chat.kind === 'group' && Number(chat.info?.numUnreadMentions ?? 0) === 0) continue;
      const text = rowPreview(latest, chat.kind === 'dm', ownUserId);
      if (!text) continue;
      const body = chat.kind === 'group' && latest.senderName ? `${latest.senderName}: ${text}` : text;
      const notification = new Notification(chat.face.name, { body, tag: chat.roomId });
      notification.onclick = () => {
        window.focus();
        onOpen(chat.room);
        notification.close();
      };
    }
  }, [summaries, openRoomId, ownUserId, onOpen]);
}

/**
 * The tab title: "(n) <page> · Bipe", n the unread badge, page the open chat or screen; "(n) Bipe" on the list.
 * The favicon is the Bipe mark in accent.fill, with a dot while n > 0.
 */
export function useTabTitle(unread: number, page?: string) {
  useEffect(() => {
    const count = unread > 0 ? `(${unread.toLocaleString()}) ` : '';
    document.title = `${count}${page ? `${page} · ` : ''}Bipe`;
  }, [unread, page]);
  useEffect(() => drawFavicon(unread > 0), [unread]);
}

// The 64-unit mark of bipe-shared design/tokens.json appThemes.icon.path, on a 64 px canvas.
const MARK = new Path2D('M8 10c0-1.1.9-2 2-2h28c9.9 0 18 8.1 18 18v12c0 9.9-8.1 18-18 18H26C16.1 56 8 47.9 8 38V10Z');

function drawFavicon(dot: boolean) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const css = getComputedStyle(document.documentElement);
  ctx.fillStyle = css.getPropertyValue('--bipe-accent-fill').trim();
  ctx.fill(MARK);
  if (dot) {
    ctx.beginPath();
    ctx.arc(50, 14, 12, 0, Math.PI * 2);
    ctx.fillStyle = css.getPropertyValue('--bipe-surface-base').trim();
    ctx.fill();
    ctx.beginPath();
    ctx.arc(50, 14, 9, 0, Math.PI * 2);
    ctx.fillStyle = css.getPropertyValue('--bipe-trust-danger-line').trim();
    ctx.fill();
  }
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.href = canvas.toDataURL('image/png');
}

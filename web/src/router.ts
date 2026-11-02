// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Permalinks for the pages of the web client, with the History API (no router dependency). The edge serves
// index.html for every path, so a deep link loads the app, which reads the path here. Overlays (the media
// viewer, menus, alerts, sheets) have no URL.
//
//   /                      chat list, Conversas      /grupos              chat list, Grupos
//   /pedidos               the requests box          /pedidos/<roomId>    one request
//   /arquivadas            archived chats            /ajustes             settings; /ajustes/<group> one group
//   /c/<roomId>            a chat                    /c/<roomId>/info     the chat with its info panel
//   /c/<roomId>/e/<eventId>  the chat at a message
//   /trabalho              Trabalho of a company account; /trabalho/<tool> one tool (ADR 0226)

import { useEffect, useState } from 'react';

export type Route =
  | { kind: 'list'; scope: 'conversas' | 'grupos' }
  | { kind: 'archived' }
  | { kind: 'requests' }
  | { kind: 'request'; roomId: string }
  | { kind: 'chat'; roomId: string; info?: boolean; eventId?: string }
  | { kind: 'settings'; group?: string }
  | { kind: 'work'; tool?: string };

const enc = encodeURIComponent;

export function pathOf(route: Route): string {
  switch (route.kind) {
    case 'list':
      return route.scope === 'grupos' ? '/grupos' : '/';
    case 'archived':
      return '/arquivadas';
    case 'requests':
      return '/pedidos';
    case 'request':
      return `/pedidos/${enc(route.roomId)}`;
    case 'chat':
      return `/c/${enc(route.roomId)}${route.info ? '/info' : route.eventId ? `/e/${enc(route.eventId)}` : ''}`;
    case 'settings':
      return route.group ? `/ajustes/${enc(route.group)}` : '/ajustes';
    case 'work':
      return route.tool ? `/trabalho/${enc(route.tool)}` : '/trabalho';
  }
}

export function routeOf(path: string): Route {
  const parts = path
    .split('/')
    .filter(Boolean)
    .map((p) => {
      try {
        return decodeURIComponent(p);
      } catch {
        return p;
      }
    });
  const [first, second, third, fourth] = parts;
  if (first === 'grupos') return { kind: 'list', scope: 'grupos' };
  if (first === 'arquivadas') return { kind: 'archived' };
  if (first === 'pedidos') return second ? { kind: 'request', roomId: second } : { kind: 'requests' };
  if (first === 'ajustes') return { kind: 'settings', group: second };
  if (first === 'trabalho') return { kind: 'work', tool: second };
  if (first === 'c' && second) {
    if (third === 'info') return { kind: 'chat', roomId: second, info: true };
    if (third === 'e' && fourth) return { kind: 'chat', roomId: second, eventId: fourth };
    return { kind: 'chat', roomId: second };
  }
  return { kind: 'list', scope: 'conversas' };
}

const listeners = new Set<() => void>();

/** Goes to a route: a new history entry, or a replacement when the current one is wrong (a dead link). */
export function navigate(route: Route, replace = false) {
  const path = pathOf(route);
  if (path === window.location.pathname) return;
  if (replace) window.history.replaceState(null, '', path);
  else window.history.pushState(null, '', path);
  listeners.forEach((l) => l());
}

/** The current route; follows navigate() and the back and forward buttons. */
export function useRoute(): Route {
  const [route, setRoute] = useState(() => routeOf(window.location.pathname));
  useEffect(() => {
    const update = () => setRoute(routeOf(window.location.pathname));
    listeners.add(update);
    window.addEventListener('popstate', update);
    return () => {
      listeners.delete(update);
      window.removeEventListener('popstate', update);
    };
  }, []);
  return route;
}

/** The web link of a message, for Copiar link. Useful to the same account only. */
export function messageLink(roomId: string, eventId: string): string {
  return `${window.location.origin}${pathOf({ kind: 'chat', roomId, eventId })}`;
}

/** The route of a link to this web client (the same origin or web.bipe.chat), or undefined. */
export function internalRoute(href: string): Route | undefined {
  try {
    const url = new URL(href, window.location.origin);
    if (url.origin !== window.location.origin && url.host !== 'web.bipe.chat') return undefined;
    return routeOf(url.pathname);
  } catch {
    return undefined;
  }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec business-chat-bar (BusinessChatModel on iOS): the tools a company turned on and the cards of the open records
// of this DM. Nothing comes from the room: the cards come only from bipe-api (ADR 0102). A failed read hides that
// tool or keeps the cards it had, never the chat. On the company account (ownerMode) the cards show the customer of
// this DM and open the owner screens.

import { useCallback, useEffect, useRef, useState } from 'react';
import { accountType, apiFor } from '../../api';
import type { ClientLike as Client } from '../../generated/matrix_sdk_ffi';
import type { Booking, BookingCustomer, CatalogItem, Order, QueuePosition, Quote } from '../../bipe/generated/business-api';
import { businessClientFor, type BusinessClient } from '../business-client';
import { unitCents } from '../Orders';

const DAY_MS = 86_400_000;
const QUEUE_REFRESH_MS = 30_000;
const OPEN_ORDERS: Order['status'][] = ['sent', 'accepted', 'ready'];

export type BusinessRoute =
  | { kind: 'book'; serviceId?: string }
  | { kind: 'reschedule'; booking: Booking }
  | { kind: 'catalog' }
  | { kind: 'order'; itemId?: string }
  | { kind: 'queue' }
  | { kind: 'quoteBooking'; quote: Quote }
  | { kind: 'quoteVersions'; quote: Quote }
  | { kind: 'ownerBooking'; booking: Booking }
  | { kind: 'ownerOrder'; order: Order }
  | { kind: 'ownerQuote'; quote: Quote }
  | { kind: 'ownerQueue' };

type Tools = {
  isCompany: boolean;
  handle?: string;
  canBook: boolean;
  hasCatalog: boolean;
  canOrder: boolean;
  queueOpen: boolean;
  canRequestQuote: boolean;
  serviceNames: Record<string, string>;
  agendaNames: Record<string, string>;
};
type Records = { bookings: Booking[]; orders: Order[]; quotes: Quote[]; queuePlaces: QueuePosition[] };

const NO_TOOLS: Tools = {
  isCompany: false,
  canBook: false,
  hasCatalog: false,
  canOrder: false,
  queueOpen: false,
  canRequestQuote: false,
  serviceNames: {},
  agendaNames: {},
};
const NO_RECORDS: Records = { bookings: [], orders: [], quotes: [], queuePlaces: [] };

const userIdOf = (customer: BookingCustomer) => ('user_id' in customer ? customer.user_id : undefined);

/** isOrderable on iOS: an active product with a fixed price and amount_cents. */
export const isOrderable = (item: CatalogItem) => unitCents(item) !== undefined;

export function useBusinessChat(matrix: Client, peer: string | undefined, peerName: string) {
  const api: BusinessClient = businessClientFor(matrix);
  const [tools, setTools] = useState(NO_TOOLS);
  const [records, setRecords] = useState(NO_RECORDS);
  const [ownerMode, setOwnerMode] = useState(false);
  const [route, setRoute] = useState<BusinessRoute>();
  const [notice, setNotice] = useState<string>();
  // A newer load, or another chat, makes the answers of an older one stale.
  const loadId = useRef(0);

  const refresh = useCallback(async () => {
    const id = ++loadId.current;
    const live = () => id === loadId.current;
    if (!peer || peer === matrix.userId()) {
      setTools(NO_TOOLS);
      setRecords(NO_RECORDS);
      setOwnerMode(false);
      return;
    }
    const [mine, theirs] = await Promise.all([
      accountType(matrix.userId()).catch(() => undefined),
      accountType(peer).catch(() => undefined),
    ]);
    if (!live()) return;
    const owner = mine === 'company';
    const company = theirs === 'company';
    setOwnerMode(owner);
    const loaded = company ? await loadTools(matrix, api, peer) : NO_TOOLS;
    if (!live()) return;
    setTools(loaded);
    if (!owner && !company) {
      setRecords(NO_RECORDS);
      return;
    }
    const next = await loadRecords(api, peer, owner, records);
    if (live()) setRecords(next);
    // records is the fallback of a failed read; a change must not start another load.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matrix, api, peer]);

  useEffect(() => {
    setRecords(NO_RECORDS);
    setTools(NO_TOOLS);
    setRoute(undefined);
    void refresh();
    const onFront = () => {
      if (!document.hidden) void refresh();
    };
    document.addEventListener('visibilitychange', onFront);
    return () => {
      loadId.current++;
      document.removeEventListener('visibilitychange', onFront);
    };
  }, [refresh]);

  // queue-customer load: the card reads again every 30 seconds while it shows and the place is active.
  const hasPlace = records.queuePlaces.length > 0;
  useEffect(() => {
    if (!hasPlace) return;
    const timer = setInterval(() => {
      if (!document.hidden) void refresh();
    }, QUEUE_REFRESH_MS);
    return () => clearInterval(timer);
  }, [hasPlace, refresh]);

  /** A flow closed: shows its result and reads the cards again. */
  const finished = useCallback(
    (message?: string) => {
      setRoute(undefined);
      if (message) setNotice(message);
      void refresh();
    },
    [refresh],
  );

  return {
    matrix,
    api,
    peer,
    peerName,
    ...tools,
    ...records,
    ownerMode,
    /** The business segment of the customer routes: the handle, else the Matrix ID. */
    business: tools.handle ?? peer ?? '',
    route,
    setRoute,
    notice,
    setNotice,
    refresh,
    finished,
  };
}

export type BusinessChat = ReturnType<typeof useBusinessChat>;

async function loadTools(matrix: Client, api: BusinessClient, peer: string): Promise<Tools> {
  const handle = await apiFor(matrix)
    .resolveHandles([peer])
    .then((found) => found.get(peer))
    .catch(() => undefined);
  const business = handle ?? peer;
  // Spec business-chat-bar: a tool shows only when it is live (ADR 0194 point 2).
  const live = new Set(await api.liveTools(business).then((r) => r.tools, () => []));
  const [services, catalog, queue] = await Promise.all([
    live.has('agenda') ? api.publicServices(business).then((r) => r.services).catch(() => []) : [],
    api.publicCatalog(business).then((r) => r.items).catch((): CatalogItem[] => []),
    api.publicQueue(business).catch(() => undefined),
  ]);
  const agendas = services.length > 0 ? await api.publicAgendas(business).then((r) => r.agendas).catch(() => []) : [];
  return {
    isCompany: true,
    handle,
    canBook: services.length > 0,
    hasCatalog: live.has('catalog') && catalog.length > 0,
    canOrder: live.has('orders') && catalog.some(isOrderable),
    queueOpen: live.has('queue') && (queue?.open ?? false),
    canRequestQuote: live.has('quotes'),
    serviceNames: Object.fromEntries(services.map((x) => [x.id, x.name])),
    agendaNames: Object.fromEntries(agendas.map((a) => [a.id, a.name])),
  };
}

/** Each list loads on its own: a failure keeps the cards it had. */
async function loadRecords(api: BusinessClient, peer: string, owner: boolean, had: Records): Promise<Records> {
  const now = Date.now();
  const [bookings, orders, quotes, places] = await Promise.all([
    api.myBookings().then((r) => r.bookings).catch(() => undefined),
    api.myOrders().then((r) => r.orders).catch(() => undefined),
    api.myQuotes().then((r) => r.quotes).catch(() => undefined),
    api.myQueuePlaces().then((r) => r.places).catch(() => undefined),
  ]);
  return {
    bookings: bookings?.filter((b) => (owner ? userIdOf(b.customer) === peer : b.business_id === peer)) ?? had.bookings,
    // Open orders, and the ones of the last day, so that a refusal or a delivery still shows.
    orders:
      orders?.filter(
        (o) =>
          (owner ? userIdOf(o.customer) === peer : o.business_id === peer) &&
          (OPEN_ORDERS.includes(o.status) || Date.parse(o.created_at) > now - DAY_MS),
      ) ?? had.orders,
    // Offered and expired quotes, and the ones answered in the last two days.
    quotes:
      quotes?.filter((q) => {
        const current = q.versions.find((v) => v.version === q.current_version);
        const open = q.status === 'offered' || q.status === 'expired';
        return (
          (owner ? q.customer_id === peer : q.business_id === peer) &&
          (open || (current !== undefined && Date.parse(current.created_at) > now - 2 * DAY_MS))
        );
      }) ?? had.quotes,
    queuePlaces:
      places?.filter(
        (p) =>
          (owner ? userIdOf(p.place.customer) === peer : p.place.business_id === peer) &&
          (p.place.status === 'waiting' || p.place.status === 'called'),
      ) ?? had.queuePlaces,
  };
}

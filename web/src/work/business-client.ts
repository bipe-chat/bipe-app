// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The owner routes of the business tools (ADR 0226): the same bipe-api routes as the apps' Trabalho tab
// (bipe-api src/business-routes.ts, contract bipe-shared api/business-schemas.json). The types come from
// bipe-shared through generated/business-api.ts. Auth as the apps: the account's own Matrix access token
// (bipe-api MatrixAuth.userId, whoami), not an OpenID token. A failed call throws WorkError with the API's
// error code, so a screen can show what went wrong; reads that a screen may skip are the caller's choice.

import type { ClientLike as Client } from '../generated/matrix_sdk_ffi';
import type {
  Agenda,
  AgendaActivation,
  AgendaFeed,
  AgendaBlock,
  AgendaCloseDay,
  AgendaInput,
  AgendaSettings,
  AgendaState,
  Availability,
  AvailabilityRules,
  Booking,
  BookingHold,
  BookingMove,
  BusyImport,
  BusinessLiveTools,
  BusinessToday,
  BusinessToolId,
  BusinessTools,
  BusinessToolState,
  Catalog,
  CatalogItem,
  CatalogItemInput,
  Order,
  OrderPolicy,
  OrderInput,
  OrderRefuseReason,
  Override,
  OverrideInput,
  OwnerBooking,
  OwnerOrderInput,
  OwnerQueuePlace,
  Policy,
  Queue,
  QueuePlace,
  QueuePolicy,
  Quote,
  QuoteAccept,
  QueuePosition,
  QuoteLinkInput,
  QuoteLinkResult,
  QuoteRequest,
  QuoteVersionInput,
  Service,
  SeatList,
  ServiceInput,
  SlotDays,
  WebSettings,
  WorkSetup,
  WorkSuggestions,
} from '../bipe/generated/business-api';

export const API_BASE = 'https://api.bipe.net.br';

/** An error answer of bipe-api: { error, reason? }, or network for no answer. */
export class WorkError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    readonly reason?: string,
  ) {
    super(reason ? `${code}: ${reason}` : code);
  }
}

/** The account's Matrix access token (client.session().accessToken). */
export type AccessToken = () => Promise<string | undefined>;

/** Opening hours of a work account (ADR 0138 rule 4, spec work-hours; bipe-api /v1/me/work-hours, WorkHoursBody). */
export type WorkHours = {
  enabled: boolean;
  /** IANA time zone. */
  time_zone: string;
  /** ISO weekday (Monday 1 to Sunday 7) with its ranges; a missing day is closed. */
  days: { day: number; ranges: { start: string; end: string }[] }[];
};

/** HeldBrandMark: a mark this account holds. label is one id of the registry brandMarks.labels. */
export type HeldBrandMark = { id: string; brand: string; brand_name: string; label: string; granted_at: string };
/** GrantedBrandMark: an account that holds this brand's mark. */
export type GrantedBrandMark = { id: string; reseller: string; label: string; granted_at: string };

/** Bookings that start on these local dates (YYYY-MM-DD), every status but expired holds. */
export type BookingsQuery = { date_from: string; date_to: string; agenda_id?: string };

export class BusinessClient {
  constructor(private readonly accessToken: AccessToken) {}

  private async request<T>(method: string, path: string, body?: unknown, contentType = 'application/json'): Promise<T> {
    const token = await this.accessToken();
    if (!token) throw new WorkError('unauthorized', 401);
    let res: Response;
    try {
      res = await fetch(`${API_BASE}${path}`, {
        method,
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${token}`,
          ...(body === undefined ? {} : { 'Content-Type': contentType }),
        },
        body:
          body === undefined
            ? undefined
            : body instanceof Blob || body instanceof ArrayBuffer
              ? body
              : JSON.stringify(body),
      });
    } catch {
      throw new WorkError('network', 0);
    }
    if (res.status === 204) return undefined as T;
    // bipe-api errors are { error, reason? } (src/errors.ts ApiError.body()).
    const json = (await res.json().catch(() => undefined)) as { error?: string; reason?: string } | undefined;
    if (!res.ok) throw new WorkError(json?.error ?? 'internal', res.status, json?.reason);
    return json as T;
  }

  private get = <T>(path: string) => this.request<T>('GET', path);
  private post = <T>(path: string, body: unknown = {}) => this.request<T>('POST', path, body);
  private put = <T>(path: string, body: unknown) => this.request<T>('PUT', path, body);
  private del = (path: string) => this.request<void>('DELETE', path);

  // ---- Business profile ----

  workHours = () => this.get<WorkHours>('/v1/me/work-hours');
  setWorkHours = (hours: WorkHours) => this.put<WorkHours>('/v1/me/work-hours', hours);

  // ---- Catálogo (specs catalog-owner, catalog-item-edit) ----

  catalog = () => this.get<Catalog>('/v1/me/business/catalog');
  createItem = (item: CatalogItemInput) => this.post<CatalogItem>('/v1/me/business/catalog/items', item);
  replaceItem = (id: string, item: CatalogItemInput) =>
    this.put<CatalogItem>(`/v1/me/business/catalog/items/${enc(id)}`, item);
  archiveItem = (id: string) => this.del(`/v1/me/business/catalog/items/${enc(id)}`);
  reorderCatalog = (itemIds: string[]) => this.put<Catalog>('/v1/me/business/catalog/order', { item_ids: itemIds });
  /** The photo must be a JPEG; the screen converts other images first (same rule as the apps). */
  setItemPhoto = (id: string, jpeg: Blob) =>
    this.request<CatalogItem>('PUT', `/v1/me/business/catalog/items/${enc(id)}/photo`, jpeg, 'image/jpeg');
  deleteItemPhoto = (id: string) => this.del(`/v1/me/business/catalog/items/${enc(id)}/photo`);
  /** The owner's read of any item photo, hidden items too (the public photo URL serves active items only). */
  itemPhoto = async (id: string): Promise<Blob> => {
    const token = await this.accessToken();
    if (!token) throw new WorkError('unauthorized', 401);
    let res: Response;
    try {
      res = await fetch(`${API_BASE}/v1/me/business/catalog/items/${enc(id)}/photo`, {
        headers: { Accept: 'image/jpeg', Authorization: `Bearer ${token}` },
      });
    } catch {
      throw new WorkError('network', 0);
    }
    if (!res.ok) throw new WorkError('not_found', res.status);
    return res.blob();
  };

  // ---- Agenda (specs agenda-owner, agenda-setup, agenda-settings, agenda-owner-booking) ----

  agenda = () => this.get<AgendaState>('/v1/me/business/agenda');
  saveAgendaSettings = (settings: AgendaSettings) => this.put<AgendaState>('/v1/me/business/agenda', settings);
  activateAgenda = (activation: AgendaActivation) =>
    this.post<AgendaState>('/v1/me/business/agenda/activate', activation);
  agendas = () => this.get<{ agendas: Agenda[] }>('/v1/me/business/agenda/agendas');
  createAgenda = (agenda: AgendaInput) => this.post<Agenda>('/v1/me/business/agenda/agendas', agenda);
  replaceAgenda = (id: string, agenda: AgendaInput) =>
    this.put<Agenda>(`/v1/me/business/agenda/agendas/${enc(id)}`, agenda);
  deleteAgenda = (id: string) => this.del(`/v1/me/business/agenda/agendas/${enc(id)}`);
  availability = (agendaId: string) =>
    this.get<AvailabilityRules>(`/v1/me/business/agenda/agendas/${enc(agendaId)}/availability`);
  setAvailability = (agendaId: string, rules: Availability[]) =>
    this.put<AvailabilityRules>(`/v1/me/business/agenda/agendas/${enc(agendaId)}/availability`, { rules });
  overrides = (dateFrom?: string, dateTo?: string) =>
    this.get<{ overrides: Override[] }>(`/v1/me/business/agenda/overrides${qs({ date_from: dateFrom, date_to: dateTo })}`);
  createOverride = (o: OverrideInput) => this.post<Override>('/v1/me/business/agenda/overrides', o);
  replaceOverride = (id: string, o: OverrideInput) =>
    this.put<Override>(`/v1/me/business/agenda/overrides/${enc(id)}`, o);
  deleteOverride = (id: string) => this.del(`/v1/me/business/agenda/overrides/${enc(id)}`);
  block = (b: AgendaBlock) => this.post<Override>('/v1/me/business/agenda/blocks', b);
  closeDay = (c: AgendaCloseDay) => this.post<Override>('/v1/me/business/agenda/close-day', c);
  services = () => this.get<{ services: Service[] }>('/v1/me/business/agenda/services');
  createService = (s: ServiceInput) => this.post<Service>('/v1/me/business/agenda/services', s);
  replaceService = (id: string, s: ServiceInput) =>
    this.put<Service>(`/v1/me/business/agenda/services/${enc(id)}`, s);
  archiveService = (id: string) => this.del(`/v1/me/business/agenda/services/${enc(id)}`);
  agendaPolicy = () => this.get<Policy>('/v1/me/business/agenda/policy');
  setAgendaPolicy = (p: Policy) => this.put<Policy>('/v1/me/business/agenda/policy', p);
  bookings = (q: BookingsQuery) => this.get<{ bookings: Booking[] }>(`/v1/me/business/agenda/bookings${qs(q)}`);
  book = (b: OwnerBooking) => this.post<Booking>('/v1/me/business/agenda/bookings', b);
  moveBooking = (id: string, m: BookingMove) =>
    this.post<Booking>(`/v1/me/business/agenda/bookings/${enc(id)}/move`, m);
  bookingAction = (id: string, action: 'cancel' | 'no-show' | 'accept' | 'refuse') =>
    this.post<Booking>(`/v1/me/business/agenda/bookings/${enc(id)}/${action}`);

  // ---- Fila virtual (spec queue-owner) ----

  queue = () => this.get<Queue>('/v1/me/business/queue');
  setQueueOpen = (open: boolean) => this.post<Queue>(`/v1/me/business/queue/${open ? 'open' : 'close'}`);
  setQueuePolicy = (p: QueuePolicy) => this.put<Queue>('/v1/me/business/queue/policy', p);
  places = () => this.get<{ places: QueuePlace[] }>('/v1/me/business/queue/places');
  addPlace = (p: OwnerQueuePlace) => this.post<QueuePlace>('/v1/me/business/queue/places', p);
  callNext = () => this.post<QueuePlace>('/v1/me/business/queue/call-next');
  placeAction = (id: string, action: 'served' | 'no-show' | 'recall') =>
    this.post<QueuePlace>(`/v1/me/business/queue/places/${enc(id)}/${action}`);

  // ---- Orçamento (spec quote-builder) ----

  quoteRequests = () => this.get<{ requests: QuoteRequest[] }>('/v1/me/business/quotes/requests');
  answerRequest = (requestId: string, v: QuoteVersionInput) =>
    this.post<Quote>(`/v1/me/business/quotes/requests/${enc(requestId)}/quote`, v);
  quotes = () => this.get<{ quotes: Quote[] }>('/v1/me/business/quotes');
  quote = (id: string) => this.get<Quote>(`/v1/me/business/quotes/${enc(id)}`);
  reviseQuote = (id: string, v: QuoteVersionInput) => this.post<Quote>(`/v1/me/business/quotes/${enc(id)}/versions`, v);
  /** A quote for a person with no Bipe account (ADR 0185). The link shows only here. */
  createQuoteLink = (v: QuoteLinkInput) => this.post<QuoteLinkResult>('/v1/me/business/quotes/links', v);
  renewQuoteLink = (id: string) => this.post<QuoteLinkResult>(`/v1/me/business/quotes/${enc(id)}/link`);
  revokeQuoteLink = (id: string) => this.request<Quote>('DELETE', `/v1/me/business/quotes/${enc(id)}/link`);

  // ---- Pedidos (spec order-owner). No Pix charge on the web (ADR 0226 point 6). ----

  orders = () => this.get<{ orders: Order[] }>('/v1/me/business/orders');
  createOrder = (o: OwnerOrderInput) => this.post<Order>('/v1/me/business/orders', o);
  order = (id: string) => this.get<Order>(`/v1/me/business/orders/${enc(id)}`);
  orderPolicy = () => this.get<OrderPolicy>('/v1/me/business/orders/policy');
  setOrderPolicy = (p: OrderPolicy) => this.put<OrderPolicy>('/v1/me/business/orders/policy', p);
  orderAction = (id: string, action: 'accept' | 'ready' | 'done' | 'cancel') =>
    this.post<Order>(`/v1/me/business/orders/${enc(id)}/${action}`);
  refuseOrder = (id: string, reason: OrderRefuseReason) =>
    this.post<Order>(`/v1/me/business/orders/${enc(id)}/refuse`, { reason });

  // ---- Tool state, sector, steps, suggestions, Hoje (specs business-tools, work-setup, ADR 0194) ----

  businessTools = () => this.get<BusinessTools>('/v1/me/business/tools');
  setBusinessTool = (id: BusinessToolId, state: BusinessToolState) =>
    this.put<BusinessTools>(`/v1/me/business/tools/${enc(id)}`, { state });
  setWorkSector = (sector?: string) => this.put<BusinessTools>('/v1/me/business/sector', sector ? { sector } : {});
  workSetup = () => this.get<WorkSetup>('/v1/me/business/setup');
  seats = () => this.get<SeatList>('/v1/me/business/seats');
  workLinkOpened = () => this.post<WorkSetup>('/v1/me/business/setup/link-opened');
  workSuggestions = () => this.get<WorkSuggestions>('/v1/me/business/suggestions');
  dismissWorkSuggestion = (id: string) => this.post<WorkSuggestions>(`/v1/me/business/suggestions/${enc(id)}/dismiss`);
  businessToday = () => this.get<BusinessToday>('/v1/me/business/today');
  liveTools = (business: string) => this.get<BusinessLiveTools>(`/v1/businesses/${enc(business)}/tools`);

  // ---- External calendars of an agenda (ADR 0101) ----

  issueAgendaFeed = (agendaId: string, includeFirstName: boolean) =>
    this.post<AgendaFeed>(`/v1/me/business/agenda/agendas/${enc(agendaId)}/feed`, { include_customer_first_name: includeFirstName });
  busyImports = (agendaId: string) => this.get<{ imports: BusyImport[] }>(`/v1/me/business/agenda/agendas/${enc(agendaId)}/busy-imports`);
  addBusyImport = (agendaId: string, url: string) =>
    this.post<BusyImport>(`/v1/me/business/agenda/agendas/${enc(agendaId)}/busy-imports`, { url });
  removeBusyImport = (agendaId: string, importId: string) =>
    this.del(`/v1/me/business/agenda/agendas/${enc(agendaId)}/busy-imports/${enc(importId)}`);

  // ---- The owner's web switches (ADR 0195) ----

  webSettings = () => this.get<WebSettings>('/v1/me/business/web-settings');
  setWebSettings = (settings: WebSettings) => this.put<WebSettings>('/v1/me/business/web-settings', settings);

  // ---- Selos de revenda, read only on the web (spec brand-marks, ADR 0226 point 2) ----

  heldBrandMarks = () => this.get<{ marks: HeldBrandMark[] }>('/v1/me/brand-marks');
  grantedBrandMarks = () => this.get<{ marks: GrantedBrandMark[] }>('/v1/me/brand-marks/granted');

  // ---- The caller's records with every business: a person's own, or a company's as the business (ADR 0102) ----

  myBookings = () => this.get<{ bookings: Booking[] }>('/v1/me/bookings');
  myOrders = () => this.get<{ orders: Order[] }>('/v1/me/orders');
  myQuotes = () => this.get<{ quotes: Quote[] }>('/v1/me/quotes');
  myQueuePlaces = () => this.get<{ places: QueuePosition[] }>('/v1/me/queue-places');

  // ---- Customer routes (specs agenda-book, catalog-view, order-customer, queue-customer, quote-card). ----
  // business is the handle or, without one, the Matrix ID.

  publicCatalog = (business: string) => this.get<Catalog>(`/v1/businesses/${enc(business)}/catalog`);
  publicServices = (business: string) =>
    this.get<{ services: Service[] }>(`/v1/businesses/${enc(business)}/agenda/services`);
  publicAgendas = (business: string, serviceId?: string) =>
    this.get<{ agendas: Agenda[] }>(`/v1/businesses/${enc(business)}/agenda/agendas${qs({ service_id: serviceId })}`);
  slots = (business: string, q: { service_id: string; agenda_id?: string; date_from: string; date_to: string }) =>
    this.get<SlotDays>(`/v1/businesses/${enc(business)}/agenda/slots${qs(q)}`);
  hold = (business: string, h: BookingHold) => this.post<Booking>(`/v1/businesses/${enc(business)}/agenda/hold`, h);
  confirmBooking = (business: string, id: string) =>
    this.post<Booking>(`/v1/businesses/${enc(business)}/agenda/bookings/${enc(id)}/confirm`);
  cancelBooking = (business: string, id: string) =>
    this.post<Booking>(`/v1/businesses/${enc(business)}/agenda/bookings/${enc(id)}/cancel`);
  rescheduleBooking = (business: string, id: string, m: BookingMove) =>
    this.post<Booking>(`/v1/businesses/${enc(business)}/agenda/bookings/${enc(id)}/move`, m);
  placeOrder = (business: string, o: OrderInput) => this.post<Order>(`/v1/businesses/${enc(business)}/orders`, o);
  cancelMyOrder = (business: string, id: string) =>
    this.post<Order>(`/v1/businesses/${enc(business)}/orders/${enc(id)}/cancel`);
  publicQueue = (business: string) => this.get<Queue>(`/v1/businesses/${enc(business)}/queue`);
  myPlace = (business: string) => this.get<QueuePosition>(`/v1/businesses/${enc(business)}/queue/me`);
  joinQueue = (business: string) => this.post<QueuePosition>(`/v1/businesses/${enc(business)}/queue/join`);
  leaveQueue = (business: string, placeId: string) =>
    this.post<QueuePlace>(`/v1/businesses/${enc(business)}/queue/places/${enc(placeId)}/leave`);
  requestQuote = (business: string) => this.post<QuoteRequest>(`/v1/businesses/${enc(business)}/quotes/requests`);
  acceptQuote = (business: string, id: string, a: QuoteAccept) =>
    this.post<Quote>(`/v1/businesses/${enc(business)}/quotes/${enc(id)}/accept`, a);
  refuseQuote = (business: string, id: string, version: number) =>
    this.post<Quote>(`/v1/businesses/${enc(business)}/quotes/${enc(id)}/refuse`, { version });
}

const clients = new WeakMap<Client, BusinessClient>();

/** The business client of one account, made once per SDK client. The token is read at each call: it rotates. */
export function businessClientFor(client: Client): BusinessClient {
  let business = clients.get(client);
  if (!business) {
    business = new BusinessClient(async () => {
      try {
        return client.session().accessToken;
      } catch {
        return undefined;
      }
    });
    clients.set(client, business);
  }
  return business;
}

const enc = encodeURIComponent;

function qs(params: Record<string, string | undefined>): string {
  const pairs = Object.entries(params).filter((e): e is [string, string] => e[1] !== undefined && e[1] !== '');
  return pairs.length === 0 ? '' : `?${new URLSearchParams(pairs).toString()}`;
}

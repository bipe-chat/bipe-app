// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec business-chat-bar on the web (BusinessChatBar.swift): the tools of a company above the timeline and on its
// profile, the cards of the open records of this DM, and the flows they open over the chat. The web shows the tools
// as a row of buttons where the phone has the header menu. No Pix on the web (ADR 0226 point 6).

import { useEffect, useState, type ReactNode } from 'react';
import type { Booking, Order, QueuePosition, Quote } from '../../bipe/generated/business-api';
import { Alert } from '../../ui/Alert';
import { NavBar } from '../../ui/List';
import { locale, s } from '../../ui/i18n';
import { BookingPanel, STATUS, STATUS_TINT } from '../Agenda';
import { WorkError } from '../business-client';
import {
  LoadState,
  PrimaryButton,
  SecondaryButton,
  TextButton,
  money,
  setNameLookup,
  useLoad,
} from '../common';
import { OrdersScreen, STATUS_TEXT as ORDER_STATUS, STATUS_TINT as ORDER_TINT, fulfilmentText, orderErrorText, refuseText } from '../Orders';
import { QueueScreen, queueErrorText } from '../Queue';
import { QuoteCard, QuotesScreen, currentVersion, versionCard } from '../Quotes';
import { AgendaBook, agendaErrorText } from './AgendaBook';
import { CatalogView } from './CatalogView';
import { OrderCustomer } from './OrderCustomer';
import { QueueCustomer, QueuePositionSummary } from './QueueCustomer';
import type { BusinessChat } from './useBusinessChat';

const NOTICE_MS = 5000;
const dateTime = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' });

/** BipeAPIError.quoteMessage on iOS. */
function quoteErrorText(e: unknown): string {
  if (!(e instanceof WorkError)) return s('bipe_quote_error');
  switch (e.code) {
    case 'network':
      return s('bipe_error_network');
    case 'quote_expired':
      return s('bipe_quote_error_expired');
    case 'quote_version_changed':
      return s('bipe_quote_error_version');
    case 'slot_taken':
    case 'hold_expired':
    case 'too_many_open_bookings':
    case 'outside_notice':
    case 'outside_horizon':
    case 'agenda_not_published':
    case 'cancellation_window_closed':
      return agendaErrorText(e);
    case 'order_already_open':
    case 'orders_closed':
    case 'item_unavailable':
    case 'price_not_fixed':
      return orderErrorText(e);
    default:
      return s('bipe_quote_error');
  }
}

/** One button per live tool (ADR 0194 point 2). */
export function BusinessToolButtons({ chat, onPick }: { chat: BusinessChat; onPick?: () => void }) {
  const [asking, setAsking] = useState(false);
  if (!chat.isCompany) return null;
  const go = (run: () => void) => () => {
    onPick?.();
    run();
  };
  const chip = (key: string, title: string, run: () => void) => (
    <button
      key={key}
      type="button"
      className="min-h-9 shrink-0 rounded-full bg-surface-sunken px-3.5 text-subheadline font-semibold text-ink-primary"
      onClick={go(run)}
    >
      {title}
    </button>
  );
  return (
    <div role="group" aria-label={s('bipe_business_tools_a11y')} className="flex flex-wrap gap-2 px-3 py-2">
      {chat.canBook && chip('book', s('bipe_agenda_book'), () => chat.setRoute({ kind: 'book' }))}
      {chat.hasCatalog && chip('catalog', s('bipe_catalog_open'), () => chat.setRoute({ kind: 'catalog' }))}
      {chat.canOrder && chip('order', s('bipe_order_make'), () => chat.setRoute({ kind: 'order' }))}
      {chat.canRequestQuote && chip('quote', s('bipe_quote_request'), () => setAsking(true))}
      {chat.queueOpen && chip('queue', s('bipe_queue_join'), () => chat.setRoute({ kind: 'queue' }))}
      {asking && (
        <Alert
          title={s('bipe_quote_request')}
          action={s('bipe_quote_request')}
          destructive={false}
          onCancel={() => setAsking(false)}
          onAction={() => {
            setAsking(false);
            chat.api.requestQuote(chat.business).then(
              () => chat.setNotice(s('bipe_quote_requested')),
              (e: unknown) => chat.setNotice(quoteErrorText(e)),
            );
          }}
        >
          {s('bipe_quote_request_body')}
        </Alert>
      )}
    </div>
  );
}

/** Above the timeline: the tools, then the cards (bookings, the order, quotes and the queue place). */
export function BusinessChatBar({ chat }: { chat: BusinessChat }) {
  const { notice, setNotice } = chat;
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(undefined), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice, setNotice]);
  const hasCards = chat.bookings.length + chat.orders.length + chat.quotes.length + chat.queuePlaces.length > 0;
  if (!chat.isCompany && !hasCards && !notice) return null;
  return (
    <div className="flex max-h-[45%] shrink-0 flex-col overflow-y-auto border-b-[0.5px] border-line-subtle bg-surface-raised">
      <BusinessToolButtons chat={chat} />
      {notice && (
        <p role="status" className="px-4 pb-2 text-footnote text-ink-secondary">
          {notice}
        </p>
      )}
      {hasCards && (
        <div className="flex flex-col gap-2 px-3 pb-2">
          {chat.queuePlaces.map((p) => (
            <QueueCard key={p.place.id} chat={chat} position={p} />
          ))}
          {chat.orders.map((o) => (
            <OrderCard key={o.id} chat={chat} order={o} />
          ))}
          {chat.quotes.map((q) => (
            <QuoteChatCard key={q.id} chat={chat} quote={q} />
          ))}
          {chat.bookings.map((b) => (
            <BookingCard key={b.id} chat={chat} booking={b} />
          ))}
        </div>
      )}
    </div>
  );
}

function Card({ onOpen, children }: { onOpen?: () => void; children: ReactNode }) {
  const className = 'flex w-full max-w-[460px] flex-col gap-1 rounded-2xl bg-bubble-other p-3 text-left text-body';
  return onOpen ? (
    <button type="button" className={className} onClick={onOpen}>
      {children}
    </button>
  ) : (
    <div className={className}>{children}</div>
  );
}

const CardTitle = ({ children }: { children: string }) => (
  <span className="text-caption font-bold text-accent-ink">{children}</span>
);

/** A customer action on a card that asks first and shows its failure in the notice line. */
function useCardAction(chat: BusinessChat, errorText: (e: unknown) => string) {
  const [busy, setBusy] = useState(false);
  return {
    busy,
    run: (call: () => Promise<unknown>) => {
      setBusy(true);
      call()
        .catch((e: unknown) => chat.setNotice(errorText(e)))
        .finally(() => {
          setBusy(false);
          void chat.refresh();
        });
    },
  };
}

// Spec agenda-booking-card.
function BookingCard({ chat, booking }: { chat: BusinessChat; booking: Booking }) {
  const [confirming, setConfirming] = useState(false);
  const action = useCardAction(chat, agendaErrorText);
  const open = ['held', 'requested', 'confirmed'].includes(booking.status);
  const canCancel = open && Date.parse(booking.end) > Date.now();
  const service = chat.serviceNames[booking.service_id];
  const agenda = chat.agendaNames[booking.agenda_id];
  return (
    <Card onOpen={chat.ownerMode ? () => chat.setRoute({ kind: 'ownerBooking', booking }) : undefined}>
      <CardTitle>{s('bipe_agenda_card_title')}</CardTitle>
      {service && <span className="text-headline text-ink-primary">{service}</span>}
      <span className="text-subheadline text-ink-primary">{dateTime.format(Date.parse(booking.start))}</span>
      {agenda && <span className="text-subheadline text-ink-secondary">{s('bipe_agenda_with', agenda)}</span>}
      {chat.ownerMode && <span className="text-subheadline text-ink-secondary">{chat.peerName}</span>}
      <span className={`text-footnote font-semibold ${STATUS_TINT[booking.status]}`}>{s(STATUS[booking.status])}</span>
      {!chat.ownerMode && (
        <div className="flex flex-wrap gap-x-1">
          <TextButton onClick={() => downloadCalendar(booking, service ?? '', chat.peerName)}>
            {s('bipe_agenda_add_to_calendar')}
          </TextButton>
          {open && (
            <TextButton onClick={() => chat.setRoute({ kind: 'reschedule', booking })}>
              {s('bipe_agenda_reschedule')}
            </TextButton>
          )}
          {canCancel && (
            <TextButton danger disabled={action.busy} onClick={() => setConfirming(true)}>
              {s('bipe_agenda_cancel')}
            </TextButton>
          )}
        </div>
      )}
      {confirming && (
        <Alert
          title={s('bipe_agenda_cancel_confirm')}
          action={s('bipe_agenda_cancel')}
          onCancel={() => setConfirming(false)}
          onAction={() => {
            setConfirming(false);
            action.run(() => chat.api.cancelBooking(chat.business, booking.id));
          }}
        >
          {s('bipe_agenda_cancel_confirm_body')}
        </Alert>
      )}
    </Card>
  );
}

// Spec order-customer: the order card.
function OrderCard({ chat, order }: { chat: BusinessChat; order: Order }) {
  const [confirming, setConfirming] = useState(false);
  const action = useCardAction(chat, orderErrorText);
  const open = ['sent', 'accepted', 'ready'].includes(order.status);
  return (
    <Card onOpen={chat.ownerMode ? () => chat.setRoute({ kind: 'ownerOrder', order }) : undefined}>
      <CardTitle>{s('bipe_order_card_title')}</CardTitle>
      {chat.ownerMode && <span className="text-headline text-ink-primary">{chat.peerName}</span>}
      {order.lines.map((line, i) => (
        <span key={i} className="text-ink-primary">
          {s('bipe_order_line', String(line.quantity), line.name)}
        </span>
      ))}
      <span className="font-bold text-ink-primary">
        {s('bipe_order_total')}: {money(order.total_amount_cents)}
      </span>
      <span className="text-subheadline text-ink-secondary">
        {fulfilmentText(order.fulfilment)} · {s('bipe_order_pay_on_site')}
      </span>
      <span className={`text-footnote font-semibold ${ORDER_TINT[order.status]}`}>{s(ORDER_STATUS[order.status])}</span>
      {order.refuse_reason && (
        <span className="text-footnote text-ink-secondary">
          {s('bipe_order_refuse_reason_label', refuseText(order.refuse_reason))}
        </span>
      )}
      {!chat.ownerMode && open && (
        <div>
          <TextButton danger disabled={action.busy} onClick={() => setConfirming(true)}>
            {s('bipe_order_cancel')}
          </TextButton>
        </div>
      )}
      {confirming && (
        <Alert
          title={s('bipe_order_cancel_confirm')}
          action={s('bipe_order_cancel')}
          onCancel={() => setConfirming(false)}
          onAction={() => {
            setConfirming(false);
            action.run(() => chat.api.cancelMyOrder(chat.business, order.id));
          }}
        />
      )}
    </Card>
  );
}

// Spec quote-card.
function QuoteChatCard({ chat, quote }: { chat: BusinessChat; quote: Quote }) {
  const [accepting, setAccepting] = useState(false);
  const action = useCardAction(chat, quoteErrorText);
  const current = currentVersion(quote);
  if (!current) return null;
  const visit = current.visit_service_id;
  const card = versionCard(current, []);
  if (visit) card.visitName = chat.serviceNames[visit] ?? '';
  return (
    <div className="flex flex-col gap-1">
      <QuoteCard card={card} status={quote.status} />
      <div className="flex flex-wrap gap-x-1">
        {chat.ownerMode ? (
          quote.status === 'offered' && (
            <TextButton onClick={() => chat.setRoute({ kind: 'ownerQuote', quote })}>
              {s('bipe_quote_send_revision')}
            </TextButton>
          )
        ) : (
          quote.status === 'offered' && (
            <>
              <TextButton disabled={action.busy} onClick={() => setAccepting(true)}>
                {s('bipe_quote_accept_order')}
              </TextButton>
              {visit && (
                <TextButton disabled={action.busy} onClick={() => chat.setRoute({ kind: 'quoteBooking', quote })}>
                  {s('bipe_quote_accept_booking')}
                </TextButton>
              )}
              <TextButton
                danger
                disabled={action.busy}
                onClick={() => action.run(() => chat.api.refuseQuote(chat.business, quote.id, quote.current_version))}
              >
                {s('bipe_quote_decline')}
              </TextButton>
            </>
          )
        )}
        {quote.versions.length > 1 && (
          <TextButton onClick={() => chat.setRoute({ kind: 'quoteVersions', quote })}>
            {s('bipe_quote_previous_versions')}
          </TextButton>
        )}
      </div>
      {accepting && (
        <FulfilmentChoice
          onClose={() => setAccepting(false)}
          onPick={(fulfilment) => {
            setAccepting(false);
            action.run(async () => {
              await chat.api.acceptQuote(chat.business, quote.id, {
                version: quote.current_version,
                target: 'order',
                fulfilment,
                payment: 'in_person',
              });
              chat.setNotice(s('bipe_order_sent'));
            });
          }}
        />
      )}
    </div>
  );
}

/** quote-card acceptOrder: pickup or delivery, paid in person. */
function FulfilmentChoice({
  onPick,
  onClose,
}: {
  onPick: (fulfilment: 'pickup' | 'delivery') => void;
  onClose: () => void;
}) {
  return (
    // The dim behind an alert is black at 40% in both schemes, as Alert.tsx draws it.
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-6" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={s('bipe_quote_accept')}
        className="flex w-full max-w-[320px] flex-col gap-3 rounded-[14px] bg-surface-raised p-4 text-center"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-headline text-ink-primary">{s('bipe_quote_accept')}</h3>
        <p className="text-footnote text-ink-primary">
          {s('bipe_order_fulfilment')} {s('bipe_order_pay_on_site')}
        </p>
        <PrimaryButton onClick={() => onPick('pickup')}>{s('bipe_order_pickup')}</PrimaryButton>
        <PrimaryButton onClick={() => onPick('delivery')}>{s('bipe_order_delivery')}</PrimaryButton>
        <SecondaryButton onClick={onClose}>{s('bipe_app_cancel')}</SecondaryButton>
      </div>
    </div>
  );
}

// Spec queue-customer: the card of the active place.
function QueueCard({ chat, position }: { chat: BusinessChat; position: QueuePosition }) {
  const [confirming, setConfirming] = useState(false);
  const action = useCardAction(chat, queueErrorText);
  return (
    <Card onOpen={chat.ownerMode ? () => chat.setRoute({ kind: 'ownerQueue' }) : undefined}>
      <CardTitle>{s('bipe_queue_title')}</CardTitle>
      {chat.ownerMode && <span className="text-headline text-ink-primary">{chat.peerName}</span>}
      <QueuePositionSummary position={position} />
      {!chat.ownerMode && (
        <div>
          <TextButton danger disabled={action.busy} onClick={() => setConfirming(true)}>
            {s('bipe_queue_leave')}
          </TextButton>
        </div>
      )}
      {confirming && (
        <Alert
          title={s('bipe_queue_leave_confirm')}
          action={s('bipe_queue_leave')}
          onCancel={() => setConfirming(false)}
          onAction={() => {
            setConfirming(false);
            action.run(() => chat.api.leaveQueue(chat.business, position.place.id));
          }}
        />
      )}
    </Card>
  );
}

/** agenda-booking-card addToCalendar: a local .ics file. Nothing goes to the server (BipeBookingCalendar on iOS). */
function downloadCalendar(booking: Booking, service: string, business: string) {
  const utc = (iso: string) => new Date(iso).toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
  const escape = (text: string) =>
    text.replace(/\\/g, '\\\\').replace(/;/g, '\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
  const ics = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Bipe//Agenda//PT',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'BEGIN:VEVENT',
    `UID:${escape(booking.id)}@bipe.chat`,
    `DTSTAMP:${utc(new Date().toISOString())}`,
    `DTSTART:${utc(booking.start)}`,
    `DTEND:${utc(booking.end)}`,
    `SUMMARY:${escape(s('bipe_agenda_calendar_title', service, business))}`,
    'END:VEVENT',
    'END:VCALENDAR',
    '',
  ].join('\r\n');
  const url = URL.createObjectURL(new Blob([ics], { type: 'text/calendar' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'bipe-agenda.ics';
  link.click();
  URL.revokeObjectURL(url);
}

/** The flows over the chat: one full-pane screen for the open route. */
export function BusinessChatSheets({ chat }: { chat: BusinessChat }) {
  const { route, setRoute, finished, api, business, matrix } = chat;
  if (!route) return null;
  const close = () => finished();
  let screen: ReactNode;
  switch (route.kind) {
    case 'book':
      screen = <AgendaBook api={api} business={business} serviceId={route.serviceId} onClose={finished} />;
      break;
    case 'reschedule':
      screen = (
        <AgendaBook
          api={api}
          business={business}
          serviceId={route.booking.service_id}
          moving={route.booking}
          onClose={finished}
        />
      );
      break;
    case 'quoteBooking': {
      const { quote } = route;
      screen = (
        <AgendaBook
          api={api}
          business={business}
          serviceId={currentVersion(quote)?.visit_service_id}
          acceptQuote={(hold) =>
            api.acceptQuote(business, quote.id, { version: quote.current_version, target: 'booking', booking: hold })
          }
          onClose={finished}
        />
      );
      break;
    }
    case 'catalog':
      screen = (
        <CatalogView
          api={api}
          business={business}
          canBook={chat.canBook}
          canOrder={chat.canOrder}
          onBook={(serviceId) => setRoute({ kind: 'book', serviceId })}
          onOrder={(itemId) => setRoute({ kind: 'order', itemId })}
          onClose={close}
        />
      );
      break;
    case 'order':
      screen = <OrderCustomer api={api} business={business} itemId={route.itemId} onClose={finished} />;
      break;
    case 'queue':
      screen = <QueueCustomer api={api} business={business} onClose={finished} />;
      break;
    case 'quoteVersions':
      screen = <QuoteVersions quote={route.quote} onClose={() => setRoute(undefined)} />;
      break;
    default:
      // The owner screens need the customer's name from the Matrix profile, as Trabalho does.
      setNameLookup((userId) => matrix.getProfile(userId).then((p) => p.displayName ?? undefined));
      screen =
        route.kind === 'ownerOrder' ? (
          <OrdersScreen client={api} onBack={close} openOrderId={route.order.id} />
        ) : route.kind === 'ownerQuote' ? (
          <QuotesScreen client={api} onBack={close} openQuoteId={route.quote.id} />
        ) : route.kind === 'ownerQueue' ? (
          <QueueScreen client={api} onBack={close} />
        ) : (
          <OwnerBooking chat={chat} booking={route.booking} onClose={close} />
        );
  }
  return <div className="absolute inset-0 z-40 flex min-h-0 flex-col bg-grouped-screen">{screen}</div>;
}

/** agenda-owner-booking from the DM: the same panel as the Agenda screen. */
function OwnerBooking({ chat, booking, onClose }: { chat: BusinessChat; booking: Booking; onClose: () => void }) {
  const state = useLoad(() => chat.api.agenda(), [chat.api]);
  const [current, setCurrent] = useState(booking);
  if (!state.data)
    return (
      <>
        <NavBar title={s('bipe_agenda_card_title')} onBack={onClose} />
        <LoadState loading={state.loading} error={state.error} onRetry={() => void state.reload()} />
      </>
    );
  return (
    <BookingPanel client={chat.api} state={state.data} booking={current} onClose={onClose} onChanged={setCurrent} />
  );
}

/** quote-card showVersion: the older versions, read only. */
function QuoteVersions({ quote, onClose }: { quote: Quote; onClose: () => void }) {
  return (
    <>
      <NavBar title={s('bipe_quote_previous_versions')} onBack={onClose} backLabel={s('bipe_app_close')} />
      <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4">
        {[...quote.versions]
          .filter((v) => v.version !== quote.current_version)
          .sort((a, b) => b.version - a.version)
          .map((v) => (
            <QuoteCard key={v.version} card={versionCard(v, [])} />
          ))}
      </div>
    </>
  );
}


// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Pedidos for the owner on the web (ADR 0226, bipe-chat/bipe#982): spec order-owner, OrderOwnerView on iOS.
// The list of orders, open ones first and newest first, next to the open order with only the actions that
// bipe-api allows in its status (orders.ts TRANSITIONS): Aceitar and Recusar on sent, Pronto on accepted,
// Entregue on ready, Cancelar on every open order. Also the owner's new order from the catálogo and the order
// days and cut-off. No Pix charge here (ADR 0226 point 6): a Pix order says the charge is in the phone app.

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type {
  BookingCustomer,
  CatalogItem,
  Order,
  OrderPolicy,
  OrderRefuseReason,
  OrderStatus,
} from '../bipe/generated/business-api';
import type { StringKey } from '../bipe/generated/strings';
import { gear, plus } from '../phosphor/icons';
import { dayText, locale, s, timeText } from '../ui/i18n';
import { Icon } from '../ui/Icon';
import { NavBar, Row, Section } from '../ui/List';
import { WorkError, type BusinessClient } from './business-client';
import {
  customerName,
  Field,
  inputClass,
  LoadState,
  money,
  OpenChatButton,
  PrimaryButton,
  SecondaryButton,
  SplitPane,
  TextButton,
  useAction,
  useLoad,
  useNames,
  type WorkScreenProps,
} from './common';

/** New orders show without a reload: the list reads again this often while the screen is open. */
const REFRESH_MS = 30_000;
/** The most of one item in an order (bipe-api MAX_QUANTITY, OrderCart.maxQuantity on iOS). */
const MAX_QUANTITY = 999;

const STATUS_TEXT: Record<OrderStatus, StringKey> = {
  sent: 'bipe_order_status_sent',
  accepted: 'bipe_order_status_accepted',
  refused: 'bipe_order_status_refused',
  ready: 'bipe_order_status_ready',
  done: 'bipe_order_status_done',
  cancelled: 'bipe_order_status_cancelled',
};

/** The status colours of BipeOrderStatus.tint on iOS. */
const STATUS_TINT: Record<OrderStatus, string> = {
  sent: 'text-trust-caution-ink',
  accepted: 'text-trust-verified-ink',
  ready: 'text-trust-verified-ink',
  done: 'text-trust-verified-ink',
  refused: 'text-ink-secondary',
  cancelled: 'text-ink-secondary',
};

const REFUSE_REASONS: { reason: OrderRefuseReason; text: StringKey }[] = [
  { reason: 'unavailable', text: 'bipe_order_refuse_unavailable' },
  { reason: 'closed', text: 'bipe_order_refuse_closed' },
  { reason: 'cannot_deliver', text: 'bipe_order_refuse_cannot_deliver' },
  { reason: 'cannot_fulfil', text: 'bipe_order_refuse_cannot_fulfil' },
];

const OPEN: OrderStatus[] = ['sent', 'accepted', 'ready'];

/** The list sections: each open status on its own, new orders first, then the closed ones together. */
const GROUPS: { statuses: OrderStatus[]; header: () => string }[] = [
  { statuses: ['sent'], header: () => s('bipe_order_status_sent') },
  { statuses: ['accepted'], header: () => s('bipe_order_status_accepted') },
  { statuses: ['ready'], header: () => s('bipe_order_status_ready') },
  { statuses: ['done', 'refused', 'cancelled'], header: () => s('bipe_web_orders_closed') },
];

/** The right column: an order, the new order, or the order settings. */
type Pane = { kind: 'order'; id: string } | { kind: 'new' } | { kind: 'settings' };

export function OrdersScreen({ client, onBack }: WorkScreenProps) {
  useNames();
  const orders = useLoad(() => client.orders(), [client]);
  const [pane, setPane] = useState<Pane>();
  const { reload, setData } = orders;

  // Read the list again every 30 s while the screen is open, so new orders show. A hidden tab skips a turn.
  useEffect(() => {
    const id = setInterval(() => {
      if (!document.hidden) void reload();
    }, REFRESH_MS);
    return () => clearInterval(id);
  }, [reload]);

  /** Puts a changed or new order in the list without a new read. */
  const upsert = (order: Order) =>
    setData((d) => {
      const rest = (d?.orders ?? []).filter((o) => o.id !== order.id);
      return { orders: [order, ...rest] };
    });

  const all = orders.data?.orders;
  const groups = useMemo(() => {
    const newestFirst = [...(all ?? [])].sort((a, b) => (a.created_at < b.created_at ? 1 : a.created_at > b.created_at ? -1 : 0));
    return GROUPS.map((g) => ({ header: g.header(), orders: newestFirst.filter((o) => g.statuses.includes(o.status)) })).filter(
      (g) => g.orders.length > 0,
    );
  }, [all]);

  const opened = pane?.kind === 'order' ? all?.find((o) => o.id === pane.id) : undefined;
  const close = () => setPane(undefined);

  let detail: ReactNode;
  if (opened) {
    detail = <OrderDetail key={opened.id} client={client} order={opened} onChange={upsert} onClose={close} />;
  } else if (pane?.kind === 'new') {
    detail = (
      <NewOrder
        client={client}
        onClose={close}
        onCreated={(order) => {
          upsert(order);
          setPane({ kind: 'order', id: order.id });
        }}
      />
    );
  } else if (pane?.kind === 'settings') {
    detail = <OrderSettings client={client} onClose={close} />;
  }

  const list = !all ? (
    <LoadState loading={orders.loading} error={orders.error} onRetry={orders.reload} />
  ) : (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <div className="flex flex-wrap items-center gap-2">
        <SecondaryButton onClick={() => setPane({ kind: 'new' })}>
          <span className="flex items-center gap-1.5">
            <Icon path={plus} size={16} />
            {s('bipe_order_new')}
          </span>
        </SecondaryButton>
        <TextButton onClick={() => setPane({ kind: 'settings' })}>
          <span className="flex items-center gap-1.5">
            <Icon path={gear} size={18} />
            {s('bipe_order_settings')}
          </span>
        </TextButton>
      </div>
      {orders.error !== undefined && <p className="text-footnote text-trust-danger-ink">{s('bipe_order_load_failed')}</p>}
      {groups.length === 0 ? (
        <p className="py-8 text-center text-body text-ink-secondary">{s('bipe_order_owner_empty')}</p>
      ) : (
        groups.map((g) => (
          <Section key={g.header} header={g.header}>
            {g.orders.map((o) => (
              <OrderRow
                key={o.id}
                order={o}
                selected={pane?.kind === 'order' && pane.id === o.id}
                onOpen={() => setPane({ kind: 'order', id: o.id })}
              />
            ))}
          </Section>
        ))
      )}
    </div>
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_orders_title')} onBack={onBack} />
      <SplitPane list={list} detail={detail} />
    </div>
  );
}

/** One list row: the customer and the total, the time and the fulfilment, the status. */
function OrderRow({ order, selected, onOpen }: { order: Order; selected: boolean; onOpen: () => void }) {
  return (
    <Row>
      <button
        type="button"
        aria-current={selected ? 'true' : undefined}
        className={`-my-1.5 flex min-w-0 flex-1 flex-col gap-0.5 rounded-lg py-2 text-left ${selected ? 'bg-surface-sunken' : ''}`}
        onClick={onOpen}
      >
        <span className="flex items-baseline gap-2">
          <span className="min-w-0 flex-1 truncate text-headline text-ink-primary">{customerText(order.customer)}</span>
          <span className="text-body tabular-nums text-ink-primary">{money(order.total_amount_cents)}</span>
        </span>
        <span className="truncate text-subheadline text-ink-secondary">
          {whenText(order.created_at)} · {fulfilmentText(order.fulfilment)}
        </span>
        <StatusLine status={order.status} />
      </button>
    </Row>
  );
}

/**
 * One order on the company side (OrderOwnerDetailView): the summary, then Aceitar and Recusar on sent, Pronto on
 * accepted, Entregue on ready, and Cancelar while the order is open. Recusar asks the reason first; Cancelar asks
 * to confirm, as the phone's alert does.
 */
function OrderDetail({
  client,
  order,
  onChange,
  onClose,
}: {
  client: BusinessClient;
  order: Order;
  onChange: (o: Order) => void;
  onClose: () => void;
}) {
  const action = useAction();
  const [refusing, setRefusing] = useState(false);
  const [confirmsCancel, setConfirmsCancel] = useState(false);

  const act = async (fn: () => Promise<Order>) => {
    const updated = await action.run(fn);
    if (updated) {
      onChange(updated);
      setRefusing(false);
      setConfirmsCancel(false);
    }
  };

  const open = OPEN.includes(order.status);

  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <DetailHeader title={s('bipe_order_card_title')} onClose={onClose} />
      <Section header={customerText(order.customer)}>
        <li className="flex flex-col gap-1.5 px-4 py-3">
          <span className="text-subheadline text-ink-secondary">{whenText(order.created_at)}</span>
          {order.lines.map((line, i) => (
            <span key={`${line.item_id ?? line.name}-${i}`} className="flex items-baseline gap-2">
              <span className="min-w-0 flex-1 text-body text-ink-primary">
                {s('bipe_order_line', String(line.quantity), line.name)}
              </span>
              <span className="text-subheadline tabular-nums text-ink-secondary">{money(line.total_amount_cents)}</span>
            </span>
          ))}
          <span className="flex items-baseline gap-2 text-body font-semibold text-ink-primary">
            <span className="flex-1">{s('bipe_order_total')}</span>
            <span className="tabular-nums">{money(order.total_amount_cents)}</span>
          </span>
          <span className="text-subheadline text-ink-secondary">
            {fulfilmentText(order.fulfilment)} ·{' '}
            {order.payment === 'pix' ? s('bipe_order_pay_pix') : s('bipe_order_pay_on_site')}
          </span>
          {order.payment === 'pix' && <span className="text-footnote text-ink-secondary">{s('bipe_web_work_in_app')}</span>}
          <StatusLine status={order.status} />
          {order.status === 'refused' && order.refuse_reason && (
            <span className="text-subheadline text-ink-secondary">
              {s('bipe_order_refuse_reason_label', refuseText(order.refuse_reason))}
            </span>
          )}
        </li>
      </Section>
      <div className="-mt-4">
        <OpenChatButton customer={order.customer} />
      </div>

      {refusing ? (
        <Section header={s('bipe_order_refuse_title')}>
          {REFUSE_REASONS.map(({ reason, text }) => (
            <Row key={reason}>
              <button
                type="button"
                className="min-w-0 flex-1 py-1 text-left text-body text-trust-danger-ink disabled:opacity-50"
                disabled={action.busy}
                onClick={() => void act(() => client.refuseOrder(order.id, reason))}
              >
                {s(text)}
              </button>
            </Row>
          ))}
          <Row>
            <button
              type="button"
              className="min-w-0 flex-1 py-1 text-left text-body text-accent-ink disabled:opacity-50"
              disabled={action.busy}
              onClick={() => setRefusing(false)}
            >
              {s('bipe_app_cancel')}
            </button>
          </Row>
        </Section>
      ) : confirmsCancel ? (
        <div className="flex flex-col gap-3">
          <p className="text-body text-ink-primary">{s('bipe_order_cancel_confirm')}</p>
          <div className="flex flex-wrap gap-2">
            <TextButton danger disabled={action.busy} onClick={() => void act(() => client.orderAction(order.id, 'cancel'))}>
              {s('bipe_order_cancel')}
            </TextButton>
            <TextButton disabled={action.busy} onClick={() => setConfirmsCancel(false)}>
              {s('bipe_web_orders_keep')}
            </TextButton>
          </div>
        </div>
      ) : (
        open && (
          <div className="flex flex-wrap items-center gap-2">
            {order.status === 'sent' && (
              <>
                <PrimaryButton disabled={action.busy} onClick={() => void act(() => client.orderAction(order.id, 'accept'))}>
                  {s('bipe_order_accept')}
                </PrimaryButton>
                <TextButton danger disabled={action.busy} onClick={() => setRefusing(true)}>
                  {s('bipe_order_refuse')}
                </TextButton>
              </>
            )}
            {order.status === 'accepted' && (
              <PrimaryButton disabled={action.busy} onClick={() => void act(() => client.orderAction(order.id, 'ready'))}>
                {s('bipe_order_mark_ready')}
              </PrimaryButton>
            )}
            {order.status === 'ready' && (
              <PrimaryButton disabled={action.busy} onClick={() => void act(() => client.orderAction(order.id, 'done'))}>
                {s('bipe_order_mark_done')}
              </PrimaryButton>
            )}
            <TextButton danger disabled={action.busy} onClick={() => setConfirmsCancel(true)}>
              {s('bipe_order_cancel')}
            </TextButton>
          </div>
        )
      )}
      <OrderErrorLine error={action.error} />
    </div>
  );
}

/**
 * Action newOrder (OrderOwnerNewView): an order from the company's own catálogo for a typed name, paid in
 * person and accepted at once by bipe-api. Only active fixed-price products can be ordered (isOrderable); an item
 * sold out today shows but cannot be added. A name-only order gets no card (ADR 0105).
 */
function NewOrder({
  client,
  onClose,
  onCreated,
}: {
  client: BusinessClient;
  onClose: () => void;
  onCreated: (o: Order) => void;
}) {
  const catalog = useLoad(
    () => client.catalog().then((c) => c.items.filter((i) => unitCents(i) !== undefined).sort((a, b) => a.sort - b.sort)),
    [client],
  );
  const save = useAction();
  const [cart, setCart] = useState<Record<string, number>>({});
  const [name, setName] = useState('');
  const [fulfilment, setFulfilment] = useState<Order['fulfilment']>('pickup');

  const products = catalog.data;
  const setQuantity = (id: string, quantity: number) =>
    setCart((c) => {
      const next = { ...c };
      if (!Number.isFinite(quantity) || quantity <= 0) delete next[id];
      else next[id] = Math.min(Math.floor(quantity), MAX_QUANTITY);
      return next;
    });
  const quantity = (id: string) => cart[id] ?? 0;
  const inCart = (products ?? []).filter((p) => quantity(p.id) > 0);
  const rest = (products ?? []).filter((p) => quantity(p.id) === 0);
  // A preview: the server snapshots the prices and adds the total.
  const total = inCart.reduce((sum, p) => sum + quantity(p.id) * (unitCents(p) ?? 0), 0);
  const customer = name.trim();

  const submit = async () => {
    if (inCart.length === 0 || customer === '') return;
    const created = await save.run(() =>
      client.createOrder({
        lines: inCart.map((p) => ({ item_id: p.id, quantity: quantity(p.id) })),
        fulfilment,
        payment: 'in_person',
        customer: { name: customer },
      }),
    );
    if (created) onCreated(created);
    // A changed catálogo: read it again, as the phone does.
    else void catalog.reload();
  };

  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <DetailHeader title={s('bipe_order_new')} onClose={onClose} />
      {!products ? (
        catalog.loading ? (
          <LoadState loading error={undefined} onRetry={catalog.reload} />
        ) : (
          <div className="flex flex-col items-center gap-3 p-8 text-center">
            <p className="text-body text-ink-secondary">{s('bipe_catalog_load_failed')}</p>
            <SecondaryButton onClick={catalog.reload}>{s('bipe_app_retry')}</SecondaryButton>
          </div>
        )
      ) : (
        <form
          className="flex flex-col gap-6"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <Field label={s('bipe_agenda_customer')}>
            <input
              className={inputClass}
              value={name}
              maxLength={120}
              placeholder={s('bipe_agenda_customer_name')}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>

          <Section header={s('bipe_order_cart')}>
            {inCart.length === 0 ? (
              <Row>
                <span className="text-body text-ink-secondary">{s('bipe_order_empty')}</span>
              </Row>
            ) : (
              inCart.map((p) => (
                <Row key={p.id}>
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-body text-ink-primary">{p.name}</span>
                    <span className="text-subheadline text-ink-secondary">
                      {s('bipe_order_line', String(quantity(p.id)), money(unitCents(p) ?? 0))}
                    </span>
                  </span>
                  <input
                    type="number"
                    inputMode="numeric"
                    min={0}
                    max={MAX_QUANTITY}
                    aria-label={`${s('bipe_order_quantity')}: ${p.name}`}
                    className={`${inputClass} w-20 text-right tabular-nums`}
                    value={quantity(p.id)}
                    onChange={(e) => setQuantity(p.id, e.target.valueAsNumber)}
                  />
                </Row>
              ))
            )}
          </Section>

          {rest.length > 0 && (
            <Section header={s('bipe_catalog_title')}>
              {rest.map((p) => (
                <Row key={p.id}>
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-body text-ink-primary">{p.name}</span>
                    <span className="text-subheadline text-ink-secondary">{money(unitCents(p) ?? 0)}</span>
                  </span>
                  <button
                    type="button"
                    className="text-subheadline font-semibold text-accent-ink disabled:text-ink-disabled"
                    disabled={p.sold_out_today === true}
                    onClick={() => setQuantity(p.id, 1)}
                  >
                    {s('bipe_order_add')}
                  </button>
                </Row>
              ))}
            </Section>
          )}

          <Section header={s('bipe_order_fulfilment')} footer={s('bipe_order_address_in_chat')}>
            {(['pickup', 'delivery'] as const).map((f) => (
              <Row key={f}>
                <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                  <span className="min-w-0 flex-1 text-body text-ink-primary">{fulfilmentText(f)}</span>
                  <input
                    type="radio"
                    name="fulfilment"
                    className="size-5 accent-[var(--color-accent-fill)]"
                    checked={fulfilment === f}
                    onChange={() => setFulfilment(f)}
                  />
                </label>
              </Row>
            ))}
          </Section>

          <Section header={s('bipe_order_payment')}>
            <Row>
              <span className="text-body text-ink-primary">{s('bipe_order_pay_on_site')}</span>
            </Row>
          </Section>

          <p className="flex items-baseline gap-2 px-4 text-headline text-ink-primary">
            <span className="flex-1">{s('bipe_order_total')}</span>
            <span className="tabular-nums">{money(total)}</span>
          </p>

          <OrderErrorLine error={save.error} />
          <div className="flex gap-2">
            <PrimaryButton type="submit" disabled={save.busy || inCart.length === 0 || customer === ''}>
              {s('bipe_app_save')}
            </PrimaryButton>
            <SecondaryButton onClick={onClose} disabled={save.busy}>
              {s('bipe_app_cancel')}
            </SecondaryButton>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * Action saveSettings (OrderSettingsView): the order days (ISO weekdays) and the local cut-off. The time zone
 * stays as bipe-api has it. An empty day list closes orders (OrderPolicy).
 */
function OrderSettings({ client, onClose }: { client: BusinessClient; onClose: () => void }) {
  const load = useLoad(() => client.orderPolicy(), [client]);
  const save = useAction();
  const [draft, setDraft] = useState<OrderPolicy>();
  const policy = draft ?? load.data;

  const toggleDay = (day: number, on: boolean) => {
    if (!policy) return;
    const days = new Set(policy.weekdays);
    if (on) days.add(day);
    else days.delete(day);
    setDraft({ ...policy, weekdays: [...days].sort((a, b) => a - b) });
  };

  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <DetailHeader title={s('bipe_order_settings')} onClose={onClose} />
      {!policy ? (
        load.loading ? (
          <LoadState loading error={undefined} onRetry={load.reload} />
        ) : (
          <div className="flex flex-col items-center gap-3 p-8 text-center">
            <p className="text-body text-ink-secondary">{s('bipe_order_load_failed')}</p>
            <SecondaryButton onClick={load.reload}>{s('bipe_app_retry')}</SecondaryButton>
          </div>
        )
      ) : (
        <form
          className="flex flex-col gap-6"
          onSubmit={async (e) => {
            e.preventDefault();
            const saved = await save.run(() => client.setOrderPolicy(policy));
            if (saved) {
              load.setData(saved);
              setDraft(undefined);
            }
          }}
        >
          <Section header={s('bipe_order_opening_days')}>
            {[1, 2, 3, 4, 5, 6, 7].map((day) => (
              <Row key={day}>
                <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                  <span className="min-w-0 flex-1 text-body capitalize text-ink-primary">{weekdayName(day)}</span>
                  <input
                    type="checkbox"
                    role="switch"
                    className="size-5 accent-[var(--color-accent-fill)]"
                    checked={policy.weekdays.includes(day)}
                    onChange={(e) => toggleDay(day, e.target.checked)}
                  />
                </label>
              </Row>
            ))}
          </Section>
          <Section>
            <Row>
              <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                <span className="min-w-0 flex-1 text-body text-ink-primary">{s('bipe_order_cutoff')}</span>
                <input
                  type="time"
                  required
                  className={inputClass}
                  value={policy.cutoff}
                  onChange={(e) => {
                    if (e.target.value) setDraft({ ...policy, cutoff: e.target.value.slice(0, 5) });
                  }}
                />
              </label>
            </Row>
          </Section>
          <OrderErrorLine error={save.error} />
          <div>
            <PrimaryButton type="submit" disabled={save.busy || draft === undefined}>
              {s('bipe_app_save')}
            </PrimaryButton>
          </div>
        </form>
      )}
    </div>
  );
}

/** The title of the right column, with a close button where only one column shows (below 900 px). */
function DetailHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <header className="flex items-center gap-2 pt-4">
      <h2 className="min-w-0 flex-1 truncate text-title2 font-bold text-ink-primary">{title}</h2>
      <span className="min-[900px]:hidden">
        <TextButton onClick={onClose}>{s('bipe_app_close')}</TextButton>
      </span>
    </header>
  );
}

function StatusLine({ status }: { status: OrderStatus }) {
  return <span className={`text-subheadline font-semibold ${STATUS_TINT[status]}`}>{s(STATUS_TEXT[status])}</span>;
}

/** The text of a failed Pedidos call: BipeAPIError.orderMessage on iOS. */
function OrderErrorLine({ error }: { error: unknown }) {
  if (error === undefined) return null;
  return (
    <p role="alert" className="text-footnote text-trust-danger-ink">
      {orderErrorText(error)}
    </p>
  );
}

function orderErrorText(e: unknown): string {
  if (!(e instanceof WorkError)) return s('bipe_order_error');
  switch (e.code) {
    case 'network':
      return s('bipe_error_network');
    case 'order_already_open':
      return s('bipe_order_error_already_open');
    case 'orders_closed':
      return s('bipe_order_error_closed');
    case 'item_unavailable':
      return s('bipe_order_error_item');
    case 'price_not_fixed':
      return s('bipe_order_error_price');
    case 'payment_requires_verified_business':
    case 'pix_charge_unavailable':
      return s('bipe_order_error_pix');
    default:
      return s('bipe_order_error');
  }
}

/** The customer of an order: the typed name, or the signed-in customer's profile name (common.tsx customerName). */
function customerText(c: BookingCustomer): string {
  return customerName(c);
}

/** The price of an orderable product (isOrderable on iOS): an active fixed-price product with an amount. */
function unitCents(item: CatalogItem): number | undefined {
  if (item.kind !== 'product' || !item.active) return undefined;
  return item.price.mode === 'fixed' ? item.price.amount_cents : undefined;
}

function fulfilmentText(f: Order['fulfilment']): string {
  return f === 'pickup' ? s('bipe_order_pickup') : s('bipe_order_delivery');
}

function refuseText(reason: OrderRefuseReason): string {
  const key = REFUSE_REASONS.find((r) => r.reason === reason)?.text;
  return key ? s(key) : reason;
}

/** The day and the clock time of an order (BusinessText.when on iOS). */
function whenText(iso: string): string {
  const ms = Date.parse(iso);
  return `${dayText(ms)} · ${timeText(ms)}`;
}

const weekdayFormat = new Intl.DateTimeFormat(locale, { weekday: 'long' });
// 2024-01-01 was a Monday: ISO day 1.
const weekdayName = (isoDay: number) => weekdayFormat.format(new Date(2024, 0, isoDay));

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Orçamentos on the web (ADR 0226, bipe-chat/bipe#983): spec quote-builder, QuoteOwnerView and QuoteBuilderView on
// iOS. The list holds the open requests first, then the sent quotes with their status. A request opens the builder;
// a quote opens its versions and, while it is open or expired, a new version prefilled with the current one. The
// builder follows the phone: lines from the catálogo or typed, 1 to 50 lines, quantity above 0 up to 100 000, unit
// price in reais, validity 7 days ahead by default and sent as the end of that local day in UTC, an optional visit
// service. Totals come from the server; the builder shows the draft total as a preview only. The customer name is
// not resolved here (the screen has no Matrix client), so a row shows the customer's user ID. No Pix charge on the
// web (ADR 0226 point 6); the phone offers none on a quote either.

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type {
  CatalogItem,
  Quote,
  QuoteLineInput,
  QuoteRequest,
  QuoteVersion,
  QuoteVersionInput,
} from '../bipe/generated/business-api';
import { trash } from '../phosphor/icons';
import { locale, s, timeText } from '../ui/i18n';
import { Icon } from '../ui/Icon';
import { NavBar, Row, Section } from '../ui/List';
import { WorkError } from './business-client';
import {
  customerName,
  Field,
  inputClass,
  LoadState,
  money,
  PrimaryButton,
  SecondaryButton,
  SplitPane,
  useAction,
  useLoad,
  useNames,
  type WorkScreenProps,
} from './common';
import { w } from './strings';

/** The lists refresh this often while the screen is open, as the phone does on scene activation. */
const REFRESH_MS = 30_000;
/** bipe-api VersionBody: 1 to 50 lines, quantity above 0 up to 100 000, amounts up to 1 000 000 000 cents. */
const MAX_LINES = 50;
const MAX_QUANTITY = 100_000;
const MAX_CENTS = 1_000_000_000;
const MAX_NAME = 200;
/** QuoteBuilderView: the validity starts 7 days ahead. */
const DEFAULT_VALID_DAYS = 7;

type Open = { kind: 'request'; id: string } | { kind: 'quote'; id: string; revising: boolean };

export function QuotesScreen({ client, onBack }: WorkScreenProps) {
  useNames();
  const lists = useLoad(async () => {
    const [requests, quotes] = await Promise.all([client.quoteRequests(), client.quotes()]);
    return {
      // Newest first, as the phone sorts them.
      requests: [...requests.requests].sort((a, b) => b.created_at.localeCompare(a.created_at)),
      quotes: quotes.quotes,
    };
  }, [client]);
  // The builder's catálogo. A failed read leaves only typed lines, as on the phone.
  const catalog = useLoad(() => client.catalog().then((c) => c.items).catch((): CatalogItem[] => []), [client]);
  const [open, setOpen] = useState<Open>();

  const { reload } = lists;
  useEffect(() => {
    const timer = setInterval(() => void reload(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [reload]);

  const data = lists.data;
  const items = catalog.data ?? [];

  /** Actions answer and revise succeeded: the quote shows at once, then both lists reload. */
  const sent = (quote: Quote) => {
    if (data) {
      lists.setData({
        requests: data.requests.filter((r) => r.id !== quote.request_id),
        quotes: [quote, ...data.quotes.filter((q) => q.id !== quote.id)],
      });
    }
    setOpen({ kind: 'quote', id: quote.id, revising: false });
    void reload();
  };

  let detail: ReactNode;
  if (data && open?.kind === 'request') {
    const request = data.requests.find((r) => r.id === open.id);
    if (request)
      detail = (
        <QuoteBuilder
          key={`answer-${request.id}`}
          client={client}
          catalog={items}
          target={{ kind: 'answer', request }}
          onSent={sent}
          onClose={() => setOpen(undefined)}
        />
      );
  } else if (data && open?.kind === 'quote') {
    const quote = data.quotes.find((q) => q.id === open.id);
    if (quote)
      detail = open.revising ? (
        <QuoteBuilder
          key={`revise-${quote.id}-${quote.current_version}`}
          client={client}
          catalog={items}
          target={{ kind: 'revise', quote }}
          onSent={sent}
          onClose={() => setOpen({ kind: 'quote', id: quote.id, revising: false })}
        />
      ) : (
        <QuoteDetail
          quote={quote}
          catalog={items}
          onBack={() => setOpen(undefined)}
          onRevise={() => setOpen({ kind: 'quote', id: quote.id, revising: true })}
        />
      );
  }

  const list = (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_quotes_title')} onBack={onBack} />
      {!data ? (
        lists.loading ? (
          <LoadState loading error={undefined} onRetry={() => void reload()} />
        ) : (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
            <p role="alert" className="text-body text-ink-secondary">
              {s('bipe_quote_load_failed')}
            </p>
            <PrimaryButton onClick={() => void reload()}>{s('bipe_app_retry')}</PrimaryButton>
          </div>
        )
      ) : data.requests.length === 0 && data.quotes.length === 0 ? (
        <p className="grid flex-1 place-items-center p-8 text-center text-body text-ink-secondary">
          {s('bipe_quote_owner_empty')}
        </p>
      ) : (
        <div className="flex flex-col gap-6 overflow-y-auto px-4 pb-8">
          {/* A failed refresh keeps the lists on screen, with the phone's toast text as a line. */}
          {lists.error !== undefined && (
            <p role="alert" className="text-footnote text-trust-danger-ink">
              {s('bipe_quote_load_failed')}
            </p>
          )}
          {data.requests.length > 0 && (
            <Section header={s('bipe_quote_requests_title')}>
              {data.requests.map((request) => (
                <RequestRow
                  key={request.id}
                  request={request}
                  selected={open?.kind === 'request' && open.id === request.id}
                  onOpen={() => setOpen({ kind: 'request', id: request.id })}
                />
              ))}
            </Section>
          )}
          {data.quotes.length > 0 && (
            <Section header={s('bipe_quotes_title')}>
              {data.quotes.map((quote) => (
                <QuoteRow
                  key={quote.id}
                  quote={quote}
                  selected={open?.kind === 'quote' && open.id === quote.id}
                  onOpen={() => setOpen({ kind: 'quote', id: quote.id, revising: false })}
                />
              ))}
            </Section>
          )}
        </div>
      )}
    </div>
  );

  return <SplitPane list={list} detail={detail} />;
}

// ---- List rows ----

function RequestRow({ request, selected, onOpen }: { request: QuoteRequest; selected: boolean; onOpen: () => void }) {
  return (
    <Row>
      <button
        type="button"
        aria-current={selected || undefined}
        className="flex min-w-0 flex-1 flex-col py-1 text-left"
        onClick={onOpen}
      >
        <span className={`truncate text-body font-semibold ${selected ? 'text-accent-ink' : 'text-ink-primary'}`}>
          {customerLabel(request.customer_id)}
        </span>
        <span className="truncate text-footnote text-ink-secondary">
          {s('bipe_quote_requested_at', whenText(request.created_at))}
        </span>
      </button>
    </Row>
  );
}

function QuoteRow({ quote, selected, onOpen }: { quote: Quote; selected: boolean; onOpen: () => void }) {
  const current = currentVersion(quote);
  return (
    <Row>
      <button
        type="button"
        aria-current={selected || undefined}
        className="flex min-w-0 flex-1 flex-col py-1 text-left"
        onClick={onOpen}
      >
        <span className="flex items-baseline gap-2">
          <span
            className={`min-w-0 flex-1 truncate text-body font-semibold ${selected ? 'text-accent-ink' : 'text-ink-primary'}`}
          >
            {customerLabel(quote.customer_id)}
          </span>
          <span className="shrink-0 text-body tabular-nums text-ink-primary">
            {money(current?.total_amount_cents ?? 0)}
          </span>
        </span>
        <span className="truncate text-footnote text-ink-secondary">
          {s('bipe_quote_version', String(quote.current_version))}
        </span>
        <StatusLine status={quote.status} />
      </button>
    </Row>
  );
}

// ---- A sent quote ----

/** Spec quote-card in owner mode: the current version, the older ones read only, Enviar nova versão while open. */
function QuoteDetail({
  quote,
  catalog,
  onBack,
  onRevise,
}: {
  quote: Quote;
  catalog: CatalogItem[];
  onBack: () => void;
  onRevise: () => void;
}) {
  const current = currentVersion(quote);
  const previous = quote.versions.filter((v) => v.version !== quote.current_version).sort((a, b) => b.version - a.version);
  const revisable = quote.status === 'offered' || quote.status === 'expired';
  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_quote_card_title')} onBack={onBack} />
      <div className="flex flex-col gap-6 overflow-y-auto px-4 pb-8">
        <p className="truncate px-4 text-headline text-ink-primary">{customerLabel(quote.customer_id)}</p>
        {current && (
          <QuoteCard
            card={versionCard(current, catalog)}
            status={quote.status}
            caption={w('bipe_web_quotes_preview')}
          />
        )}
        {revisable && (
          <div>
            <PrimaryButton onClick={onRevise}>{s('bipe_quote_send_revision')}</PrimaryButton>
          </div>
        )}
        {previous.length > 0 && (
          <section className="flex flex-col gap-3">
            <h3 className="px-4 text-footnote text-ink-secondary">{s('bipe_quote_previous_versions')}</h3>
            {previous.map((v) => (
              <QuoteCard key={v.version} card={versionCard(v, catalog)} />
            ))}
          </section>
        )}
      </div>
    </div>
  );
}

// ---- The builder ----

type BuilderTarget = { kind: 'answer'; request: QuoteRequest } | { kind: 'revise'; quote: Quote };

/** One line being built: a catálogo item (name fixed) or a typed name. Quantity and price stay as typed. */
type DraftLine = { key: number; itemId?: string; name: string; quantity: string; unitPrice: string };

let nextLineKey = 0;
const draftLine = (line: Omit<DraftLine, 'key'>): DraftLine => ({ key: nextLineKey++, ...line });

/** Spec quote-builder actions addLine, removeLine, answer and revise. */
function QuoteBuilder({
  client,
  catalog,
  target,
  onSent,
  onClose,
}: {
  client: WorkScreenProps['client'];
  catalog: CatalogItem[];
  target: BuilderTarget;
  onSent: (quote: Quote) => void;
  onClose: () => void;
}) {
  const revising = target.kind === 'revise';
  // A revision starts from the current version; its validity only when it is still ahead.
  const start = target.kind === 'revise' ? currentVersion(target.quote) : undefined;
  const [lines, setLines] = useState<DraftLine[]>(() =>
    (start?.lines ?? []).map((l) =>
      draftLine({
        itemId: l.item_id,
        name: l.name,
        quantity: quantityText(l.quantity),
        unitPrice: reaisText(l.unit_amount_cents),
      }),
    ),
  );
  const [validUntil, setValidUntil] = useState(() =>
    start && Date.parse(start.valid_until) > Date.now()
      ? localDate(new Date(start.valid_until))
      : localDate(addDays(new Date(), DEFAULT_VALID_DAYS)),
  );
  const [visitServiceId, setVisitServiceId] = useState<string | undefined>(start?.visit_service_id);
  const send = useAction();

  const active = useMemo(() => catalog.filter((i) => i.active), [catalog]);
  const services = useMemo(() => active.filter((i) => i.kind === 'service'), [active]);

  const update = (key: number, patch: Partial<DraftLine>) =>
    setLines((all) => all.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  /** Action addLine from the catálogo: its fixed price as the default unit price. */
  const addItem = (id: string) => {
    const item = active.find((i) => i.id === id);
    if (!item || lines.length >= MAX_LINES) return;
    const cents = item.price.mode === 'on_request' ? undefined : item.price.amount_cents;
    setLines((all) => [
      ...all,
      draftLine({ itemId: item.id, name: item.name, quantity: '1', unitPrice: cents === undefined ? '' : reaisText(cents) }),
    ]);
  };

  const draft = buildDraft(lines, validUntil, visitServiceId);
  const total = previewTotal(lines);
  const today = localDate(new Date());
  const visitName = services.find((sv) => sv.id === visitServiceId)?.name;

  const submit = async () => {
    if (!draft) return;
    const quote = await send.run(() =>
      target.kind === 'answer'
        ? client.answerRequest(target.request.id, draft)
        : client.reviseQuote(target.quote.id, draft),
    );
    if (quote) onSent(quote);
  };

  const customerId = target.kind === 'answer' ? target.request.customer_id : target.quote.customer_id;

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar
        title={revising ? s('bipe_quote_new_version') : s('bipe_quote_builder_title')}
        onBack={onClose}
        backLabel={s('bipe_app_cancel')}
      />
      <form
        className="flex flex-col gap-6 overflow-y-auto px-4 pb-8"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Section header={customerLabel(customerId)}>
          {lines.map((line) => (
            <li key={line.key} className="flex flex-col gap-2 px-4 py-3 [&:not(:last-child)]:border-b-[0.5px] [&:not(:last-child)]:border-line-subtle">
              <div className="flex items-center gap-2">
                {line.itemId === undefined ? (
                  <input
                    className={`${inputClass} min-w-0 flex-1`}
                    aria-label={s('bipe_quote_line_name')}
                    placeholder={s('bipe_quote_line_name')}
                    maxLength={MAX_NAME}
                    value={line.name}
                    onChange={(e) => update(line.key, { name: e.target.value })}
                  />
                ) : (
                  <span className="min-w-0 flex-1 truncate text-headline text-ink-primary">{line.name}</span>
                )}
                <button
                  type="button"
                  className="grid size-11 shrink-0 place-items-center rounded-full text-trust-danger-ink"
                  onClick={() => setLines((all) => all.filter((l) => l.key !== line.key))}
                >
                  <Icon path={trash} size={20} label={s('bipe_catalog_remove')} />
                </button>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Field label={s('bipe_quote_quantity')}>
                  <input
                    className={inputClass}
                    inputMode="decimal"
                    value={line.quantity}
                    aria-invalid={parseQuantity(line.quantity) === undefined}
                    onChange={(e) => update(line.key, { quantity: e.target.value })}
                  />
                </Field>
                <Field label={s('bipe_quote_unit_price')}>
                  <input
                    className={inputClass}
                    inputMode="decimal"
                    placeholder="0,00"
                    value={line.unitPrice}
                    aria-invalid={line.unitPrice !== '' && parseCents(line.unitPrice) === undefined}
                    onChange={(e) => update(line.key, { unitPrice: e.target.value })}
                  />
                </Field>
              </div>
            </li>
          ))}
          <Row>
            <div className="flex flex-1 flex-wrap items-center gap-3">
              <button
                type="button"
                className="min-h-11 text-body text-accent-ink disabled:opacity-50"
                disabled={lines.length >= MAX_LINES}
                onClick={() => setLines((all) => [...all, draftLine({ name: '', quantity: '1', unitPrice: '' })])}
              >
                {s('bipe_quote_add_line')}
              </button>
              {active.length > 0 && (
                <select
                  className={`${inputClass} min-w-0 flex-1`}
                  aria-label={s('bipe_quote_from_catalog')}
                  value=""
                  disabled={lines.length >= MAX_LINES}
                  onChange={(e) => addItem(e.target.value)}
                >
                  <option value="" disabled>
                    {s('bipe_quote_from_catalog')}
                  </option>
                  {active.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.price.mode === 'on_request' ? item.name : `${item.name} · ${item.price.text}`}
                    </option>
                  ))}
                </select>
              )}
            </div>
          </Row>
        </Section>

        <Section>
          <li className="flex flex-col gap-3 px-4 py-3">
            <Field label={s('bipe_quote_validity')}>
              <input
                type="date"
                className={inputClass}
                min={today}
                required
                value={validUntil}
                onChange={(e) => setValidUntil(e.target.value)}
              />
            </Field>
            {services.length > 0 && (
              <Field label={s('bipe_quote_visit')}>
                <select
                  className={inputClass}
                  value={visitServiceId ?? ''}
                  onChange={(e) => setVisitServiceId(e.target.value || undefined)}
                >
                  <option value="">{s('bipe_quote_visit_none')}</option>
                  {services.map((sv) => (
                    <option key={sv.id} value={sv.id}>
                      {sv.name}
                    </option>
                  ))}
                </select>
              </Field>
            )}
          </li>
        </Section>

        <Section>
          <Row>
            <span className="min-w-0 flex-1 text-headline text-ink-primary">{s('bipe_quote_total')}</span>
            <span className="text-headline tabular-nums text-ink-primary">{money(total)}</span>
          </Row>
        </Section>

        {lines.length > 0 && (
          <QuoteCard
            caption={w('bipe_web_quotes_preview')}
            status="offered"
            card={{
              version: revising && target.kind === 'revise' ? target.quote.current_version + 1 : 1,
              lines: lines.flatMap((l) => {
                const quantity = parseQuantity(l.quantity);
                const cents = parseCents(l.unitPrice);
                const name = l.name.trim();
                return quantity === undefined || cents === undefined || name === ''
                  ? []
                  : [{ quantity, name, cents: Math.round(quantity * cents) }];
              }),
              totalCents: total,
              validUntil: validUntil ? endOfLocalDay(validUntil) : undefined,
              visitName: visitServiceId ? (visitName ?? '') : undefined,
            }}
          />
        )}

        {send.error !== undefined && (
          <p role="alert" className="text-footnote text-trust-danger-ink">
            {sendErrorText(send.error)}
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <PrimaryButton type="submit" disabled={draft === undefined || send.busy}>
            {revising ? s('bipe_quote_send_revision') : s('bipe_quote_send')}
          </PrimaryButton>
          <SecondaryButton onClick={onClose} disabled={send.busy}>
            {s('bipe_app_cancel')}
          </SecondaryButton>
        </div>
      </form>
    </div>
  );
}

/** The version to send; undefined while a line is not complete, as QuoteBuilderView.draft. */
function buildDraft(lines: DraftLine[], validUntil: string, visitServiceId: string | undefined): QuoteVersionInput | undefined {
  if (lines.length === 0 || lines.length > MAX_LINES || !validUntil) return undefined;
  const inputs: QuoteLineInput[] = [];
  for (const line of lines) {
    const quantity = parseQuantity(line.quantity);
    const cents = parseCents(line.unitPrice);
    if (quantity === undefined || cents === undefined) return undefined;
    if (line.itemId !== undefined) {
      inputs.push({ item_id: line.itemId, quantity, unit_amount_cents: cents });
    } else {
      const name = line.name.trim();
      if (name === '' || name.length > MAX_NAME) return undefined;
      inputs.push({ name, quantity, unit_amount_cents: cents });
    }
  }
  const end = endOfLocalDay(validUntil);
  if (end === undefined || Date.parse(end) <= Date.now()) return undefined;
  return { lines: inputs, valid_until: end, ...(visitServiceId ? { visit_service_id: visitServiceId } : {}) };
}

/** The draft total as a preview only: the server adds the total, rounding each line to centavos. */
function previewTotal(lines: DraftLine[]): number {
  return lines.reduce((sum, line) => {
    const quantity = parseQuantity(line.quantity);
    const cents = parseCents(line.unitPrice);
    return quantity === undefined || cents === undefined ? sum : sum + Math.round(quantity * cents);
  }, 0);
}

/** BipeAPIError.quoteMessage: expired and version-changed have their own text, else the quote error. */
function sendErrorText(e: unknown): string {
  if (e instanceof WorkError) {
    if (e.code === 'network') return s('bipe_error_network');
    if (e.code === 'quote_expired') return s('bipe_quote_error_expired');
    if (e.code === 'quote_version_changed') return s('bipe_quote_error_version');
  }
  return s('bipe_quote_error');
}

// ---- The customer's card (spec quote-card), read only ----

type CardData = {
  version: number;
  lines: { quantity: number; name: string; cents: number }[];
  totalCents: number;
  /** UTC ISO; undefined while the draft has no date. */
  validUntil?: string;
  /** Set when the version has a visit service: its name, or empty when the catálogo no longer lists it. */
  visitName?: string;
};

function versionCard(v: QuoteVersion, catalog: CatalogItem[]): CardData {
  return {
    version: v.version,
    lines: v.lines.map((l) => ({ quantity: l.quantity, name: l.name, cents: l.total_amount_cents })),
    totalCents: v.total_amount_cents,
    validUntil: v.valid_until,
    visitName: v.visit_service_id ? (catalog.find((i) => i.id === v.visit_service_id)?.name ?? '') : undefined,
  };
}

/** QuoteCardView and QuoteVersionSummary: title and version, lines, total, validity, visit and status. */
function QuoteCard({ card, status, caption }: { card: CardData; status?: Quote['status']; caption?: string }) {
  return (
    <figure className="flex flex-col gap-1.5">
      {caption && <figcaption className="px-4 text-footnote text-ink-secondary">{caption}</figcaption>}
      <div className="flex max-w-[360px] flex-col gap-1.5 rounded-2xl bg-bubble-other p-3 text-body">
        <div className="flex items-baseline gap-2">
          <span className="flex-1 text-caption font-bold text-accent-ink">{s('bipe_quote_card_title')}</span>
          <span className="text-caption text-ink-secondary">{s('bipe_quote_version', String(card.version))}</span>
        </div>
        {card.lines.map((line, i) => (
          <div key={i} className="flex items-baseline gap-2">
            <span className="min-w-0 flex-1 text-ink-primary">
              {s('bipe_order_line', quantityText(line.quantity), line.name)}
            </span>
            <span className="shrink-0 text-subheadline tabular-nums text-ink-secondary">{money(line.cents)}</span>
          </div>
        ))}
        <div className="flex items-baseline gap-2 font-bold text-ink-primary">
          <span className="flex-1">{s('bipe_quote_total')}</span>
          <span className="tabular-nums">{money(card.totalCents)}</span>
        </div>
        {card.validUntil && (
          <span className="text-subheadline text-ink-secondary">
            {s('bipe_quote_valid_until', dateText(card.validUntil))}
          </span>
        )}
        {card.visitName !== undefined && (
          <span className="text-subheadline text-ink-secondary">
            {[s('bipe_quote_visit'), card.visitName].filter((t) => t !== '').join(': ')}
          </span>
        )}
        {status && <StatusLine status={status} />}
      </div>
    </figure>
  );
}

/** BusinessStatusLine with BipeQuoteStatus text and tint. */
function StatusLine({ status }: { status: Quote['status'] }) {
  const [text, tint] = {
    offered: [s('bipe_quote_status_offered'), 'text-trust-caution-ink'],
    accepted: [s('bipe_quote_status_accepted'), 'text-trust-verified-ink'],
    refused: [s('bipe_quote_status_refused'), 'text-ink-secondary'],
    expired: [s('bipe_quote_status_expired'), 'text-ink-secondary'],
  }[status];
  return <span className={`text-footnote font-semibold ${tint}`}>{text}</span>;
}

// ---- Values ----

/** The version that can be accepted; the newest when the list lacks it. */
function currentVersion(quote: Quote): QuoteVersion | undefined {
  return (
    quote.versions.find((v) => v.version === quote.current_version) ??
    quote.versions.reduce<QuoteVersion | undefined>((a, v) => (a && a.version > v.version ? a : v), undefined)
  );
}

/** The customer of a request or quote. The web screen has no name lookup, so the user ID shows. */
function customerLabel(userId: string): string {
  return customerName(userId);
}

/** QuoteBuilderView.quantity: comma or point, above 0 up to 100 000. */
function parseQuantity(typed: string): number | undefined {
  const text = typed.trim().replace(',', '.');
  if (!/^\d+(\.\d+)?$/.test(text)) return undefined;
  const value = Number(text);
  return value > 0 && value <= MAX_QUANTITY ? value : undefined;
}

/**
 * BipeMoney.cents(from:): an amount in reais to cents, rounded half up. With a comma, points are thousands
 * (1.234,56); without one, a point is the decimal mark (40.5). Exact on the digits, no float rounding.
 */
function parseCents(typed: string): number | undefined {
  let text = typed.replace(/R\$/g, '').replace(/\s/g, '');
  if (text === '') return undefined;
  if (text.includes(',')) text = text.replace(/\./g, '').replace(',', '.');
  const match = /^(\d*)(?:\.(\d*))?$/.exec(text);
  if (!match || (match[1] === '' && !match[2])) return undefined;
  const whole = Number(match[1] || '0');
  const fraction = match[2] ?? '';
  const cents = whole * 100 + Number(fraction.padEnd(2, '0').slice(0, 2)) + (fraction.charAt(2) >= '5' ? 1 : 0);
  return Number.isSafeInteger(cents) && cents <= MAX_CENTS ? cents : undefined;
}

const reaisFormat = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** `40,00`: an amount in reais to edit. */
function reaisText(cents: number): string {
  return reaisFormat.format(cents / 100);
}

const quantityFormat = new Intl.NumberFormat(locale, { maximumFractionDigits: 2, useGrouping: false });
/** BusinessText.quantity: up to two decimals, no grouping. */
function quantityText(quantity: number): string {
  return quantityFormat.format(quantity);
}

const pad = (n: number) => String(n).padStart(2, '0');
/** A local date as the date input holds it: YYYY-MM-DD. */
function localDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function addDays(d: Date, days: number): Date {
  const out = new Date(d);
  out.setDate(out.getDate() + days);
  return out;
}

/** BipeQuoteVersionInput.endOfLocalDay: the last second of a local date, in UTC with a trailing Z. */
function endOfLocalDay(date: string): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return undefined;
  const next = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + 1);
  return new Date(next.getTime() - 1000).toISOString();
}

const dateFormat = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric' });
/** A validity as the card shows it: the date only, abbreviated. */
function dateText(iso: string): string {
  return dateFormat.format(new Date(iso));
}

const weekdayDayFormat = new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'short' });
/** BusinessText.when: `seg., 3 de nov. · 14:30`. */
function whenText(iso: string): string {
  const ms = Date.parse(iso);
  return `${weekdayDayFormat.format(ms)} · ${timeText(ms)}`;
}

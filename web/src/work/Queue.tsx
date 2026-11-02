// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Fila virtual for the owner on the web (ADR 0226, bipe-chat/bipe#984; spec queue-owner, QueueOwnerView on iOS):
// one wide screen for a computer at the counter. The open switch and today's date on top, a large Chamar
// próximo with the place now called in text big enough to read from a distance, the waiting list with its
// actions, a name-only walk-in and the arrival window. Same routes and rules as the phone: it reads again every
// 30 seconds while it shows and right after each action. Space or Enter outside a field calls the next place.

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import type { CatalogItem, Queue, QueuePlace, QueuePlaceStatus } from '../bipe/generated/business-api';
import type { StringKey } from '../bipe/generated/strings';
import { locale, s, timeText } from '../ui/i18n';
import { NavBar, Row, Section } from '../ui/List';
import { Spinner } from '../ui/Spinner';
import { WorkError } from './business-client';
import {
  customerName,
  Field,
  inputClass,
  PrimaryButton,
  SecondaryButton,
  useAction,
  useLoad,
  useNames,
  type WorkScreenProps,
} from './common';
import { w } from './strings';

/** Action refresh: every 30 seconds while the screen shows (refreshEvery30Seconds on iOS). */
const REFRESH_MS = 30_000;

/** Action setWindow: 1 to 240 minutes (bipe-api queue.ts PolicyBody). */
const MIN_WINDOW = 1;
const MAX_WINDOW = 240;

const STATUS_TEXT: Record<QueuePlaceStatus, StringKey> = {
  waiting: 'bipe_queue_status_waiting',
  called: 'bipe_queue_called',
  served: 'bipe_queue_status_served',
  no_show: 'bipe_queue_status_no_show',
  left: 'bipe_queue_status_left',
};

const STATUS_TINT: Record<QueuePlaceStatus, string> = {
  waiting: 'text-trust-caution-ink',
  called: 'text-trust-verified-ink',
  served: 'text-ink-secondary',
  no_show: 'text-trust-danger-ink',
  left: 'text-ink-secondary',
};

/** The text of a failed queue call, as BipeAPIError.queueMessage on iOS. Never the raw API message. */
function queueErrorText(e: unknown): string {
  if (!(e instanceof WorkError)) return s('bipe_queue_error');
  switch (e.code) {
    case 'network':
      return s('bipe_error_network');
    case 'queue_closed':
      return s('bipe_queue_closed');
    case 'already_in_queue':
      return s('bipe_queue_error_already_joined');
    case 'place_not_active':
      return s('bipe_queue_error_inactive');
    default:
      return s('bipe_queue_error');
  }
}

/** A typed walk-in shows its name. A signed-in customer has no name on this screen yet: the placeholder, as iOS without a name. */
function placeName(place: QueuePlace): string {
  return customerName(place.customer);
}

/** Queue.date is the business's local day (YYYY-MM-DD); noon keeps it on that day in every browser zone. */
function queueDay(date: string): string {
  const day = new Date(`${date}T12:00:00`);
  if (Number.isNaN(day.getTime())) return date;
  return new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric', month: 'long' }).format(day);
}

/** Keys typed into a field, a button or an editable area stay there. */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON', 'A'].includes(target.tagName);
}

/** Spec queue-owner. Opens from business-tools (WorkView). */
export function QueueScreen({ client, onBack }: WorkScreenProps) {
  useNames();
  // Action refresh: today's queue and places. The queue resets every day on bipe-api.
  const load = useLoad(async () => {
    const [queue, { places }] = await Promise.all([client.queue(), client.places()]);
    return { queue, places };
  }, [client]);
  // The optional service of a walk-in: the active Catálogo services. A failed read only hides the picker.
  const catalog = useLoad(() => client.catalog().catch(() => ({ items: [] as CatalogItem[] })), [client]);
  const services = useMemo(
    () => (catalog.data?.items ?? []).filter((i) => i.kind === 'service' && i.active),
    [catalog.data],
  );
  const serviceName = useCallback(
    (id: string | undefined) => (id ? (services.find((sv) => sv.id === id)?.name ?? '') : ''),
    [services],
  );

  const act = useAction();
  const { reload } = load;

  // Every 30 seconds while the screen shows, and again when the tab comes back to the front.
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!document.hidden) void reload();
    }, REFRESH_MS);
    const onVisible = () => {
      if (!document.hidden) void reload();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [reload]);

  const queue = load.data?.queue;
  const places = load.data?.places ?? [];
  // The number of a place is its order of arrival today (bipe-api lists today's places by joined_at).
  const numbers = useMemo(() => new Map(places.map((p, i) => [p.id, i + 1])), [places]);
  const waiting = places.filter((p) => p.status === 'waiting');
  const called = places.filter((p) => p.status === 'called');
  const done = places.filter((p) => p.status !== 'waiting' && p.status !== 'called');
  // The place on the big display: the one called last.
  const current = called.reduce<QueuePlace | undefined>(
    (last, p) => (!last || (p.called_at ?? '') > (last.called_at ?? '') ? p : last),
    undefined,
  );

  /** Runs one write, then reads again, as the phone does after each action. */
  const write = useCallback(
    async <T,>(fn: () => Promise<T>) => {
      const result = await act.run(fn);
      await reload();
      return result;
    },
    [act, reload],
  );

  const setOpen = (open: boolean) =>
    write(async () => {
      const q = await client.setQueueOpen(open);
      load.setData((d) => (d ? { ...d, queue: q } : d));
      return q;
    });
  const placeAction = (place: QueuePlace, action: 'served' | 'no-show' | 'recall') =>
    write(() => client.placeAction(place.id, action));

  // Action callNext: hidden when nobody waits, as on the phone. A ref keeps the key handler on the latest state.
  const canCallNext = waiting.length > 0 && !act.busy;
  const callNext = useRef<() => void>(() => {});
  useEffect(() => {
    callNext.current = () => {
      if (canCallNext) void write(() => client.callNext());
    };
  });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== ' ' && e.key !== 'Enter') return;
      if (e.repeat || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey || isTyping(e.target)) return;
      e.preventDefault();
      callNext.current();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (!load.data) {
    return (
      <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
        <NavBar title={s('bipe_queue_title')} onBack={onBack} />
        {load.loading ? (
          <div className="grid flex-1 place-items-center p-8">
            <Spinner />
          </div>
        ) : (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
            <p role="alert" className="text-body text-ink-secondary">
              {s('bipe_queue_load_failed')}
            </p>
            <SecondaryButton onClick={() => void reload()}>{s('bipe_app_retry')}</SecondaryButton>
          </div>
        )}
      </div>
    );
  }

  const isOpen = queue?.open === true;

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_queue_title')} onBack={onBack} />
      <div className="flex flex-col gap-6 overflow-y-auto px-4 pb-8">
        {/* Top: today's date, the open switch and the count. */}
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          {queue && (
            <span className="text-title2 font-bold capitalize text-ink-primary">{queueDay(queue.date)}</span>
          )}
          <label className="flex items-center gap-3">
            <input
              type="checkbox"
              role="switch"
              className="size-5 accent-[var(--color-accent-fill)]"
              checked={isOpen}
              disabled={act.busy || !queue}
              onChange={(e) => void setOpen(e.target.checked)}
              aria-label={isOpen ? s('bipe_queue_close') : s('bipe_queue_open')}
            />
            <span className={`text-headline ${isOpen ? 'text-trust-verified-ink' : 'text-ink-secondary'}`}>
              {isOpen ? s('bipe_queue_open_now') : s('bipe_queue_closed')}
            </span>
          </label>
          <span className="text-body text-ink-secondary">{s('bipe_queue_waiting_count', String(waiting.length))}</span>
        </div>

        {act.error !== undefined && (
          <p role="alert" className="text-footnote text-trust-danger-ink">
            {queueErrorText(act.error)}
          </p>
        )}

        {/* The counter display: the place now called, and Chamar próximo. */}
        <div className="flex flex-col gap-4 rounded-2xl bg-grouped-cell p-6 min-[900px]:flex-row min-[900px]:items-center">
          <div className="flex min-w-0 flex-1 flex-col gap-1" aria-live="polite">
            <span className="text-headline text-ink-secondary">{w('bipe_web_queue_now_calling')}</span>
            {current ? (
              <>
                <span className="text-[96px] font-bold leading-none tabular-nums text-trust-verified-ink">
                  {w('bipe_web_queue_number', String(numbers.get(current.id) ?? ''))}
                </span>
                <span className="truncate text-[40px] font-bold leading-tight text-ink-primary">
                  {placeName(current)}
                </span>
                <span className="text-subheadline text-ink-secondary">
                  {[
                    serviceName(current.service_id),
                    current.called_at ? w('bipe_web_queue_called_at', timeText(Date.parse(current.called_at))) : '',
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
              </>
            ) : (
              <span className="text-title2 text-ink-secondary">{w('bipe_web_queue_none_called')}</span>
            )}
          </div>
          <div className="flex flex-col items-stretch gap-2 min-[900px]:w-[320px]">
            {waiting.length > 0 ? (
              <>
                <button
                  type="button"
                  className="min-h-24 rounded-2xl bg-accent-fill px-6 text-[28px] font-bold text-ink-on-accent disabled:opacity-50"
                  onClick={() => callNext.current()}
                  disabled={!canCallNext}
                >
                  {s('bipe_queue_call_next')}
                </button>
                <span className="text-center text-footnote text-ink-secondary">{w('bipe_web_queue_key_hint')}</span>
              </>
            ) : (
              <span className="text-center text-body text-ink-secondary">{s('bipe_queue_owner_empty')}</span>
            )}
          </div>
        </div>

        <div className="grid gap-6 min-[900px]:grid-cols-[1fr_360px]">
          <div className="flex flex-col gap-6">
            {/* Called places other than the one on display, then the waiting list in order of arrival. */}
            {(called.length > 0 || waiting.length > 0) && (
              <Section header={s('bipe_queue_waiting_count', String(waiting.length))}>
                {[...called, ...waiting].map((place) => (
                  <PlaceRow
                    key={place.id}
                    place={place}
                    number={numbers.get(place.id) ?? 0}
                    service={serviceName(place.service_id)}
                    busy={act.busy}
                    onAction={(a) => void placeAction(place, a)}
                  />
                ))}
              </Section>
            )}
            {done.length > 0 && (
              <Section header={w('bipe_web_queue_earlier')}>
                {done.map((place) => (
                  <PlaceRow
                    key={place.id}
                    place={place}
                    number={numbers.get(place.id) ?? 0}
                    service={serviceName(place.service_id)}
                    busy={act.busy}
                    onAction={(a) => void placeAction(place, a)}
                  />
                ))}
              </Section>
            )}
          </div>
          <div className="flex flex-col gap-6">
            <WalkInForm
              services={services}
              busy={act.busy}
              onAdd={async (name, serviceId) =>
                (await write(() =>
                  client.addPlace({ customer: { name }, ...(serviceId ? { service_id: serviceId } : {}) }),
                )) !== undefined
              }
            />
            {queue && (
              <PolicyForm
                queue={queue}
                busy={act.busy}
                onSave={async (minutes) => {
                  const saved = await write(() => client.setQueuePolicy({ no_show_after_minutes: minutes }));
                  return saved !== undefined;
                }}
              />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * One place: its number, name, service, arrival time and status, with the phone's actions. served from waiting
 * or called; no-show from waiting or called; recall from called or no-show (QueueOwnerView.actions).
 */
function PlaceRow({
  place,
  number,
  service,
  busy,
  onAction,
}: {
  place: QueuePlace;
  number: number;
  service: string;
  busy: boolean;
  onAction: (action: 'served' | 'no-show' | 'recall') => void;
}) {
  const active = place.status === 'waiting' || place.status === 'called';
  const canRecall = place.status === 'called' || place.status === 'no_show';
  return (
    <Row
      leading={
        <span className="w-10 shrink-0 text-right text-title2 font-bold tabular-nums text-ink-primary">{number}</span>
      }
    >
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-headline text-ink-primary">{placeName(place)}</span>
        <span className="truncate text-footnote text-ink-secondary">
          {[service, w('bipe_web_queue_waiting_since', timeText(Date.parse(place.joined_at)))]
            .filter(Boolean)
            .join(' · ')}
        </span>
        <span className={`text-footnote font-semibold ${STATUS_TINT[place.status]}`}>{s(STATUS_TEXT[place.status])}</span>
      </div>
      {(active || canRecall) && (
        <div className="flex shrink-0 flex-wrap justify-end gap-2">
          {active && (
            <>
              <SecondaryButton onClick={() => onAction('served')} disabled={busy}>
                {s('bipe_queue_mark_served')}
              </SecondaryButton>
              <SecondaryButton onClick={() => onAction('no-show')} disabled={busy}>
                {s('bipe_queue_mark_no_show')}
              </SecondaryButton>
            </>
          )}
          {canRecall && (
            <SecondaryButton onClick={() => onAction('recall')} disabled={busy}>
              {s('bipe_queue_recall')}
            </SecondaryButton>
          )}
        </div>
      )}
    </Row>
  );
}

/** Action addWalkIn with a typed name and an optional service. The field clears after an add. */
function WalkInForm({
  services,
  busy,
  onAdd,
}: {
  services: CatalogItem[];
  busy: boolean;
  onAdd: (name: string, serviceId: string | undefined) => Promise<boolean>;
}) {
  const [name, setName] = useState('');
  const [serviceId, setServiceId] = useState('');
  const trimmed = name.trim();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!trimmed) return;
    if (await onAdd(trimmed, serviceId || undefined)) {
      setName('');
      setServiceId('');
    }
  };
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-grouped-cell p-4" onSubmit={(e) => void submit(e)}>
      <h3 className="text-headline text-ink-primary">{s('bipe_queue_add_walk_in')}</h3>
      <Field label={s('bipe_agenda_customer_name')}>
        <input
          className={inputClass}
          value={name}
          maxLength={120}
          autoComplete="off"
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      {services.length > 0 && (
        <Field label={s('bipe_agenda_service')}>
          <select className={inputClass} value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
            <option value="">{w('bipe_web_queue_no_service')}</option>
            {services.map((sv) => (
              <option key={sv.id} value={sv.id}>
                {sv.name}
              </option>
            ))}
          </select>
        </Field>
      )}
      <div>
        <PrimaryButton type="submit" disabled={busy || !trimmed}>
          {s('bipe_queue_add_walk_in')}
        </PrimaryButton>
      </div>
    </form>
  );
}

/** Action setWindow: minutes to arrive after a call, 1 to 240. After it passes, bipe-api marks the place no-show. */
function PolicyForm({
  queue,
  busy,
  onSave,
}: {
  queue: Queue;
  busy: boolean;
  onSave: (minutes: number) => Promise<boolean>;
}) {
  const saved = queue.policy.no_show_after_minutes;
  const [draft, setDraft] = useState<string>();
  const text = draft ?? String(saved);
  const value = /^\d+$/.test(text.trim()) ? Number(text.trim()) : NaN;
  const valid = Number.isInteger(value) && value >= MIN_WINDOW && value <= MAX_WINDOW;
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid || value === saved) return;
    if (await onSave(value)) setDraft(undefined);
  };
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-grouped-cell p-4" onSubmit={(e) => void submit(e)}>
      <h3 className="text-headline text-ink-primary">{s('bipe_queue_settings')}</h3>
      <Field label={s('bipe_queue_no_show_window')} hint={w('bipe_web_queue_window_range')}>
        <input
          className={inputClass}
          type="number"
          inputMode="numeric"
          min={MIN_WINDOW}
          max={MAX_WINDOW}
          step={1}
          value={text}
          onChange={(e) => setDraft(e.target.value)}
        />
      </Field>
      <div>
        <PrimaryButton type="submit" disabled={busy || !valid || value === saved}>
          {s('bipe_app_save')}
        </PrimaryButton>
      </div>
    </form>
  );
}

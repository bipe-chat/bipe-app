// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The Agenda of a company account on the web (ADR 0226, bipe-chat/bipe#981): the same specs, strings and
// bipe-api routes as the phone. Before activation: agenda-setup (a template and the time zone). After it: a
// week of bookings (agenda-owner, a week instead of the phone's day), one booking or a form in a side panel
// (agenda-owner-booking, Encaixe, Bloquear horário, Fechar dia), and agenda-settings with the people, hours,
// services and special days that the phone edits in agenda-setup. Times show in the business time zone
// (AgendaState.time_zone), never the browser's: bipe-api takes UTC instants with Z and local YYYY-MM-DD dates.
// The close-day message to customers is not here: it goes through the encrypted chat (ADR 0102), and this
// screen has only the business client.

import { useMemo, useState } from 'react';
import type {
  Agenda,
  AgendaState,
  Availability,
  Booking,
  BookingCustomer,
  BookingStatus,
  Override,
  Policy,
  Service,
  ServiceInput,
  TimeRange,
} from '../bipe/generated/business-api';
import { agendaTemplates } from '../bipe/generated/registries.json';
import type { StringKey } from '../bipe/generated/strings';
import { caretLeft, caretRight, gear, x } from '../phosphor/icons';
import { locale, s } from '../ui/i18n';
import { Icon } from '../ui/Icon';
import { NavBar, Row, Section } from '../ui/List';
import { WorkError, type BusinessClient } from './business-client';
import {
  customerName,
  ErrorLine,
  Field,
  inputClass,
  LoadState,
  OpenChatButton,
  PrimaryButton,
  SecondaryButton,
  SplitPane,
  TextButton,
  useAction,
  useLoad,
  useNames,
  useWorkHost,
  type WorkScreenProps,
} from './common';

// ---- Business time zone ----------------------------------------------------------------------------------------

const MINUTE = 60_000;
const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

const partsFormats = new Map<string, Intl.DateTimeFormat>();
const clockFormats = new Map<string, Intl.DateTimeFormat>();

/** The local date (YYYY-MM-DD) and minute of the day of an instant in timeZone (bipe-api localParts). */
function localParts(timeZone: string, ms: number): { date: string; minute: number } {
  let format = partsFormats.get(timeZone);
  if (!format) {
    format = new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    });
    partsFormats.set(timeZone, format);
  }
  const p: Record<string, string> = {};
  for (const part of format.formatToParts(ms)) p[part.type] = part.value;
  return { date: `${p.year}-${p.month}-${p.day}`, minute: (Number(p.hour) % 24) * 60 + Number(p.minute) };
}

/** A local date and minute read as if it were UTC: the base of the offset arithmetic below. */
function wallMs(date: string, minute: number): number {
  const [y, m, d] = date.split('-').map(Number);
  return Date.UTC(y, m - 1, d, 0, minute);
}

/** The instant of a local wall time in timeZone. Two passes settle the offset around a DST change. */
function zonedToUtc(timeZone: string, date: string, clockText: string): number {
  const wall = wallMs(date, minutesOf(clockText));
  const offset = (ms: number) => {
    const local = localParts(timeZone, ms);
    return wallMs(local.date, local.minute) - Math.floor(ms / MINUTE) * MINUTE;
  };
  return wall - offset(wall - offset(wall));
}

/** UTC with a trailing Z and no milliseconds, as the contract's instants. */
const instant = (ms: number) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** ISO weekday of a local date: Monday 1 to Sunday 7. */
const isoWeekday = (date: string) => new Date(`${date}T00:00:00Z`).getUTCDay() || 7;
const mondayOf = (date: string) => addDays(date, 1 - isoWeekday(date));

const minutesOf = (clockText: string) => Number(clockText.slice(0, 2)) * 60 + Number(clockText.slice(3, 5));
const clock = (minute: number) =>
  `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;

/** A local date in words. The date carries no zone, so it formats at noon UTC. */
function dateText(date: string, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(locale, { ...options, timeZone: 'UTC' }).format(new Date(`${date}T12:00:00Z`));
}

function weekText(monday: string): string {
  const format = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', timeZone: 'UTC' });
  return format.formatRange(new Date(`${monday}T12:00:00Z`), new Date(`${addDays(monday, 6)}T12:00:00Z`));
}

/** The clock time of an instant in the business time zone. */
function clockIn(timeZone: string, ms: number): string {
  let format = clockFormats.get(timeZone);
  if (!format) {
    format = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone });
    clockFormats.set(timeZone, format);
  }
  return format.format(ms);
}

const weekdayName = (isoDay: number) =>
  // 2024-01-01 was a Monday: ISO day 1.
  new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(new Date(2024, 0, isoDay));

/** Ranges of one day: valid times, each end after its start, none overlapping (bipe-api checkRanges). */
function rangesValid(ranges: TimeRange[]): boolean {
  if (ranges.some((r) => !TIME.test(r.start) || !TIME.test(r.end))) return false;
  const sorted = ranges.map((r) => [minutesOf(r.start), minutesOf(r.end)] as const).sort((a, b) => a[0] - b[0]);
  return sorted.every(([start, end], i) => end > start && (i === 0 || start >= sorted[i - 1][1]));
}

const rangesText = (ranges: TimeRange[]) => ranges.map((r) => `${r.start} – ${r.end}`).join(', ');

// ---- Bookings ---------------------------------------------------------------------------------------------------

export const STATUS: Record<BookingStatus, StringKey> = {
  held: 'bipe_agenda_status_held',
  requested: 'bipe_agenda_status_requested',
  confirmed: 'bipe_agenda_status_confirmed',
  done: 'bipe_agenda_status_done',
  cancelled: 'bipe_agenda_status_cancelled',
  no_show: 'bipe_agenda_status_no_show',
};

export const STATUS_TINT: Record<BookingStatus, string> = {
  held: 'text-trust-caution-ink',
  requested: 'text-trust-caution-ink',
  confirmed: 'text-trust-verified-ink',
  done: 'text-ink-secondary',
  cancelled: 'text-trust-danger-ink',
  no_show: 'text-trust-danger-ink',
};

const OPEN: BookingStatus[] = ['held', 'requested', 'confirmed'];

/** A name-only customer shows the name; a signed-in one the profile name (common.tsx customerName). */
const customerText = (c: BookingCustomer) => customerName(c);
/** A web record is name-only with a web mark (ADR 0195 point 17). */
const bookingCustomerText = (b: Booking) => (b.web ? `${customerText(b.customer)} · ${s('bipe_web_record_mark')}` : customerText(b.customer));

/** The agenda errors of bipe-api, as the phone shows them (BipeAPIError.agendaMessage). */
const AGENDA_ERRORS: Record<string, StringKey> = {
  slot_taken: 'bipe_agenda_error_slot_taken',
  hold_expired: 'bipe_agenda_error_hold_expired',
  too_many_open_bookings: 'bipe_agenda_error_too_many_open',
  outside_notice: 'bipe_agenda_error_outside_notice',
  outside_horizon: 'bipe_agenda_error_outside_horizon',
  agenda_not_published: 'bipe_agenda_error_not_published',
  cancellation_window_closed: 'bipe_agenda_error_cancel_window',
};

function agendaErrorText(e: unknown): string {
  if (e instanceof WorkError && e.code === 'network') return s('bipe_error_network');
  return s((e instanceof WorkError && AGENDA_ERRORS[e.code]) || 'bipe_agenda_error_save');
}

function AgendaError({ error, text }: { error: unknown; text?: string }) {
  if (error === undefined) return null;
  return (
    <p role="alert" className="text-footnote text-trust-danger-ink">
      {text ?? agendaErrorText(error)}
    </p>
  );
}

const nameOf = (state: AgendaState) => {
  const agendas = new Map(state.agendas.map((a) => [a.id, a.name]));
  const services = new Map(state.services.map((sv) => [sv.id, sv.name]));
  return { agenda: (id: string) => agendas.get(id) ?? '', service: (id: string) => services.get(id) ?? '' };
};

/** The agendas a service can use; every active one when it names none on this list. */
function agendasFor(state: AgendaState, serviceId: string, keep?: string): Agenda[] {
  const ids = state.services.find((sv) => sv.id === serviceId)?.agenda_ids ?? [];
  const usable = state.agendas.filter((a) => (a.active && (ids.length === 0 || ids.includes(a.id))) || a.id === keep);
  return usable.length ? usable : state.agendas;
}

// ---- Screen ----------------------------------------------------------------------------------------------------

/** GET /v1/me/business/agenda answers not_found (reason agenda_off) before activation: state is then undefined. */
async function loadAgenda(client: BusinessClient): Promise<{ state?: AgendaState }> {
  try {
    return { state: await client.agenda() };
  } catch (e) {
    if (e instanceof WorkError && e.code === 'not_found') return {};
    throw e;
  }
}

export function AgendaScreen({ client, onBack }: WorkScreenProps) {
  useNames();
  const load = useLoad(() => loadAgenda(client), [client]);
  const [settings, setSettings] = useState(false);
  const setState = (state: AgendaState) => load.setData({ state });

  if (!load.data)
    return (
      <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
        <NavBar title={s('bipe_tab_agenda')} onBack={onBack} />
        {load.loading ? (
          <LoadState loading error={undefined} onRetry={load.reload} />
        ) : (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
            <p className="text-footnote text-ink-secondary">{s('bipe_agenda_load_failed')}</p>
            <SecondaryButton onClick={() => void load.reload()}>{s('bipe_app_retry')}</SecondaryButton>
          </div>
        )}
      </div>
    );
  const { state } = load.data;
  if (!state)
    return (
      <SetupScreen
        client={client}
        onBack={onBack}
        onActivated={(activated) => {
          setState(activated);
          // The phone's setup goes on with hours, people and services: on the web they are in the settings.
          setSettings(true);
        }}
      />
    );
  if (settings)
    return (
      <SettingsScreen
        client={client}
        state={state}
        onBack={() => setSettings(false)}
        onState={setState}
        reload={load.reload}
      />
    );
  return <WeekScreen client={client} state={state} onBack={onBack} onSettings={() => setSettings(true)} />;
}

// ---- agenda-setup: pick a template and activate ----------------------------------------------------------------

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Sao_Paulo';
}

function validTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone });
    return timeZone.length > 0;
  } catch {
    return false;
  }
}

function SetupScreen({
  client,
  onBack,
  onActivated,
}: {
  client: BusinessClient;
  onBack: () => void;
  onActivated: (state: AgendaState) => void;
}) {
  const [template, setTemplate] = useState<string>();
  const [timeZone, setTimeZone] = useState(browserTimeZone);
  const zones = useMemo(() => Intl.supportedValuesOf('timeZone'), []);
  const activate = useAction();

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_agenda_activate')} onBack={onBack} />
      <form
        className="mx-auto flex w-full max-w-xl flex-col gap-6 overflow-y-auto px-4 pb-8"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!template) return;
          // Action pickTemplate: the server makes the first agenda and the services as Catálogo items.
          const state = await activate.run(() => client.activateAgenda({ template_id: template, time_zone: timeZone }));
          if (state) onActivated(state);
        }}
      >
        <p className="px-4 text-body text-ink-secondary">{s('bipe_agenda_tab_off_body')}</p>
        <Section header={s('bipe_agenda_pick_template')} footer={s('bipe_agenda_template_hint')}>
          {agendaTemplates.templates.map((t) => (
            <Row key={t.id}>
              <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                <input
                  type="radio"
                  name="template"
                  className="size-5 accent-[var(--color-accent-fill)]"
                  checked={template === t.id}
                  onChange={() => setTemplate(t.id)}
                />
                <span className="min-w-0 flex-1 text-body text-ink-primary">{s(t.title_key as StringKey)}</span>
              </label>
            </Row>
          ))}
        </Section>
        <Field label={s('bipe_agenda_time_zone')}>
          <input
            className={inputClass}
            list="agenda-time-zones"
            value={timeZone}
            onChange={(e) => setTimeZone(e.target.value.trim())}
          />
        </Field>
        <datalist id="agenda-time-zones">
          {zones.map((z) => (
            <option key={z} value={z} />
          ))}
        </datalist>
        <ErrorLine error={activate.error} />
        <div>
          <PrimaryButton type="submit" disabled={!template || !validTimeZone(timeZone) || activate.busy}>
            {s('bipe_agenda_activate')}
          </PrimaryButton>
        </div>
      </form>
    </div>
  );
}

// ---- agenda-owner: the week ------------------------------------------------------------------------------------

type Panel = { kind: 'booking'; booking: Booking } | { kind: 'new' } | { kind: 'block' } | { kind: 'close' };

function WeekScreen({
  client,
  state,
  onBack,
  onSettings,
}: {
  client: BusinessClient;
  state: AgendaState;
  onBack: () => void;
  onSettings: () => void;
}) {
  const timeZone = state.time_zone;
  const today = localParts(timeZone, Date.now()).date;
  const [monday, setMonday] = useState(() => mondayOf(today));
  const [day, setDay] = useState(today);
  const [filter, setFilter] = useState('all');
  const [panel, setPanel] = useState<Panel>();
  const names = nameOf(state);
  const days = [0, 1, 2, 3, 4, 5, 6].map((i) => addDays(monday, i));
  const agendaId = filter === 'all' ? undefined : filter;
  const bookings = useLoad(
    () => client.bookings({ date_from: monday, date_to: addDays(monday, 6), agenda_id: agendaId }),
    [client, monday, agendaId],
  );
  const byDay = useMemo(() => {
    const map = new Map<string, Booking[]>();
    for (const b of bookings.data?.bookings ?? []) {
      const date = localParts(timeZone, Date.parse(b.start)).date;
      map.set(date, [...(map.get(date) ?? []), b]);
    }
    return map;
  }, [bookings.data, timeZone]);
  // Block and close-day act on the agenda on screen, or on every agenda for Todos.
  const onScreen = agendaId ? [agendaId] : state.agendas.map((a) => a.id);

  const goToWeek = (next: string) => {
    setMonday(next);
    setDay(next <= today && today <= addDays(next, 6) ? today : next);
  };
  const changed = (booking?: Booking) => {
    void bookings.reload();
    setPanel(booking ? { kind: 'booking', booking } : undefined);
  };
  const closeLabel = day === today ? s('bipe_agenda_close_today') : s('bipe_web_agenda_close_day');
  const selected = panel?.kind === 'booking' ? panel.booking.id : undefined;

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_tab_agenda')} onBack={onBack} />
      <div className="flex min-h-0 flex-1">
        <div
          className={`@container min-h-0 min-w-0 flex-1 flex-col overflow-y-auto ${panel ? 'hidden min-[900px]:flex' : 'flex'}`}
        >
          <div className="flex flex-wrap items-center gap-2 px-4 pb-2">
            <button
              type="button"
              className="grid size-11 place-items-center rounded-full text-glyph-primary"
              aria-label={s('bipe_web_agenda_prev_week')}
              onClick={() => goToWeek(addDays(monday, -7))}
            >
              <Icon path={caretLeft} size={18} />
            </button>
            <button
              type="button"
              className="grid size-11 place-items-center rounded-full text-glyph-primary"
              aria-label={s('bipe_web_agenda_next_week')}
              onClick={() => goToWeek(addDays(monday, 7))}
            >
              <Icon path={caretRight} size={18} />
            </button>
            <h3 className="min-w-0 text-headline text-ink-primary">{weekText(monday)}</h3>
            <TextButton onClick={() => goToWeek(mondayOf(today))} disabled={monday === mondayOf(today)}>
              {s('bipe_agenda_today')}
            </TextButton>
            {state.agendas.length > 1 && (
              <select
                className={inputClass}
                value={filter}
                aria-label={s('bipe_agenda_person')}
                onChange={(e) => setFilter(e.target.value)}
              >
                <option value="all">{s('bipe_agenda_all_people')}</option>
                {state.agendas.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
            )}
            <span className="flex-1" />
            <TextButton onClick={() => setPanel({ kind: 'new' })}>{s('bipe_agenda_walk_in')}</TextButton>
            <TextButton onClick={() => setPanel({ kind: 'block' })} disabled={onScreen.length === 0}>
              {s('bipe_agenda_block')}
            </TextButton>
            <TextButton onClick={() => setPanel({ kind: 'close' })} disabled={onScreen.length === 0}>
              {closeLabel}
            </TextButton>
            <button
              type="button"
              className="grid size-11 place-items-center rounded-full text-glyph-primary"
              aria-label={s('bipe_agenda_settings_title')}
              onClick={onSettings}
            >
              <Icon path={gear} size={20} />
            </button>
          </div>
          <button type="button" className="px-4 pb-3 text-left text-footnote text-ink-secondary" onClick={onSettings}>
            {state.published ? s('bipe_agenda_published') : s('bipe_agenda_not_published')}
          </button>
          {!bookings.data ? (
            bookings.loading ? (
              <LoadState loading error={undefined} onRetry={bookings.reload} />
            ) : (
              <div className="flex flex-col items-center gap-3 p-8 text-center">
                <p className="text-footnote text-ink-secondary">{s('bipe_agenda_load_failed')}</p>
                <SecondaryButton onClick={() => void bookings.reload()}>{s('bipe_app_retry')}</SecondaryButton>
              </div>
            )
          ) : (
            <>
              {bookings.data.bookings.length === 0 && (
                <p className="px-4 pb-3 text-footnote text-ink-secondary">{s('bipe_web_agenda_empty_week')}</p>
              )}
              <div className="grid grid-cols-1 gap-3 px-4 pb-8 @min-[640px]:grid-cols-7 @min-[640px]:gap-2">
                {days.map((date) => (
                  <div key={date} className="flex min-w-0 flex-col gap-1.5">
                    <button
                      type="button"
                      aria-pressed={date === day}
                      onClick={() => setDay(date)}
                      className={`flex items-baseline gap-1.5 rounded-lg px-2 py-1 text-left ${date === day ? 'bg-accent-wash' : ''}`}
                    >
                      <span className="text-footnote capitalize text-ink-secondary">
                        {dateText(date, { weekday: 'short' })}
                      </span>
                      <span
                        className={`text-headline ${date === today ? 'text-accent-ink' : 'text-ink-primary'}`}
                      >
                        {dateText(date, { day: 'numeric' })}
                      </span>
                    </button>
                    {(byDay.get(date) ?? []).map((b) => (
                      <BookingCard
                        key={b.id}
                        booking={b}
                        timeZone={timeZone}
                        service={names.service(b.service_id)}
                        agenda={!agendaId && state.agendas.length > 1 ? names.agenda(b.agenda_id) : undefined}
                        selected={b.id === selected}
                        onOpen={() => {
                          setDay(date);
                          setPanel({ kind: 'booking', booking: b });
                        }}
                      />
                    ))}
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
        {panel && (
          <aside className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto min-[900px]:w-[380px] min-[900px]:flex-none min-[900px]:border-l-[0.5px] min-[900px]:border-line-subtle">
            {panel.kind === 'booking' ? (
              <BookingPanel
                key={`${panel.booking.id}:${panel.booking.start}:${panel.booking.agenda_id}`}
                client={client}
                state={state}
                booking={panel.booking}
                onClose={() => setPanel(undefined)}
                onChanged={changed}
              />
            ) : panel.kind === 'new' ? (
              <NewBookingPanel
                client={client}
                state={state}
                day={day}
                agendaId={agendaId}
                onClose={() => setPanel(undefined)}
                onChanged={changed}
              />
            ) : panel.kind === 'block' ? (
              <BlockPanel
                client={client}
                state={state}
                day={day}
                agendaIds={onScreen}
                place={agendaId ? names.agenda(agendaId) : s('bipe_agenda_all_people')}
                onClose={() => setPanel(undefined)}
                onChanged={() => changed()}
              />
            ) : (
              <ClosePanel
                client={client}
                state={state}
                day={day}
                agendaIds={onScreen}
                place={agendaId ? names.agenda(agendaId) : s('bipe_agenda_all_people')}
                onClose={() => setPanel(undefined)}
                onChanged={() => changed()}
              />
            )}
          </aside>
        )}
      </div>
    </div>
  );
}

function BookingCard({
  booking,
  timeZone,
  service,
  agenda,
  selected,
  onOpen,
}: {
  booking: Booking;
  timeZone: string;
  service: string;
  agenda?: string;
  selected: boolean;
  onOpen: () => void;
}) {
  // Cancelled and no-show bookings show dimmed (spec agenda-owner).
  const dim = booking.status === 'cancelled' || booking.status === 'no_show';
  return (
    <button
      type="button"
      onClick={onOpen}
      className={`flex min-w-0 flex-col rounded-lg bg-grouped-cell px-2 py-1.5 text-left ${dim ? 'opacity-50' : ''} ${selected ? 'outline-2 outline-accent-fill' : ''}`}
    >
      <span className="text-footnote font-semibold text-ink-primary">{clockIn(timeZone, Date.parse(booking.start))}</span>
      <span className="truncate text-footnote text-ink-primary">{service}</span>
      <span className="truncate text-caption text-ink-secondary">{bookingCustomerText(booking)}</span>
      {agenda && <span className="truncate text-caption text-ink-secondary">{agenda}</span>}
      {booking.status !== 'confirmed' && (
        <span className={`truncate text-caption ${STATUS_TINT[booking.status]}`}>{s(STATUS[booking.status])}</span>
      )}
    </button>
  );
}

function PanelHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="flex items-center gap-2 pt-2">
      <h3 className="min-w-0 flex-1 truncate text-headline text-ink-primary">{title}</h3>
      <button
        type="button"
        className="grid size-11 place-items-center rounded-full text-glyph-primary"
        aria-label={s('bipe_app_close')}
        onClick={onClose}
      >
        <Icon path={x} size={18} />
      </button>
    </div>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <Row>
      <span className="min-w-0 flex-1 text-body text-ink-primary">{label}</span>
      <span className="min-w-0 truncate text-body text-ink-secondary">{value}</span>
    </Row>
  );
}

/** Next quarter hour today, else 9:00 (AgendaOwnerBookingView.fill). */
function defaultClock(timeZone: string, date: string, step: number): string {
  const now = localParts(timeZone, Date.now());
  if (date !== now.date) return '09:00';
  return clock(Math.min(Math.ceil(now.minute / step) * step, 1440 - step));
}

// ---- agenda-owner-booking: one booking ---------------------------------------------------------------------------

export function BookingPanel({
  client,
  state,
  booking,
  onClose,
  onChanged,
}: {
  client: BusinessClient;
  state: AgendaState;
  booking: Booking;
  onClose: () => void;
  onChanged: (booking: Booking) => void;
}) {
  const timeZone = state.time_zone;
  const names = nameOf(state);
  const action = useAction();
  const [moving, setMoving] = useState(false);
  const [confirmsCancel, setConfirmsCancel] = useState(false);
  const start = Date.parse(booking.start);
  const local = localParts(timeZone, start);
  const [agendaId, setAgendaId] = useState(booking.agenda_id);
  const [date, setDate] = useState(local.date);
  const [time, setTime] = useState(clock(local.minute));

  const run = async (fn: () => Promise<Booking>) => {
    const result = await action.run(fn);
    if (result) {
      setMoving(false);
      setConfirmsCancel(false);
      onChanged(result);
    }
  };

  if (moving)
    return (
      <form
        className="flex flex-col gap-4 px-4 pb-8"
        onSubmit={(e) => {
          e.preventDefault();
          // Action save, existing: POST .../bookings/{id}/move with the new start and agenda.
          void run(() =>
            client.moveBooking(booking.id, { agenda_id: agendaId, start: instant(zonedToUtc(timeZone, date, time)) }),
          );
        }}
      >
        <PanelHeader title={s('bipe_agenda_reschedule')} onClose={() => setMoving(false)} />
        <Field label={s('bipe_agenda_person')}>
          <select className={inputClass} value={agendaId} onChange={(e) => setAgendaId(e.target.value)}>
            {agendasFor(state, booking.service_id, booking.agenda_id).map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label={s('bipe_agenda_date')}>
          <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <Field label={s('bipe_agenda_time')}>
          <input type="time" className={inputClass} value={time} onChange={(e) => setTime(e.target.value)} />
        </Field>
        <AgendaError error={action.error} />
        <div className="flex gap-2">
          <PrimaryButton type="submit" disabled={action.busy || !date || !TIME.test(time)}>
            {s('bipe_app_save')}
          </PrimaryButton>
          <SecondaryButton onClick={() => setMoving(false)}>{s('bipe_app_cancel')}</SecondaryButton>
        </div>
      </form>
    );

  const now = Date.now();
  const status = booking.status;
  return (
    <div className="flex flex-col gap-4 px-4 pb-8">
      <PanelHeader title={names.service(booking.service_id)} onClose={onClose} />
      <Section>
        <Detail label={s('bipe_agenda_service')} value={names.service(booking.service_id)} />
        <Detail label={s('bipe_agenda_person')} value={names.agenda(booking.agenda_id)} />
        <Detail
          label={s('bipe_agenda_date')}
          value={dateText(local.date, { weekday: 'short', day: 'numeric', month: 'short' })}
        />
        <Detail
          label={s('bipe_agenda_time')}
          value={`${clockIn(timeZone, start)} – ${clockIn(timeZone, Date.parse(booking.end))}`}
        />
        <Detail label={s('bipe_agenda_customer')} value={bookingCustomerText(booking)} />
        <Row>
          <span className={`text-subheadline font-semibold ${STATUS_TINT[status]}`}>{s(STATUS[status])}</span>
        </Row>
      </Section>
      {confirmsCancel ? (
        <div className="flex flex-col gap-3 rounded-xl bg-grouped-cell p-4">
          <p className="text-headline text-ink-primary">{s('bipe_agenda_cancel_confirm')}</p>
          <p className="text-footnote text-ink-secondary">{s('bipe_agenda_cancel_confirm_body')}</p>
          <div className="flex flex-wrap gap-2">
            <TextButton danger disabled={action.busy} onClick={() => void run(() => client.bookingAction(booking.id, 'cancel'))}>
              {s('bipe_agenda_cancel')}
            </TextButton>
            <TextButton onClick={() => setConfirmsCancel(false)}>{s('bipe_agenda_keep')}</TextButton>
          </div>
        </div>
      ) : (
        <div className="flex flex-col items-start">
          <OpenChatButton customer={booking.customer} />
          {status === 'requested' && (
            <>
              <TextButton disabled={action.busy} onClick={() => void run(() => client.bookingAction(booking.id, 'accept'))}>
                {s('bipe_agenda_accept')}
              </TextButton>
              <TextButton
                danger
                disabled={action.busy}
                onClick={() => void run(() => client.bookingAction(booking.id, 'refuse'))}
              >
                {s('bipe_agenda_decline')}
              </TextButton>
            </>
          )}
          {(status === 'requested' || status === 'confirmed') && (
            <TextButton disabled={action.busy} onClick={() => setMoving(true)}>
              {s('bipe_agenda_reschedule')}
            </TextButton>
          )}
          {(status === 'confirmed' || status === 'done') && start <= now && (
            <TextButton
              disabled={action.busy}
              onClick={() => void run(() => client.bookingAction(booking.id, 'no-show'))}
            >
              {s('bipe_agenda_mark_no_show')}
            </TextButton>
          )}
          {OPEN.includes(status) && Date.parse(booking.end) > now && (
            <TextButton danger disabled={action.busy} onClick={() => setConfirmsCancel(true)}>
              {s('bipe_agenda_cancel')}
            </TextButton>
          )}
        </div>
      )}
      <AgendaError error={action.error} />
    </div>
  );
}

// ---- Encaixe, Bloquear horário, Fechar dia -----------------------------------------------------------------------

type PanelProps = {
  client: BusinessClient;
  state: AgendaState;
  day: string;
  onClose: () => void;
};

/** Action addWalkIn: a name-only booking (source owner). The owner can book outside the published hours. */
function NewBookingPanel({
  client,
  state,
  day,
  agendaId: shownAgenda,
  onClose,
  onChanged,
}: PanelProps & { agendaId?: string; onChanged: (booking: Booking) => void }) {
  const timeZone = state.time_zone;
  const services = state.services.filter((sv) => sv.active);
  const [serviceId, setServiceId] = useState(services[0]?.id ?? '');
  const firstAgenda = (sid: string, prefer?: string) => {
    const usable = agendasFor(state, sid);
    return usable.find((a) => a.id === prefer)?.id ?? usable[0]?.id ?? '';
  };
  const [agendaId, setAgendaId] = useState(() => firstAgenda(serviceId, shownAgenda));
  const [date, setDate] = useState(day);
  const [time, setTime] = useState(() => defaultClock(timeZone, day, 15));
  const [name, setName] = useState('');
  const save = useAction();
  const canSave = !save.busy && serviceId && agendaId && date && TIME.test(time) && name.trim().length > 0;

  return (
    <form
      className="flex flex-col gap-4 px-4 pb-8"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!canSave) return;
        const booking = await save.run(() =>
          client.book({
            service_id: serviceId,
            agenda_id: agendaId,
            start: instant(zonedToUtc(timeZone, date, time)),
            customer: { name: name.trim() },
          }),
        );
        if (booking) onChanged(booking);
      }}
    >
      <PanelHeader title={s('bipe_agenda_walk_in')} onClose={onClose} />
      {services.length === 0 ? (
        <p className="text-footnote text-ink-secondary">{s('bipe_web_agenda_no_services')}</p>
      ) : (
        <>
          <Field label={s('bipe_agenda_service')}>
            <select
              className={inputClass}
              value={serviceId}
              onChange={(e) => {
                setServiceId(e.target.value);
                setAgendaId(firstAgenda(e.target.value, agendaId));
              }}
            >
              {services.map((sv) => (
                <option key={sv.id} value={sv.id}>
                  {sv.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label={s('bipe_agenda_person')}>
            <select className={inputClass} value={agendaId} onChange={(e) => setAgendaId(e.target.value)}>
              {agendasFor(state, serviceId).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label={s('bipe_agenda_date')}>
            <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label={s('bipe_agenda_time')}>
            <input type="time" className={inputClass} value={time} onChange={(e) => setTime(e.target.value)} />
          </Field>
          <Field label={s('bipe_agenda_customer_name')} hint={s('bipe_agenda_walk_in_hint')}>
            <input className={inputClass} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
          </Field>
          <AgendaError error={save.error} />
          <div>
            <PrimaryButton type="submit" disabled={!canSave}>
              {s('bipe_app_save')}
            </PrimaryButton>
          </div>
        </>
      )}
    </form>
  );
}

/** Action block: a start and an end on one day, for the agenda on screen or every agenda. */
function BlockPanel({
  client,
  state,
  day,
  agendaIds,
  place,
  onClose,
  onChanged,
}: PanelProps & { agendaIds: string[]; place: string; onChanged: () => void }) {
  const timeZone = state.time_zone;
  const [date, setDate] = useState(day);
  // The next hour today (or 9:00 on another day), for one hour, as on the phone.
  const [start, setStart] = useState(() => defaultClock(timeZone, day, 60));
  const [end, setEnd] = useState(() => clock(Math.min(minutesOf(defaultClock(timeZone, day, 60)) + 60, 1439)));
  const save = useAction();
  const valid = !!date && TIME.test(start) && TIME.test(end) && minutesOf(end) > minutesOf(start);

  return (
    <form
      className="flex flex-col gap-4 px-4 pb-8"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!valid) return;
        const done = await save.run(() =>
          client.block({
            agenda_ids: agendaIds,
            start: instant(zonedToUtc(timeZone, date, start)),
            end: instant(zonedToUtc(timeZone, date, end)),
          }),
        );
        if (done) onChanged();
      }}
    >
      <PanelHeader title={s('bipe_agenda_block')} onClose={onClose} />
      <p className="text-footnote text-ink-secondary">{place}</p>
      <Field label={s('bipe_agenda_date')}>
        <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
      </Field>
      <div className="flex gap-2">
        <Field label={s('bipe_web_agenda_start')}>
          <input type="time" className={inputClass} value={start} onChange={(e) => setStart(e.target.value)} />
        </Field>
        <Field label={s('bipe_web_agenda_end')}>
          <input type="time" className={inputClass} value={end} onChange={(e) => setEnd(e.target.value)} />
        </Field>
      </div>
      <AgendaError error={save.error} />
      <div>
        <PrimaryButton type="submit" disabled={!valid || save.busy}>
          {s('bipe_app_save')}
        </PrimaryButton>
      </div>
    </form>
  );
}

/**
 * Action closeToday, for any day of the week: closed overrides; the bookings stay until the owner acts. Then the
 * owner edits bipe_agenda_close_today_message and sends it into the DM with each signed-in customer of that day's
 * open bookings, from this browser (ADR 0102, AgendaOwnerModel.send on the phone).
 */
function ClosePanel({
  client,
  state,
  day,
  agendaIds,
  place,
  onClose,
  onChanged,
}: PanelProps & { agendaIds: string[]; place: string; onChanged: () => void }) {
  const today = localParts(state.time_zone, Date.now()).date;
  const [date, setDate] = useState(day);
  const save = useAction();
  const host = useWorkHost();
  const [customers, setCustomers] = useState<string[]>();
  const [text, setText] = useState(s('bipe_agenda_close_today_message'));
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState<number>();
  const title = date === today ? s('bipe_agenda_close_today') : s('bipe_web_agenda_close_day');

  const close = async () => {
    const found = await save.run(async () => {
      await client.closeDay({ agenda_ids: agendaIds, date });
      if (!host) return [];
      // The message goes to the open bookings of that day; a failed read only skips the message.
      const day = await client
        .bookings({ date_from: date, date_to: date, agenda_id: agendaIds.length === 1 ? agendaIds[0] : undefined })
        .catch(() => ({ bookings: [] as Booking[] }));
      const ids = day.bookings
        .filter((b) => OPEN.includes(b.status) && agendaIds.includes(b.agenda_id))
        .flatMap((b) => ('user_id' in b.customer ? [b.customer.user_id] : []));
      return [...new Set(ids)];
    });
    if (!found) return;
    if (found.length === 0) onChanged();
    else setCustomers(found);
  };

  const send = async () => {
    const body = text.trim();
    if (!host || !customers || !body) return;
    setSending(true);
    let count = 0;
    for (const userId of customers) if (await host.sendText(userId, body)) count += 1;
    setSending(false);
    setSent(count);
  };

  if (customers) {
    return (
      <div className="flex flex-col gap-4 px-4 pb-8">
        <PanelHeader title={title} onClose={onChanged} />
        {sent === undefined ? (
          <>
            <Field label={s('bipe_agenda_close_today_send')}>
              <textarea
                className={`${inputClass} min-h-28 py-2`}
                value={text}
                disabled={sending}
                onChange={(e) => setText(e.target.value)}
              />
            </Field>
            <div className="flex gap-2">
              <PrimaryButton disabled={sending || !text.trim()} onClick={() => void send()}>
                {s('bipe_agenda_close_today_send')}
              </PrimaryButton>
              <SecondaryButton disabled={sending} onClick={onChanged}>
                {s('bipe_app_cancel')}
              </SecondaryButton>
            </div>
          </>
        ) : (
          <>
            <p className="text-body text-ink-primary">{s('bipe_agenda_close_today_sent', String(sent))}</p>
            <div>
              <PrimaryButton onClick={onChanged}>{s('bipe_app_close')}</PrimaryButton>
            </div>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 px-4 pb-8">
      <PanelHeader title={title} onClose={onClose} />
      <p className="text-footnote text-ink-secondary">{place}</p>
      <Field label={s('bipe_agenda_date')}>
        <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
      </Field>
      <p className="text-body text-ink-primary">{s('bipe_agenda_close_today_body')}</p>
      <AgendaError error={save.error} />
      <div className="flex gap-2">
        <PrimaryButton disabled={!date || save.busy} onClick={() => void close()}>
          {title}
        </PrimaryButton>
        <SecondaryButton onClick={onClose}>{s('bipe_app_cancel')}</SecondaryButton>
      </div>
    </div>
  );
}

// ---- agenda-settings ---------------------------------------------------------------------------------------------

type SettingsPage = 'general' | 'people' | 'hours' | 'services' | 'overrides';

type SettingsProps = {
  client: BusinessClient;
  state: AgendaState;
  onState: (state: AgendaState) => void;
  reload: () => Promise<void>;
};

function SettingsScreen({ onBack, ...props }: SettingsProps & { onBack: () => void }) {
  // On a wide screen the first page shows next to the list; on a narrow one the list comes first.
  const [page, setPage] = useState<SettingsPage | undefined>(() =>
    window.matchMedia('(min-width: 900px)').matches ? 'general' : undefined,
  );
  const pages: { page: SettingsPage; title: string }[] = [
    { page: 'general', title: s('bipe_web_agenda_rules') },
    { page: 'people', title: s('bipe_agenda_setup_people') },
    { page: 'hours', title: s('bipe_agenda_setup_hours') },
    { page: 'services', title: s('bipe_web_agenda_services') },
    { page: 'overrides', title: s('bipe_web_agenda_overrides') },
  ];
  const title = pages.find((p) => p.page === page)?.title ?? '';

  const list = (
    <div className="flex flex-col gap-6 px-4 py-4">
      <Section footer={props.state.published ? undefined : s('bipe_agenda_template_hint')}>
        {pages.map((p) => (
          <Row key={p.page}>
            <button
              type="button"
              aria-current={p.page === page}
              className="flex min-w-0 flex-1 items-center gap-2 py-1 text-left"
              onClick={() => setPage(p.page)}
            >
              <span className={`min-w-0 flex-1 truncate text-body ${p.page === page ? 'text-accent-ink' : 'text-ink-primary'}`}>
                {p.title}
              </span>
              <Icon path={caretRight} size={16} className="text-glyph-secondary" />
            </button>
          </Row>
        ))}
      </Section>
    </div>
  );

  const detail = page && (
    <div className="flex flex-col gap-6 px-4 pb-8 pt-2">
      <PanelHeader title={title} onClose={() => setPage(undefined)} />
      {page === 'general' ? (
        <GeneralPage {...props} />
      ) : page === 'people' ? (
        <PeoplePage {...props} />
      ) : page === 'hours' ? (
        <HoursPage {...props} />
      ) : page === 'services' ? (
        <ServicesPage {...props} />
      ) : (
        <OverridesPage {...props} />
      )}
    </div>
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_agenda_settings_title')} onBack={onBack} />
      <SplitPane list={list} detail={detail} />
    </div>
  );
}

function Switch({ label, checked, disabled, onChange }: { label: string; checked: boolean; disabled?: boolean; onChange: (on: boolean) => void }) {
  return (
    <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
      <span className="min-w-0 flex-1 text-body text-ink-primary">{label}</span>
      <input
        type="checkbox"
        role="switch"
        className="size-5 accent-[var(--color-accent-fill)]"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
    </label>
  );
}

const POLICY_FIELDS = [
  { key: 'min_notice_minutes', label: 'bipe_agenda_min_notice', min: 0, max: 43_200 },
  { key: 'horizon_days', label: 'bipe_agenda_horizon', min: 1, max: 365 },
  { key: 'slot_step_minutes', label: 'bipe_agenda_slot_step', min: 5, max: 240 },
  { key: 'cancel_window_minutes', label: 'bipe_agenda_cancel_window', min: 0, max: 43_200 },
  { key: 'max_open_bookings_per_customer', label: 'bipe_agenda_max_open', min: 1, max: 20 },
] as const;

type PolicyNumber = (typeof POLICY_FIELDS)[number]['key'];

/** A whole number within the contract's limits, else undefined. */
function intIn(text: string, min: number, max: number): number | undefined {
  if (!/^\d+$/.test(text.trim())) return undefined;
  const n = Number(text);
  return n >= min && n <= max ? n : undefined;
}

/** Publish switch, time zone and the booking policy (spec agenda-settings). */
function GeneralPage({ client, state, onState }: SettingsProps) {
  const publish = useAction();
  const save = useAction();
  const [draft, setDraft] = useState(() =>
    Object.fromEntries(POLICY_FIELDS.map((f) => [f.key, String(state.policy[f.key])])) as Record<PolicyNumber, string>,
  );
  const [requestMode, setRequestMode] = useState(state.policy.confirm_mode === 'request');
  const [saved, setSaved] = useState(false);

  // Every field within its limits; reminder_minutes stays as read.
  const edited = useMemo((): Policy | undefined => {
    const policy: Policy = { ...state.policy, confirm_mode: requestMode ? 'request' : 'auto' };
    for (const f of POLICY_FIELDS) {
      const n = intIn(draft[f.key], f.min, f.max);
      if (n === undefined) return undefined;
      policy[f.key] = n;
    }
    return policy;
  }, [draft, requestMode, state.policy]);
  const changed = edited !== undefined && JSON.stringify(edited) !== JSON.stringify(state.policy);

  // A failed switch leaves published as it was: a failure while it is off was a publish.
  const publishError =
    publish.error instanceof WorkError && publish.error.code === 'bad_request' && !state.published
      ? s('bipe_agenda_publish_incomplete')
      : undefined;

  return (
    <>
      <Section footer={state.published ? s('bipe_agenda_published') : s('bipe_agenda_not_published')}>
        <Row>
          <Switch
            label={s('bipe_agenda_publish')}
            checked={state.published}
            disabled={publish.busy}
            onChange={async (on) => {
              const next = await publish.run(() =>
                client.saveAgendaSettings({ published: on, time_zone: state.time_zone }),
              );
              if (next) onState(next);
            }}
          />
        </Row>
        <Detail label={s('bipe_agenda_time_zone')} value={state.time_zone} />
      </Section>
      <AgendaError error={publish.error} text={publishError} />
      <form
        className="flex flex-col gap-6"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!edited) return;
          setSaved(false);
          const policy = await save.run(() => client.setAgendaPolicy(edited));
          if (policy) {
            onState({ ...state, policy });
            setSaved(true);
          }
        }}
      >
        <div className="flex flex-col gap-3">
          {POLICY_FIELDS.map((f) => (
            <Field key={f.key} label={s(f.label)}>
              <input
                type="number"
                inputMode="numeric"
                min={f.min}
                max={f.max}
                className={inputClass}
                value={draft[f.key]}
                onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}
              />
            </Field>
          ))}
        </div>
        <Section footer={s('bipe_agenda_request_mode_footer')}>
          <Row>
            <Switch label={s('bipe_agenda_request_mode')} checked={requestMode} onChange={setRequestMode} />
          </Row>
        </Section>
        <AgendaError error={save.error} />
        {saved && !changed && <p className="text-footnote text-ink-secondary">{s('bipe_web_agenda_saved')}</p>}
        <div>
          <PrimaryButton type="submit" disabled={!changed || save.busy}>
            {s('bipe_app_save')}
          </PrimaryButton>
        </div>
      </form>
    </>
  );
}

/** People and stations: add, rename, take out of booking, remove (bookings keep the agenda). */
function PeoplePage({ client, state, reload }: SettingsProps) {
  const add = useAction();
  const [name, setName] = useState('');
  const nextSort = Math.max(-1, ...state.agendas.map((a) => a.sort)) + 1;
  return (
    <>
      {state.agendas.length > 0 && (
        <Section>
          {state.agendas.map((a) => (
            <PersonRow key={`${a.id}:${a.name}:${a.active}`} client={client} agenda={a} reload={reload} />
          ))}
        </Section>
      )}
      <form
        className="flex flex-col gap-3"
        onSubmit={async (e) => {
          e.preventDefault();
          const trimmed = name.trim();
          if (!trimmed) return;
          // A new person starts with the first person's hours (bipe-api createAgenda).
          const made = await add.run(() => client.createAgenda({ name: trimmed, active: true, sort: nextSort }));
          if (made) {
            setName('');
            await reload();
          }
        }}
      >
        <Field label={s('bipe_agenda_person_name')}>
          <input className={inputClass} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
        </Field>
        <AgendaError error={add.error} />
        <div>
          <PrimaryButton type="submit" disabled={!name.trim() || add.busy}>
            {s('bipe_agenda_add_person')}
          </PrimaryButton>
        </div>
      </form>
    </>
  );
}

function PersonRow({ client, agenda, reload }: { client: BusinessClient; agenda: Agenda; reload: () => Promise<void> }) {
  const action = useAction();
  const [name, setName] = useState(agenda.name);
  const [confirmsRemove, setConfirmsRemove] = useState(false);
  const replace = async (next: { name: string; active: boolean }) => {
    const done = await action.run(() =>
      client.replaceAgenda(agenda.id, {
        name: next.name,
        active: next.active,
        sort: agenda.sort,
        ...(agenda.photo ? { photo: agenda.photo } : {}),
      }),
    );
    if (done) await reload();
  };
  return (
    <li className="flex flex-col gap-2 px-4 py-3 [&:not(:last-child)]:border-b-[0.5px] [&:not(:last-child)]:border-line-subtle">
      <div className="flex items-center gap-2">
        <input
          className={`${inputClass} min-w-0 flex-1 bg-surface-sunken`}
          aria-label={s('bipe_agenda_person_name')}
          value={name}
          maxLength={120}
          onChange={(e) => setName(e.target.value)}
          onBlur={() => {
            const trimmed = name.trim();
            if (trimmed && trimmed !== agenda.name) void replace({ name: trimmed, active: agenda.active });
            else setName(agenda.name);
          }}
        />
        <label className="flex items-center gap-2 text-footnote text-ink-secondary">
          <input
            type="checkbox"
            role="switch"
            className="size-5 accent-[var(--color-accent-fill)]"
            checked={agenda.active}
            disabled={action.busy}
            onChange={(e) => void replace({ name: agenda.name, active: e.target.checked })}
          />
          {s('bipe_web_agenda_takes_bookings')}
        </label>
      </div>
      {confirmsRemove ? (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-footnote text-ink-secondary">{s('bipe_web_agenda_remove_person_body')}</span>
          <TextButton
            danger
            disabled={action.busy}
            onClick={async () => {
              const done = await action.run(async () => {
                await client.deleteAgenda(agenda.id);
                return true;
              });
              if (done) await reload();
            }}
          >
            {s('bipe_agenda_remove_person')}
          </TextButton>
          <TextButton onClick={() => setConfirmsRemove(false)}>{s('bipe_app_cancel')}</TextButton>
        </div>
      ) : (
        <div>
          <TextButton danger onClick={() => setConfirmsRemove(true)}>
            {s('bipe_agenda_remove_person')}
          </TextButton>
        </div>
      )}
      <AgendaError error={action.error} />
    </li>
  );
}

/** A day's ranges: several per day for a lunch break (bipe_agenda_add_range). */
function RangesEditor({ ranges, onChange }: { ranges: TimeRange[]; onChange: (ranges: TimeRange[]) => void }) {
  const next = nextRange(ranges);
  return (
    <div className="flex flex-col gap-2">
      {ranges.map((r, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            type="time"
            className={inputClass}
            aria-label={s('bipe_web_agenda_start')}
            value={r.start}
            onChange={(e) => onChange(ranges.map((o, j) => (j === i ? { ...o, start: e.target.value } : o)))}
          />
          <input
            type="time"
            className={inputClass}
            aria-label={s('bipe_web_agenda_end')}
            value={r.end}
            onChange={(e) => onChange(ranges.map((o, j) => (j === i ? { ...o, end: e.target.value } : o)))}
          />
          <button
            type="button"
            className="grid size-11 place-items-center rounded-full text-glyph-secondary"
            aria-label={s('bipe_web_work_remove')}
            onClick={() => onChange(ranges.filter((_, j) => j !== i))}
          >
            <Icon path={x} size={16} />
          </button>
        </div>
      ))}
      <div>
        <TextButton disabled={!next} onClick={() => next && onChange([...ranges, next])}>
          {s('bipe_agenda_add_range')}
        </TextButton>
      </div>
    </div>
  );
}

/** 9:00 to 18:00 on an empty day, else the hour after the last range. */
function nextRange(ranges: TimeRange[]): TimeRange | undefined {
  if (ranges.length === 0) return { start: '09:00', end: '18:00' };
  if (ranges.some((r) => !TIME.test(r.end))) return undefined;
  const last = Math.max(...ranges.map((r) => minutesOf(r.end)));
  return last < 1439 ? { start: clock(last), end: clock(Math.min(last + 60, 1439)) } : undefined;
}

/** Weekly hours per agenda: PUT .../agendas/{id}/availability with every rule. */
function HoursPage({ client, state }: SettingsProps) {
  const [agendaId, setAgendaId] = useState(state.agendas[0]?.id ?? '');
  if (!agendaId) return <p className="text-footnote text-ink-secondary">{s('bipe_agenda_add_person')}</p>;
  return (
    <>
      {state.agendas.length > 1 && (
        <Field label={s('bipe_agenda_person')}>
          <select className={inputClass} value={agendaId} onChange={(e) => setAgendaId(e.target.value)}>
            {state.agendas.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </Field>
      )}
      <WeekHours key={agendaId} client={client} agendaId={agendaId} />
    </>
  );
}

function WeekHours({ client, agendaId }: { client: BusinessClient; agendaId: string }) {
  const load = useLoad(() => client.availability(agendaId), [client, agendaId]);
  const save = useAction();
  const [draft, setDraft] = useState<Availability[]>();
  const rules = draft ?? load.data?.rules;
  if (!rules) return <LoadState loading={load.loading} error={load.error} onRetry={load.reload} />;

  const dayRanges = (weekday: number) =>
    rules.filter((r) => r.weekday === weekday).map(({ start, end }) => ({ start, end }));
  const setDay = (weekday: number, ranges: TimeRange[]) =>
    setDraft([...rules.filter((r) => r.weekday !== weekday), ...ranges.map((r) => ({ weekday, ...r }))]);
  const valid = [1, 2, 3, 4, 5, 6, 7].every((d) => rangesValid(dayRanges(d)));

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!valid) return;
        const saved = await save.run(() => client.setAvailability(agendaId, rules));
        if (saved) {
          load.setData(saved);
          setDraft(undefined);
        }
      }}
    >
      <Section>
        {[1, 2, 3, 4, 5, 6, 7].map((weekday) => {
          const ranges = dayRanges(weekday);
          return (
            <li
              key={weekday}
              className="flex flex-col gap-2 px-4 py-3 [&:not(:last-child)]:border-b-[0.5px] [&:not(:last-child)]:border-line-subtle"
            >
              <div className="flex items-center gap-3">
                <span className="min-w-0 flex-1 text-body capitalize text-ink-primary">{weekdayName(weekday)}</span>
                <label className="flex items-center gap-2 text-footnote text-ink-secondary">
                  <input
                    type="checkbox"
                    checked={ranges.length === 0}
                    onChange={(e) => setDay(weekday, e.target.checked ? [] : [{ start: '09:00', end: '18:00' }])}
                  />
                  {s('bipe_agenda_closed')}
                </label>
              </div>
              {ranges.length > 0 && <RangesEditor ranges={ranges} onChange={(next) => setDay(weekday, next)} />}
              {!rangesValid(ranges) && (
                <p className="text-footnote text-trust-danger-ink">{s('bipe_web_agenda_ranges_invalid')}</p>
              )}
            </li>
          );
        })}
      </Section>
      <AgendaError error={save.error} />
      <div>
        <PrimaryButton type="submit" disabled={!valid || draft === undefined || save.busy}>
          {s('bipe_app_save')}
        </PrimaryButton>
      </div>
    </form>
  );
}

/** Services: Catálogo items of kind service with duration, buffers and agendas. Price and text stay in Catálogo. */
function ServicesPage({ client, state, reload }: SettingsProps) {
  const [editing, setEditing] = useState<Service | 'new'>();
  if (editing)
    return (
      <ServiceEditor
        key={editing === 'new' ? 'new' : editing.id}
        client={client}
        state={state}
        service={editing === 'new' ? undefined : editing}
        onDone={async (changed) => {
          setEditing(undefined);
          if (changed) await reload();
        }}
      />
    );
  return (
    <>
      {state.services.length > 0 && (
        <Section>
          {state.services.map((sv) => (
            <Row key={sv.id}>
              <button
                type="button"
                className={`flex min-w-0 flex-1 items-center gap-2 py-1 text-left ${sv.active ? '' : 'opacity-50'}`}
                onClick={() => setEditing(sv)}
              >
                <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{sv.name}</span>
                <span className="text-footnote text-ink-secondary">
                  {s('bipe_agenda_duration', String(sv.duration_minutes))}
                </span>
                <Icon path={caretRight} size={16} className="text-glyph-secondary" />
              </button>
            </Row>
          ))}
        </Section>
      )}
      <div>
        <PrimaryButton onClick={() => setEditing('new')} disabled={state.agendas.length === 0}>
          {s('bipe_agenda_add_service')}
        </PrimaryButton>
      </div>
    </>
  );
}

function ServiceEditor({
  client,
  state,
  service,
  onDone,
}: {
  client: BusinessClient;
  state: AgendaState;
  service?: Service;
  onDone: (changed: boolean) => void;
}) {
  const save = useAction();
  const [name, setName] = useState(service?.name ?? '');
  const [duration, setDuration] = useState(String(service?.duration_minutes ?? 30));
  const [before, setBefore] = useState(String(service?.buffer_before_minutes ?? 0));
  const [after, setAfter] = useState(String(service?.buffer_after_minutes ?? 0));
  const [agendaIds, setAgendaIds] = useState<string[]>(
    service?.agenda_ids ?? (state.agendas[0] ? [state.agendas[0].id] : []),
  );
  const [active, setActive] = useState(service?.active ?? true);
  const [confirmsRemove, setConfirmsRemove] = useState(false);

  const minutes = {
    duration: intIn(duration, 1, 1440),
    before: intIn(before, 0, 1440),
    after: intIn(after, 0, 1440),
  };
  const input = ((): ServiceInput | undefined => {
    const trimmed = name.trim();
    const { duration: d, before: b, after: a } = minutes;
    if (!trimmed || d === undefined || b === undefined || a === undefined || agendaIds.length === 0) return undefined;
    const scheduling = { duration_minutes: d, buffer_before_minutes: b, buffer_after_minutes: a, agenda_ids: agendaIds };
    if (!service)
      return {
        kind: 'service',
        name: trimmed,
        price: { mode: 'on_request' },
        active,
        sort: Math.max(-1, ...state.services.map((sv) => sv.sort)) + 1,
        ...scheduling,
      };
    // A full replacement: every Catálogo field the screen does not edit goes back as read.
    return {
      kind: 'service',
      name: trimmed,
      price: service.price,
      ...(service.description !== undefined ? { description: service.description } : {}),
      ...(service.category !== undefined ? { category: service.category } : {}),
      ...(service.sold_out_today !== undefined ? { sold_out_today: service.sold_out_today } : {}),
      active,
      sort: service.sort,
      ...scheduling,
    };
  })();

  const toggleAgenda = (id: string, on: boolean) =>
    setAgendaIds(on ? [...agendaIds, id] : agendaIds.filter((a) => a !== id));

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!input) return;
        const done = await save.run(() => (service ? client.replaceService(service.id, input) : client.createService(input)));
        if (done) onDone(true);
      }}
    >
      <Field label={s('bipe_catalog_item_name')}>
        <input className={inputClass} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label={s('bipe_agenda_service_duration')}>
        <input type="number" min={1} max={1440} className={inputClass} value={duration} onChange={(e) => setDuration(e.target.value)} />
      </Field>
      <Field label={s('bipe_agenda_buffer_before')}>
        <input type="number" min={0} max={1440} className={inputClass} value={before} onChange={(e) => setBefore(e.target.value)} />
      </Field>
      <Field label={s('bipe_agenda_buffer_after')}>
        <input type="number" min={0} max={1440} className={inputClass} value={after} onChange={(e) => setAfter(e.target.value)} />
      </Field>
      <Section header={s('bipe_agenda_setup_people')}>
        {state.agendas.map((a) => (
          <Row key={a.id}>
            <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
              <input
                type="checkbox"
                className="size-5 accent-[var(--color-accent-fill)]"
                checked={agendaIds.includes(a.id)}
                onChange={(e) => toggleAgenda(a.id, e.target.checked)}
              />
              <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{a.name}</span>
            </label>
          </Row>
        ))}
      </Section>
      <Section footer={s('bipe_web_agenda_price_in_catalog')}>
        <Row>
          <Switch label={s('bipe_catalog_active')} checked={active} onChange={setActive} />
        </Row>
      </Section>
      <AgendaError error={save.error} />
      <div className="flex flex-wrap gap-2">
        <PrimaryButton type="submit" disabled={!input || save.busy}>
          {s('bipe_app_save')}
        </PrimaryButton>
        <SecondaryButton onClick={() => onDone(false)}>{s('bipe_app_cancel')}</SecondaryButton>
      </div>
      {service &&
        (confirmsRemove ? (
          <div className="flex flex-col gap-2 rounded-xl bg-grouped-cell p-4">
            <p className="text-footnote text-ink-secondary">{s('bipe_catalog_remove_body')}</p>
            <div className="flex gap-2">
              <TextButton
                danger
                disabled={save.busy}
                onClick={async () => {
                  const done = await save.run(async () => {
                    await client.archiveService(service.id);
                    return true;
                  });
                  if (done) onDone(true);
                }}
              >
                {s('bipe_catalog_remove')}
              </TextButton>
              <TextButton onClick={() => setConfirmsRemove(false)}>{s('bipe_app_cancel')}</TextButton>
            </div>
          </div>
        ) : (
          <div>
            <TextButton danger onClick={() => setConfirmsRemove(true)}>
              {s('bipe_catalog_remove')}
            </TextButton>
          </div>
        ))}
    </form>
  );
}

/** Special days from today on: dates with other hours, or closed, per agenda. */
function OverridesPage({ client, state }: SettingsProps) {
  const today = localParts(state.time_zone, Date.now()).date;
  const load = useLoad(() => client.overrides(today), [client, today]);
  const remove = useAction();
  const [editing, setEditing] = useState<Override | 'new'>();
  const names = nameOf(state);

  if (editing)
    return (
      <OverrideEditor
        key={editing === 'new' ? 'new' : editing.id}
        client={client}
        state={state}
        override={editing === 'new' ? undefined : editing}
        onDone={(changed) => {
          setEditing(undefined);
          if (changed) void load.reload();
        }}
      />
    );
  if (!load.data) return <LoadState loading={load.loading} error={load.error} onRetry={load.reload} />;
  const dates = (o: Override) =>
    o.date_from === o.date_to
      ? dateText(o.date_from, { weekday: 'short', day: 'numeric', month: 'short' })
      : new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', timeZone: 'UTC' }).formatRange(
          new Date(`${o.date_from}T12:00:00Z`),
          new Date(`${o.date_to}T12:00:00Z`),
        );
  return (
    <>
      {load.data.overrides.length > 0 && (
        <Section>
          {load.data.overrides.map((o) => (
            <Row key={o.id}>
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-body text-ink-primary">{dates(o)}</span>
                <span className="truncate text-footnote text-ink-secondary">
                  {names.agenda(o.agenda_id)} · {o.closed ? s('bipe_agenda_closed') : rangesText(o.ranges)}
                </span>
              </span>
              <TextButton onClick={() => setEditing(o)}>{s('bipe_app_edit')}</TextButton>
              <button
                type="button"
                className="grid size-11 place-items-center rounded-full text-glyph-secondary disabled:opacity-50"
                aria-label={s('bipe_web_work_remove')}
                disabled={remove.busy}
                onClick={async () => {
                  const done = await remove.run(async () => {
                    await client.deleteOverride(o.id);
                    return true;
                  });
                  if (done) void load.reload();
                }}
              >
                <Icon path={x} size={16} />
              </button>
            </Row>
          ))}
        </Section>
      )}
      <AgendaError error={remove.error} />
      <div>
        <PrimaryButton onClick={() => setEditing('new')} disabled={state.agendas.length === 0}>
          {s('bipe_web_agenda_add_override')}
        </PrimaryButton>
      </div>
    </>
  );
}

function OverrideEditor({
  client,
  state,
  override,
  onDone,
}: {
  client: BusinessClient;
  state: AgendaState;
  override?: Override;
  onDone: (changed: boolean) => void;
}) {
  const today = localParts(state.time_zone, Date.now()).date;
  const save = useAction();
  const [agendaId, setAgendaId] = useState(override?.agenda_id ?? state.agendas[0]?.id ?? '');
  const [from, setFrom] = useState(override?.date_from ?? today);
  const [to, setTo] = useState(override?.date_to ?? today);
  const [closed, setClosed] = useState(override?.closed ?? true);
  const [ranges, setRanges] = useState<TimeRange[]>(
    override && !override.closed ? override.ranges : [{ start: '09:00', end: '12:00' }],
  );
  const valid = !!agendaId && !!from && !!to && to >= from && (closed || (ranges.length > 0 && rangesValid(ranges)));

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!valid) return;
        const body = { agenda_id: agendaId, date_from: from, date_to: to, closed, ranges: closed ? [] : ranges };
        const done = await save.run(() =>
          override ? client.replaceOverride(override.id, body) : client.createOverride(body),
        );
        if (done) onDone(true);
      }}
    >
      <Field label={s('bipe_agenda_person')}>
        <select className={inputClass} value={agendaId} onChange={(e) => setAgendaId(e.target.value)}>
          {state.agendas.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </Field>
      <div className="flex gap-2">
        <Field label={s('bipe_web_agenda_date_from')}>
          <input type="date" className={inputClass} value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label={s('bipe_web_agenda_date_to')}>
          <input type="date" className={inputClass} value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </Field>
      </div>
      <Section>
        <Row>
          <Switch label={s('bipe_agenda_closed')} checked={closed} onChange={setClosed} />
        </Row>
      </Section>
      {!closed && <RangesEditor ranges={ranges} onChange={setRanges} />}
      {!closed && !rangesValid(ranges) && (
        <p className="text-footnote text-trust-danger-ink">{s('bipe_web_agenda_ranges_invalid')}</p>
      )}
      <AgendaError error={save.error} />
      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={!valid || save.busy}>
          {s('bipe_app_save')}
        </PrimaryButton>
        <SecondaryButton onClick={() => onDone(false)}>{s('bipe_app_cancel')}</SecondaryButton>
      </div>
    </form>
  );
}

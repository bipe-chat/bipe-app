// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec agenda-book (AgendaBookView.swift): service, then person (or Qualquer pessoa), then a day on a week strip and
// a free time, then a review with the business policy. The time is held for 5 minutes until confirm. When moving, or
// when a quote is accepted into a booking, the review calls that route instead of hold and confirm. Times show in
// this browser's time zone; the days come from the API in the business's time zone.

import { useEffect, useState } from 'react';
import type { Agenda, Booking, BookingHold, Service, SlotDay } from '../../bipe/generated/business-api';
import { NavBar } from '../../ui/List';
import { locale, s, timeText } from '../../ui/i18n';
import { WorkError, type BusinessClient } from '../business-client';
import { priceText } from '../Catalog';
import { LoadState, PrimaryButton, SecondaryButton, useLoad } from '../common';
import { addDays, localDate } from '../Quotes';

const ANY = 'any';
const WEEK_DAYS = 7;
/** BipeAgendaPolicy.defaults: the public routes do not give the business's cancel window yet. */
const DEFAULT_CANCEL_WINDOW_HOURS = 2;

const dayLabel = new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', timeZone: 'UTC' });
const fullDay = new Intl.DateTimeFormat(locale, { dateStyle: 'full' });

/** BipeAPIError.agendaMessage on iOS. */
export function agendaErrorText(e: unknown): string {
  if (!(e instanceof WorkError)) return s('bipe_agenda_error_save');
  switch (e.code) {
    case 'network':
      return s('bipe_error_network');
    case 'slot_taken':
      return s('bipe_agenda_error_slot_taken');
    case 'hold_expired':
      return s('bipe_agenda_error_hold_expired');
    case 'too_many_open_bookings':
      return s('bipe_agenda_error_too_many_open');
    case 'outside_notice':
      return s('bipe_agenda_error_outside_notice');
    case 'outside_horizon':
      return s('bipe_agenda_error_outside_horizon');
    case 'agenda_not_published':
      return s('bipe_agenda_error_not_published');
    case 'cancellation_window_closed':
      return s('bipe_agenda_error_cancel_window');
    default:
      return s('bipe_agenda_error_save');
  }
}

function cancelPolicyLine(): string {
  const span = new Intl.NumberFormat(locale, { style: 'unit', unit: 'hour', unitDisplay: 'long' });
  return s('bipe_agenda_cancel_policy', span.format(DEFAULT_CANCEL_WINDOW_HOURS));
}

type Step = 'service' | 'person' | 'time' | 'review';

export function AgendaBook({
  api,
  business,
  serviceId,
  moving,
  acceptQuote,
  onClose,
}: {
  api: BusinessClient;
  business: string;
  serviceId?: string;
  moving?: Booking;
  /** quote-card acceptBooking: the review accepts the quote with the chosen time. */
  acceptQuote?: (hold: BookingHold) => Promise<unknown>;
  onClose: (message?: string) => void;
}) {
  const services = useLoad(() => api.publicServices(business).then((r) => r.services), [api, business]);
  const [service, setService] = useState<Service>();
  const [agendas, setAgendas] = useState<Agenda[]>([]);
  const [agendaId, setAgendaId] = useState<string>(ANY);
  const [step, setStep] = useState<Step>('service');
  const [weekStart, setWeekStart] = useState(() => new Date());
  const [days, setDays] = useState<SlotDay[]>();
  const [date, setDate] = useState<string>();
  const [start, setStart] = useState<string>();
  const [hold, setHold] = useState<Booking>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const pickService = async (next: Service) => {
    setService(next);
    setError(undefined);
    const list = await api.publicAgendas(business, next.id).then(
      (r) => r.agendas,
      () => [] as Agenda[],
    );
    setAgendas(list);
    if (list.length === 1) {
      setAgendaId(list[0].id);
      setStep('time');
    } else {
      setAgendaId(ANY);
      setStep(list.length > 1 ? 'person' : 'time');
    }
  };

  // The service picked when the catálogo, a Remarcar or a quote opened the flow.
  const list = services.data;
  useEffect(() => {
    const found = serviceId ? list?.find((x) => x.id === serviceId) : undefined;
    if (found) void pickService(found);
    // Runs once when the list arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list, serviceId]);

  const loadWeek = (first: Date) => {
    if (!service) return;
    setDays(undefined);
    api
      .slots(business, {
        service_id: service.id,
        agenda_id: agendaId === ANY ? undefined : agendaId,
        date_from: localDate(first),
        date_to: localDate(addDays(first, WEEK_DAYS - 1)),
      })
      .then(
        (r) => {
          setDays(r.days);
          setDate((d) => (d && r.days.some((x) => x.date === d) ? d : r.days.find((x) => x.slots.length > 0)?.date));
        },
        (e: unknown) => {
          setDays([]);
          setError(agendaErrorText(e));
        },
      );
  };

  useEffect(() => {
    if (step === 'time') loadWeek(weekStart);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step, weekStart, service, agendaId]);

  const pickTime = async (at: string) => {
    if (!service) return;
    setError(undefined);
    setStart(at);
    if (moving || acceptQuote) {
      setStep('review');
      return;
    }
    setBusy(true);
    try {
      setHold(await api.hold(business, { service_id: service.id, agenda_id: agendaId, start: at }));
      setStep('review');
    } catch (e) {
      setError(agendaErrorText(e));
      loadWeek(weekStart);
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    if (!service || !start) return;
    setBusy(true);
    setError(undefined);
    try {
      if (moving) await api.rescheduleBooking(business, moving.id, { agenda_id: agendaId, start });
      else if (acceptQuote) await acceptQuote({ service_id: service.id, agenda_id: agendaId, start });
      else if (hold) await api.confirmBooking(business, hold.id);
      onClose(s('bipe_agenda_booked'));
    } catch (e) {
      setError(agendaErrorText(e));
      if (e instanceof WorkError && (e.code === 'hold_expired' || e.code === 'slot_taken')) {
        setHold(undefined);
        setStep('time');
      }
    } finally {
      setBusy(false);
    }
  };

  const back = () => {
    setError(undefined);
    if (step === 'review') setStep('time');
    else if (step === 'time' && agendas.length > 1) setStep('person');
    else if ((step === 'time' || step === 'person') && !serviceId) setStep('service');
    else onClose();
  };

  const title = moving
    ? s('bipe_agenda_reschedule')
    : { service: s('bipe_agenda_book'), person: s('bipe_agenda_pick_person'), time: s('bipe_agenda_pick_time'), review: s('bipe_agenda_review_title') }[step];
  const today = localDate(new Date());
  const shown = days?.find((d) => d.date === date);
  const agendaName = agendas.find((a) => a.id === agendaId)?.name;

  return (
    <>
      <NavBar title={title} onBack={back} />
      {!services.data ? (
        <div className="flex flex-1 flex-col">
          {services.loading ? (
            <LoadState loading error={undefined} onRetry={() => undefined} />
          ) : (
            <div className="flex flex-col items-center gap-3 p-8 text-center">
              <p className="text-footnote text-ink-secondary">{s('bipe_agenda_load_failed')}</p>
              <SecondaryButton onClick={() => void services.reload()}>{s('bipe_app_retry')}</SecondaryButton>
            </div>
          )}
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
          {error && (
            <p role="alert" className="text-footnote text-trust-danger-ink">
              {error}
            </p>
          )}
          {step === 'service' && (
            <Choices
              header={s('bipe_agenda_pick_service')}
              items={services.data.map((x) => ({
                key: x.id,
                title: x.name,
                detail: `${s('bipe_agenda_duration', String(x.duration_minutes))} · ${priceText(x.price)}`,
                onPick: () => void pickService(x),
              }))}
            />
          )}
          {step === 'person' && (
            <Choices
              header={s('bipe_agenda_pick_person')}
              items={[{ id: ANY, name: s('bipe_agenda_anyone') }, ...agendas].map((a) => ({
                key: a.id,
                title: a.name,
                onPick: () => {
                  setAgendaId(a.id);
                  setStep('time');
                },
              }))}
            />
          )}
          {step === 'time' && (
            <>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  className="min-h-11 px-2 text-accent-ink disabled:opacity-40"
                  aria-label={s('bipe_a11y_back')}
                  disabled={localDate(weekStart) <= today}
                  onClick={() => setWeekStart(addDays(weekStart, -WEEK_DAYS))}
                >
                  ‹
                </button>
                <div className="flex min-w-0 flex-1 gap-1 overflow-x-auto">
                  {days?.map((d) => (
                    <button
                      key={d.date}
                      type="button"
                      aria-pressed={d.date === date}
                      disabled={d.slots.length === 0}
                      className={`min-h-11 shrink-0 rounded-lg px-2.5 text-footnote disabled:opacity-40 ${d.date === date ? 'bg-accent-fill text-ink-on-accent' : 'bg-grouped-cell text-ink-primary'}`}
                      onClick={() => setDate(d.date)}
                    >
                      {dayLabel.format(Date.parse(`${d.date}T12:00:00Z`))}
                    </button>
                  ))}
                </div>
                <button
                  type="button"
                  className="min-h-11 px-2 text-accent-ink"
                  aria-label={s('bipe_agenda_pick_time')}
                  onClick={() => setWeekStart(addDays(weekStart, WEEK_DAYS))}
                >
                  ›
                </button>
              </div>
              {days === undefined ? (
                <LoadState loading error={undefined} onRetry={() => undefined} />
              ) : shown && shown.slots.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {shown.slots.map((slot) => (
                    <SecondaryButton key={slot.start} disabled={busy} onClick={() => void pickTime(slot.start)}>
                      {timeText(Date.parse(slot.start))}
                    </SecondaryButton>
                  ))}
                </div>
              ) : (
                <p className="text-body text-ink-secondary">{s('bipe_agenda_no_slots')}</p>
              )}
            </>
          )}
          {step === 'review' && service && start && (
            <div className="flex flex-col gap-2">
              <h3 className="text-headline text-ink-primary">{service.name}</h3>
              <p className="text-body text-ink-primary">
                {fullDay.format(Date.parse(start))}, {timeText(Date.parse(start))}
              </p>
              <p className="text-subheadline text-ink-secondary">
                {s('bipe_agenda_duration', String(service.duration_minutes))} · {priceText(service.price)}
              </p>
              {agendaName && <p className="text-subheadline text-ink-secondary">{s('bipe_agenda_with', agendaName)}</p>}
              <p className="text-footnote text-ink-secondary">{cancelPolicyLine()}</p>
              {hold?.hold_expires_at && <HoldLine until={hold.hold_expires_at} />}
              <PrimaryButton disabled={busy} onClick={() => void confirm()}>
                {s('bipe_agenda_confirm')}
              </PrimaryButton>
            </div>
          )}
        </div>
      )}
    </>
  );
}

function Choices({
  header,
  items,
}: {
  header: string;
  items: { key: string; title: string; detail?: string; onPick: () => void }[];
}) {
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="px-1 text-footnote text-ink-secondary">{header}</h3>
      <ul className="overflow-hidden rounded-xl bg-grouped-cell">
        {items.map((item) => (
          <li key={item.key}>
            <button type="button" className="flex w-full flex-col p-3 text-left" onClick={item.onPick}>
              <span className="text-body text-ink-primary">{item.title}</span>
              {item.detail && <span className="text-footnote text-ink-secondary">{item.detail}</span>}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** bipe_agenda_hold with the time left of the 5-minute hold. */
function HoldLine({ until }: { until: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const left = Math.max(0, Math.round((Date.parse(until) - now) / 1000));
  return (
    <p className="text-footnote text-ink-secondary">
      {s('bipe_agenda_hold', `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`)}
    </p>
  );
}

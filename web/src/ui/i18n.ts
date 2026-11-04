// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// UI text and dates. The apps follow the system language; the web follows the browser's.
// pt-BR is the default, English only when the browser asks for it first.

import { t, type Locale, type StringKey } from '../bipe/generated/strings';

const first = (navigator.languages?.[0] ?? navigator.language ?? '').toLowerCase();
export const locale: Locale = first.startsWith('en') ? 'en' : 'pt-BR';
document.documentElement.lang = locale;

/** The string of key, with %1$s, %2$s, ... replaced by args. */
export function s(key: StringKey, ...args: string[]): string {
  return t(key, args, locale);
}

const clock = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' });
const dayThisYear = new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric', month: 'long' });
const dayOtherYear = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', year: 'numeric' });

/** The clock time of a message (MessageTimestamp, TimelineRowView.swift). */
export function timeText(ms: number): string {
  return clock.format(ms);
}

/** Spec timeline-rows notes: today, yesterday, else the date, with the year only when it is not this year. */
export function dayText(ms: number): string {
  const day = new Date(ms);
  const today = new Date();
  const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();
  if (sameDay(day, today)) return s('bipe_app_timeline_today');
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (sameDay(day, yesterday)) return s('bipe_app_timeline_yesterday');
  return (day.getFullYear() === today.getFullYear() ? dayThisYear : dayOtherYear).format(day);
}

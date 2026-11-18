// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// UI text and dates. The language is the choice of spec language-region (Idioma) in this browser, else the
// browser's: pt-BR is the default, English only when the browser asks for it first.

import { t, type Locale, type StringKey } from '../bipe/generated/strings';

export type LanguageChoice = 'system' | 'pt' | 'en';
const LANGUAGE_KEY = 'bipe.settings.language';

/** The stored choice of spec language-region; system when there is none. */
export function languageChoice(): LanguageChoice {
  try {
    const value = localStorage.getItem(LANGUAGE_KEY);
    return value === 'pt' || value === 'en' ? value : 'system';
  } catch {
    return 'system';
  }
}

/** Action selectLanguage: stores the choice and reloads the page, which reads it at start. */
export function selectLanguage(choice: LanguageChoice) {
  if (choice === languageChoice()) return;
  try {
    if (choice === 'system') localStorage.removeItem(LANGUAGE_KEY);
    else localStorage.setItem(LANGUAGE_KEY, choice);
  } catch {
    return;
  }
  location.reload();
}

const first = (navigator.languages?.[0] ?? navigator.language ?? '').toLowerCase();
const chosen = languageChoice();
export const locale: Locale = chosen === 'en' || (chosen === 'system' && first.startsWith('en')) ? 'en' : 'pt-BR';
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

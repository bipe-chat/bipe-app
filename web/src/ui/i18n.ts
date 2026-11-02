// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// UI text and dates. The apps follow the system language; the web follows the browser's.
// pt-BR is the default, English only when the browser asks for it first.

import { en, ptBR, t, type Locale, type StringKey } from '../bipe/generated/strings';

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

// The web strings of bipe-shared branch web-strings, on their way to strings.ts. Until the regenerated
// strings.ts has a key, the text below shows: the same pt-BR and English text, word for word. Replace
// each pending() with s() after the regen; nothing else changes.
const PENDING = {
  bipe_web_signin_title: ['Use o Bipe no computador', 'Use Bipe on your computer'],
  bipe_web_signin_step_open: ['Abra o Bipe no celular.', 'Open Bipe on your phone.'],
  bipe_web_signin_step_path: ['Toque em %1$s › %2$s › %3$s › %4$s.', 'Tap %1$s › %2$s › %3$s › %4$s.'],
  bipe_web_signin_step_scan: [
    'Aponte a câmera do celular para este código.',
    "Point your phone's camera at this code.",
  ],
  bipe_web_signin_check_code_title: ['Digite o número que aparece no celular', 'Type the number your phone shows'],
  bipe_web_signin_check_code_send: ['Confirmar', 'Confirm'],
  bipe_web_signin_approve: ['Aprove no celular para entrar.', 'Approve on your phone to sign in.'],
  bipe_web_signin_keys: ['Trazendo suas chaves do celular…', 'Getting your keys from your phone…'],
  bipe_web_signin_expired: ['O código venceu. Criando outro…', 'The code expired. Making a new one…'],
  bipe_web_loading: ['Carregando…', 'Loading…'],
  bipe_web_timeline_undecryptable: [
    'Ainda não dá para ler esta mensagem neste computador.',
    "This message can't be read on this computer yet.",
  ],
  bipe_app_timeline_unsupported: [
    'Este tipo de mensagem ainda não aparece aqui.',
    "This kind of message doesn't show here yet.",
  ],
  bipe_app_upload_remove: ['Remover', 'Remove'],
  bipe_a11y_scroll_to_newest: ['Ir para as mensagens mais novas', 'Go to the newest messages'],
} as const;

export function pending(key: keyof typeof PENDING, ...args: string[]): string {
  const table = (locale === 'en' ? en : ptBR) as Record<string, string>;
  const text = table[key] ?? PENDING[key][locale === 'en' ? 1 : 0];
  return text.replace(/%(\d+)\$s/g, (whole, n: string) => args[Number(n) - 1] ?? whole);
}

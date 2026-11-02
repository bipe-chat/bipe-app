// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Web-only strings of Trabalho (ADR 0226), on their way to bipe-shared strings/. Until the regenerated
// strings.ts has a key, the text below shows: the same pt-BR and English text, word for word. Replace each
// w() with s() after the regen; nothing else changes. Same rule as PENDING in ui/i18n.ts.

import { locale } from '../ui/i18n';

const PENDING = {
  bipe_web_work_phone_only: [
    'Criar a empresa, o CNPJ, o selo, os domínios, as pessoas da equipe e as cobranças Pix ficam no app do celular.',
    'Creating the company, the CNPJ, the seal, domains, team members and Pix charges stay in the phone app.',
  ],
  bipe_web_work_in_app: ['Disponível no app do celular.', 'Available in the phone app.'],
} as const;

export type PendingKey = keyof typeof PENDING;

/** The pending string of key, with %1$s, %2$s, ... replaced by args. */
export function w(key: PendingKey, ...args: string[]): string {
  const text: string = PENDING[key][locale === 'en' ? 1 : 0];
  return args.reduce((out, arg, i) => out.replaceAll(`%${i + 1}$s`, arg), text);
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec scam-warning (ScamWarningCard in TimelineRowView.swift, BipeMoneyPause.swift): a received message that
// looks like a scam pattern gets a calm caution card under its text: a warning glyph, Pode ser um golpe and
// the kind's text. For the money kinds (registries scam.moneyPauseKinds) its links stay shut for
// scam.moneyPauseSeconds the first time the card shows in this page, with the countdown. The rule is the
// shared scamWarningKind of the generated lib, the same that the apps' vectors check.

import { useEffect, useState } from 'react';
import { scamWarningKind } from './shared-rules';
import { homoglyphs, phones, scam } from './bipe/generated/registries.json';
import type { StringKey } from './bipe/generated/strings';
import { warningCircle } from './phosphor/icons';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';

const reg = { scam, homoglyphs, phones };

export type ScamKind =
  | 'extortion'
  | 'pixCode'
  | 'boleto'
  | 'remoteApp'
  | 'pinRequest'
  | 'pixKey'
  | 'deliveryFee'
  | 'lookalikeLink'
  | 'bankAlert'
  | 'receipt'
  | 'newNumber'
  | 'urgency';

const MESSAGE: Record<ScamKind, StringKey> = {
  extortion: 'bipe_scam_warning_extortion',
  pixCode: 'bipe_scam_warning_pix_code',
  boleto: 'bipe_scam_warning_boleto',
  remoteApp: 'bipe_scam_warning_remote_app',
  pinRequest: 'bipe_scam_warning_pin_request',
  pixKey: 'bipe_scam_warning_pix_key',
  deliveryFee: 'bipe_scam_warning_delivery_fee',
  lookalikeLink: 'bipe_scam_warning_lookalike_link',
  bankAlert: 'bipe_scam_warning_bank_alert',
  receipt: 'bipe_scam_warning_receipt',
  newNumber: 'bipe_scam_warning_new_number',
  urgency: 'bipe_scam_warning_urgency',
};

export function scamKindOf(text: string): ScamKind | undefined {
  try {
    return (scamWarningKind(text, reg) as ScamKind | null) ?? undefined;
  } catch {
    return undefined;
  }
}

// One pause per message for the life of the page (BipeMoneyPause: one per open chat on the phone).
const pauseEnds = new Map<string, number>();

export function pauseLeft(rowKey: string): number {
  const end = pauseEnds.get(rowKey);
  return end === undefined ? 0 : Math.max(0, Math.ceil((end - Date.now()) / 1000));
}

export function ScamWarningCard({ rowKey, kind }: { rowKey: string; kind: ScamKind }) {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!scam.moneyPauseKinds.includes(kind) || pauseEnds.has(rowKey)) return;
    pauseEnds.set(rowKey, Date.now() + scam.moneyPauseSeconds * 1000);
  }, [rowKey, kind]);
  const left = pauseLeft(rowKey);
  useEffect(() => {
    if (left <= 0) return;
    const timer = setTimeout(() => tick((n) => n + 1), 1000);
    return () => clearTimeout(timer);
  }, [left]);
  return (
    <div
      role="note"
      className="flex max-w-[320px] items-start gap-2 rounded-[10px] border border-trust-caution-line bg-trust-caution-wash p-2"
    >
      <Icon path={warningCircle} size={16} className="mt-0.5 shrink-0 text-trust-caution-ink" />
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-caption font-bold text-trust-caution-ink">{s('bipe_scam_warning_title')}</span>
        <span className="text-caption text-ink-primary">{s(MESSAGE[kind])}</span>
        {left > 0 && (
          <span aria-hidden className="text-caption font-bold text-trust-caution-ink tabular-nums">
            {s('bipe_pause_countdown', String(left))}
          </span>
        )}
      </div>
    </div>
  );
}

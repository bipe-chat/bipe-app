// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec linked-devices and device-detail in a browser (bipe ADR 0291, bipe-chat/bipe#1482): the device rows of the group
// Aparelhos e assistentes, this browser first, read-only. No sign-out and no link actions: only a phone signs devices
// out (ADR 0266), so the footer is bipe_mac_linked_devices_footer.

import { useState } from 'react';
import { deviceKinds } from './bipe/generated/registries.json';
import type { StringKey } from './bipe/generated/strings';
import type { DeviceList } from './devices';
import { caretRight, chatText, desktop, link, phone, shareNetwork, warning } from './phosphor/icons';
import type { DeviceRow } from './shared-rules';
import { locale, s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { NavBar, Row, Section } from './ui/List';
import { Spinner } from './ui/Spinner';

const key = (k: string) => k as StringKey;

// The icons of registries/device-kinds.json (Phosphor names).
const ICONS: Record<string, string> = { phone, desktop, link, 'chat-text': chatText, 'share-network': shareNetwork, warning };

const relative = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
const dateTime = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' });

/** A past time as "há 5 minutos", "ontem". */
function ago(ms: number): string {
  const seconds = (ms - Date.now()) / 1000;
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['second', 60],
    ['minute', 60],
    ['hour', 24],
    ['day', 30],
    ['month', 12],
  ];
  let value = seconds;
  for (const [unit, size] of steps) {
    if (Math.abs(value) < size) return relative.format(Math.round(value), unit);
    value /= size;
  }
  return relative.format(Math.round(value), 'year');
}

const title = (row: DeviceRow) => row.name ?? s('bipe_linked_devices_unknown_name');

/** Kind, system and app, and "fora do Brasil" for a program that sends data out of Brazil. */
function details(row: DeviceRow): string {
  const parts = [s(key(row.label))];
  if (row.os) parts.push(row.os);
  if (row.app && row.app !== row.name) parts.push(row.app);
  if (row.leavesBrazil) parts.push(s('bipe_devices_outside_brazil'));
  return parts.join(' · ');
}

/** Last use. This browser shows none. */
function use(row: DeviceRow): string | undefined {
  return !row.isThis && row.lastSeen !== null ? s('bipe_linked_devices_last_used', ago(row.lastSeen)) : undefined;
}

/** The subtitle of the group row: bipe_devices_summary, or bipe_devices_summary_unknown while unknownWarning is on. */
export function devicesSummary(list: DeviceList): string | undefined {
  if (list.phase !== 'loaded') return undefined;
  const { counts } = list;
  if (counts.unknown > 0 && deviceKinds.unknownWarning) return s('bipe_devices_summary_unknown', String(counts.unknown));
  return s('bipe_devices_summary', String(counts.devices), String(counts.programs));
}

function DeviceIcon({ row, size }: { row: DeviceRow; size: number }) {
  return (
    <Icon
      path={ICONS[row.icon] ?? warning}
      size={size}
      className={`shrink-0 ${row.warning ? 'text-trust-caution-ink' : 'text-accent-ink'}`}
    />
  );
}

/** Sections Aparelhos and Assistentes e programas: the device rows, read-only. */
export function DeviceSections({
  list,
  onRetry,
  onOpen,
}: {
  list: DeviceList;
  onRetry: () => void;
  onOpen: (row: DeviceRow) => void;
}) {
  const rows = list.phase === 'loaded' ? list.rows : [];
  const devices = rows.filter((r) => r.section !== 'programs');
  const programs = rows.filter((r) => r.section === 'programs');
  return (
    <>
      <Section header={s('bipe_devices_section_devices')} footer={s('bipe_mac_linked_devices_footer')}>
        {list.phase === 'loading' ? (
          <Row>
            <span className="flex flex-1 justify-center">
              <Spinner />
            </span>
          </Row>
        ) : list.phase === 'failed' ? (
          <Row>
            <span className="min-w-0 flex-1 text-body text-ink-secondary">{s('bipe_linked_devices_error')}</span>
            <button type="button" className="text-body text-accent-ink" onClick={onRetry}>
              {s('bipe_app_retry')}
            </button>
          </Row>
        ) : devices.length === 0 ? (
          <Row>
            <span className="min-w-0 flex-1 text-body text-ink-secondary">{s('bipe_linked_devices_empty')}</span>
          </Row>
        ) : (
          devices.map((row) => <DeviceRowButton key={row.deviceId} row={row} onOpen={onOpen} />)
        )}
      </Section>
      {programs.length > 0 && (
        <Section header={s('bipe_devices_section_programs')}>
          {programs.map((row) => (
            <DeviceRowButton key={row.deviceId} row={row} onOpen={onOpen} />
          ))}
        </Section>
      )}
    </>
  );
}

/** One row: icon, "Este aparelho" for this browser, name, kind and details, last use. Opens device-detail. */
function DeviceRowButton({ row, onOpen }: { row: DeviceRow; onOpen: (row: DeviceRow) => void }) {
  const lastUse = use(row);
  return (
    <li>
      <button
        type="button"
        className="flex w-full items-center gap-3 pl-4 text-left hover:bg-surface-sunken"
        onClick={() => onOpen(row)}
      >
        <DeviceIcon row={row} size={22} />
        <span className="flex min-h-11 min-w-0 flex-1 items-center gap-2 py-2 pr-4 [li:not(:last-child)_&]:border-b-[0.5px] [li:not(:last-child)_&]:border-line-subtle">
          <span className="flex min-w-0 flex-1 flex-col">
            {row.isThis && <span className="text-caption font-semibold text-accent-ink">{s('bipe_devices_this_device')}</span>}
            <span className="truncate text-body text-ink-primary">{title(row)}</span>
            <span className="text-subheadline text-ink-secondary">{details(row)}</span>
            {lastUse && <span className="text-subheadline text-ink-secondary">{lastUse}</span>}
            {row.warning && <span className="text-footnote text-trust-caution-ink">{s('bipe_devices_unknown_warning')}</span>}
          </span>
          <Icon path={caretRight} size={16} className="text-glyph-secondary" />
        </span>
      </button>
    </li>
  );
}

/** Spec device-detail, read-only: everything Bipe knows about one device. No sign-out in a browser. */
export function DeviceDetail({ row, rows, onBack }: { row: DeviceRow; rows: DeviceRow[]; onBack: () => void }) {
  const [copied, setCopied] = useState(false);
  const lastUse = use(row);
  const linkedBy = row.linkedBy === null ? undefined : (rows.find((r) => r.deviceId === row.linkedBy)?.name ?? row.linkedBy);
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      <NavBar title={row.isThis ? s('bipe_devices_this_device') : title(row)} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pt-2 pb-6">
        <Section footer={row.isThis ? s('bipe_devices_this_device_hint') : undefined}>
          <Row leading={<DeviceIcon row={row} size={28} />}>
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-headline text-ink-primary">{title(row)}</span>
              <span className="text-subheadline text-ink-secondary">{details(row)}</span>
            </span>
          </Row>
          {row.warning && (
            <Row>
              <span className="text-subheadline text-trust-caution-ink">{s('bipe_devices_unknown_warning')}</span>
            </Row>
          )}
        </Section>
        <Section>
          {lastUse && <TextRow text={lastUse} />}
          {row.ip && <TextRow text={s('bipe_device_detail_ip', row.ip)} />}
          {row.linkedAt !== null && <TextRow text={s('bipe_device_detail_linked_at', dateTime.format(row.linkedAt))} />}
          {linkedBy && <TextRow text={s('bipe_device_detail_linked_by', linkedBy)} />}
          <TextRow text={row.attested ? s('bipe_device_detail_attested') : s('bipe_device_detail_not_attested')} />
          {/* Action copyId: the one place that shows the device ID (ADR 0291). */}
          <Row>
            <button
              type="button"
              className="min-w-0 flex-1 py-1 text-left text-body break-all text-ink-primary"
              onClick={() => void navigator.clipboard.writeText(row.deviceId).then(() => setCopied(true))}
            >
              {s('bipe_device_detail_id', row.deviceId)}
            </button>
          </Row>
        </Section>
        {copied && (
          <p role="status" className="px-4 text-footnote text-ink-secondary">
            {s('bipe_device_detail_id_copied')}
          </p>
        )}
      </div>
    </div>
  );
}

function TextRow({ text }: { text: string }) {
  return (
    <Row>
      <span className="min-w-0 flex-1 text-body text-ink-primary">{text}</span>
    </Row>
  );
}

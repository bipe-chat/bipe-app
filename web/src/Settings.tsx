// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Settings for this browser (spec settings-root, SettingsRootView.swift and SettingsGroupView.swift).
// The groups, sections and rows come from registries settings, in its order. The web shows only the
// rows that work in a browser: url rows (pages on bipe.chat) and Sair deste aparelho. A group with no
// such row is left out. The root rows are the iPhone Settings layout: a 29 px accent.fill tile with an
// 18 px ink.onAccent glyph, the title, the current state as the subtitle and a chevron.

import { Fragment, useEffect, useState } from 'react';
import { apiFor } from './api';
import { PALETTES, BACKGROUNDS, useSetting, writeSetting, type Appearance } from './appearance';
import { useClient } from './media';
import { useMuralEnabled, writeMural } from './mural-plan';
import { syncedSettingChanged } from './settings-sync';
import { openNotes } from './notes';
import { caretRight, chatCircle, bell, check, devices, info, lockSimple, notepad, userCircle, warningCircle } from './phosphor/icons';
import { useDeviceList } from './devices';
import { DeviceDetail, DeviceSections, devicesSummary } from './DevicesSettings';
import type { DeviceRow } from './shared-rules';
import { Spinner } from './ui/Spinner';
import { settings } from './registries';
import { useAccounts } from './accounts-context';
import { Alert } from './ui/Alert';
import { RecoveryState, VerificationState } from './generated/matrix_sdk_ffi';
import { EncryptionSheet, useEncryptionState } from './Encryption';
import type { StringKey } from './bipe/generated/strings';
import { Avatar } from './ui/Avatar';
import { locale, s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { NavBar, Row, Section } from './ui/List';

type Group = (typeof settings.groups)[number];
type SettingsRow = Group['sections'][number]['rows'][number];

// The root group icons of registries settings (Phosphor names).
const ICONS: Record<string, string> = {
  'lock-simple': lockSimple,
  'user-circle': userCircle,
  bell,
  'chat-circle': chatCircle,
  devices,
  info,
};

const key = (k: string) => k as StringKey;

// The rows of switches the web keeps itself, in this browser, under the key the apps use on the phone.
const LOCAL_SWITCHES: Record<string, string> = {
  readReceipts: 'bipe.settings.readReceipts',
  // The web's notification choices (Notificações): sound and the message text, in this browser.
  sound: 'bipe.web.notifications.sound',
  preview: 'bipe.web.notifications.preview',
};
// Rows the web draws itself: Nome e foto, Tema (choice), Cores (link).
const WEB_ROWS = new Set(['namePhoto', 'appearance', 'colors', 'mural']);

function webRow(row: SettingsRow): boolean {
  // A planned: gate hides a row until the apps ship its handler (chats.mural, bipe-chat/bipe#1044).
  if ('gate' in row && typeof row.gate === 'string' && row.gate.startsWith('planned:')) return false;
  return (
    row.kind === 'url' ||
    row.key === 'signOut' ||
    row.key === 'oldMessages' ||
    WEB_ROWS.has(row.key) ||
    LOCAL_SWITCHES[row.key] !== undefined
  );
}

function readSwitch(storageKey: string): boolean {
  try {
    return localStorage.getItem(storageKey) !== 'false';
  } catch {
    return true;
  }
}

/**
 * Spec read-receipts readReceiptsEnabled (ADR 0222): the Privacidade switch, in this browser, on by
 * default (ReadReceiptSetting on iOS). Off: DMs send private receipts and show no read ticks.
 */
export function readReceiptsOn(): boolean {
  return readSwitch(LOCAL_SWITCHES.readReceipts);
}

function SwitchRow({ title, storageKey }: { title: string; storageKey: string }) {
  const [on, setOn] = useState(() => readSwitch(storageKey));
  return (
    <SwitchControl
      title={title}
      on={on}
      onChange={(next) => {
        setOn(next);
        try {
          localStorage.setItem(storageKey, String(next));
        } catch {
          // Private windows can refuse storage; the switch then lasts for this page only.
        }
        if (storageKey === LOCAL_SWITCHES.readReceipts) syncedSettingChanged('readReceipts');
      }}
    />
  );
}

// Row chats.mural (spec chat-list, Mural setting): per account, in this browser, off by default, no sync.
function MuralSwitchRow({ title }: { title: string }) {
  const userId = useClient().userId();
  const on = useMuralEnabled(userId);
  return <SwitchControl title={title} on={on} onChange={(next) => {
        writeMural(userId, next);
        syncedSettingChanged('mural');
      }} />;
}

function SwitchControl({ title, on, onChange }: { title: string; on: boolean; onChange: (on: boolean) => void }) {
  return (
    <Row>
      <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
        <span className="min-w-0 flex-1 text-body text-ink-primary">{title}</span>
        <input
          type="checkbox"
          role="switch"
          className="peer sr-only"
          checked={on}
          onChange={(e) => onChange(e.target.checked)}
        />
        {/* The iOS switch: 51 x 31 with a 27 px knob, accent.fill when on, surface.sunken when off. */}
        <span
          aria-hidden
          className="relative h-[31px] w-[51px] shrink-0 rounded-full bg-surface-sunken transition-colors peer-checked:bg-accent-fill peer-focus-visible:outline-2 peer-focus-visible:outline-accent-fill after:absolute after:top-[2px] after:left-[2px] after:size-[27px] after:rounded-full after:bg-surface-base after:shadow after:transition-transform peer-checked:after:translate-x-5"
        />
      </label>
    </Row>
  );
}

function webSections(group: Group) {
  // Notificações opens its own screen on the phone and lists no rows; the web has its own two switches.
  if (group.key === 'notifications' && typeof Notification !== 'undefined')
    return [
      {
        key: 'web',
        footer: 'bipe_notifications_preview_footer',
        rows: [
          { key: 'sound', title: 'bipe_notifications_sound', kind: 'switch' },
          { key: 'preview', title: 'bipe_notifications_preview', kind: 'switch' },
        ],
      },
    ] as unknown as Group['sections'];
  return group.sections
    .map((section) => ({ ...section, rows: section.rows.filter(webRow) }))
    .filter((section) => section.rows.length > 0);
}

function urlOf(name: string): string | undefined {
  const urls = settings.urls as Record<string, { pt: string; en: string }>;
  return urls[name]?.[locale === 'en' ? 'en' : 'pt'];
}

type RootSummary = { text: string; needsAttention?: boolean };

/**
 * Spec settings-root, action appear: the subtitles of the root rows. Each fills in when it arrives, and a failed
 * read shows no subtitle. The web has no recovery token, as Android (BipeWaysBack hasSyncedToken false). The help
 * version waits for a web build version. Notifications shows On only: the Off string names the phone.
 */
function useRootSummaries(client: ReturnType<typeof useClient>): Record<string, RootSummary | undefined> {
  const appearance = useSetting('appearance');
  const palette = useSetting('palette');
  const [privacy, setPrivacy] = useState<RootSummary>();
  const [account, setAccount] = useState<RootSummary>();
  // Group Aparelhos e assistentes (ADR 0291): the device and assistant counts.
  const devicesText = devicesSummary(useDeviceList(client).list);
  useEffect(() => {
    let stopped = false;
    const api = apiFor(client);
    api.whoCanMessage().then((value) => {
      if (!stopped && value) setPrivacy({ text: s(key(`bipe_settings_privacy_summary_${value}`)) });
    });
    api.accountWays().then((ways) => {
      if (stopped || !ways) return;
      const hasRecoveryKey = client.encryption().recoveryState() === RecoveryState.Enabled;
      const count = [ways.hasEmail, ways.hasLinkedAccount, hasRecoveryKey].filter(Boolean).length;
      const text =
        count === 0
          ? s('bipe_settings_account_paths_none')
          : count === 1
            ? s('bipe_account_paths_warning')
            : s('bipe_settings_account_paths', String(count));
      setAccount({ text, needsAttention: count <= 1 });
    });
    return () => {
      stopped = true;
    };
  }, [client]);
  const theme =
    appearance === 'light' || appearance === 'dark'
      ? s(key(`bipe_chats_appearance_${appearance}`))
      : s('bipe_web_appearance_system');
  return {
    privacy,
    account,
    devices: devicesText === undefined ? undefined : { text: devicesText },
    notifications:
      typeof Notification !== 'undefined' && Notification.permission === 'granted'
        ? { text: s('bipe_settings_notifications_on') }
        : undefined,
    chats: { text: `${theme} · ${s(key(`bipe_colors_${palette}`))}` },
  };
}

export function Settings({
  group,
  onGroup,
  onClose,
  onOpenChat,
}: {
  group?: string;
  onGroup: (group: string | undefined) => void;
  onClose: () => void;
  /** Spec voce openNotes. */
  onOpenChat: (roomId: string) => void;
}) {
  const client = useClient();
  const [openingNotes, setOpeningNotes] = useState(false);
  const [notesFailed, setNotesFailed] = useState(false);
  const summaries = useRootSummaries(client);
  const startNotes = () => {
    if (openingNotes) return;
    setNotesFailed(false);
    setOpeningNotes(true);
    openNotes(client)
      .then(onOpenChat)
      .catch((e: unknown) => {
        console.warn('[trace] notes open failed', e);
        setNotesFailed(true);
      })
      .finally(() => setOpeningNotes(false));
  };
  // The open group comes from the URL (/ajustes/<group>).
  const open = settings.groups.find((g) => g.key === group);
  const setOpen = (g: Group | undefined) => onGroup(g?.key);
  const [me, setMe] = useState<{ name: string; avatarUrl?: string }>({ name: client.userId() });
  useEffect(() => {
    let stopped = false;
    Promise.all([client.displayName().catch(() => undefined), client.avatarUrl().catch(() => undefined)]).then(
      ([name, avatarUrl]) => !stopped && setMe({ name: name ?? client.userId(), avatarUrl }),
    );
    return () => {
      stopped = true;
    };
  }, [client]);

  const groups = settings.root
    .map((k) => settings.groups.find((g) => g.key === k))
    .filter((g): g is Group => g !== undefined && webSections(g).length > 0);

  if (open) return <GroupScreen group={open} onBack={() => setOpen(undefined)} />;
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_tab_you')} onBack={onClose} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pb-6">
        <div className="flex flex-col items-center gap-1 py-2 text-center">
          <Avatar name={me.name} size={96} mxcUrl={me.avatarUrl} userId={client.userId()} />
          <h3 className="mt-2 text-title2 text-ink-primary">{me.name}</h3>
          {/* Seu perfil opens from the header (settings-root notes). */}
          <button
            type="button"
            className="text-body text-accent-ink"
            onClick={() => setOpen(settings.groups.find((g) => g.key === 'profile'))}
          >
            {s('bipe_settings_profile')}
          </button>
        </div>
        <Section>
          <li>
            <button
              type="button"
              className="flex w-full items-center gap-3 pl-4 text-left hover:bg-surface-sunken"
              disabled={openingNotes}
              onClick={startNotes}
            >
              <span
                aria-hidden
                className="grid size-[29px] shrink-0 place-items-center rounded-[7px] bg-accent-fill text-ink-on-accent"
              >
                <Icon path={notepad} size={18} />
              </span>
              <span className="flex min-h-11 min-w-0 flex-1 items-center gap-2 py-1 pr-4">
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-body text-ink-primary">{s('bipe_self_chat_title')}</span>
                  <span className="truncate text-subheadline text-ink-secondary">{s('bipe_self_chat_subtitle')}</span>
                </span>
                {openingNotes && <Spinner />}
              </span>
            </button>
          </li>
        </Section>
        {notesFailed && (
          <Alert
            title={s('bipe_self_chat_error')}
            action={s('bipe_app_retry')}
            destructive={false}
            onCancel={() => setNotesFailed(false)}
            onAction={startNotes}
          />
        )}
        <Section header={s('bipe_settings_section')}>
          {groups.map((g) => {
            const summary = summaries[g.key];
            return (
            <li key={g.key}>
              <button
                type="button"
                className="flex w-full items-center gap-3 pl-4 text-left hover:bg-surface-sunken"
                onClick={() => setOpen(g)}
              >
                <span
                  aria-hidden
                  className="grid size-[29px] shrink-0 place-items-center rounded-[7px] bg-accent-fill text-ink-on-accent"
                >
                  {g.icon && ICONS[g.icon] && <Icon path={ICONS[g.icon]} size={18} />}
                </span>
                <span className="flex min-h-11 min-w-0 flex-1 items-center gap-2 py-1 pr-4 [li:not(:last-child)_&]:border-b-[0.5px] [li:not(:last-child)_&]:border-line-subtle">
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className="truncate text-body text-ink-primary">{s(key(g.title))}</span>
                      {summary?.needsAttention && <Icon path={warningCircle} size={16} className="shrink-0 text-trust-caution-ink" />}
                    </span>
                    {summary && (
                      <span className={`text-subheadline ${summary.needsAttention ? 'text-trust-caution-ink' : 'text-ink-secondary'}`}>
                        {summary.text}
                      </span>
                    )}
                  </span>
                  <Icon path={caretRight} size={16} className="text-glyph-secondary" />
                </span>
              </button>
            </li>
            );
          })}
        </Section>
      </div>
    </div>
  );
}

function GroupScreen({ group, onBack }: { group: Group; onBack: () => void }) {
  const [signingOut, setSigningOut] = useState<'one' | 'all'>();
  const accountCount = useAccounts().accounts.list.length;
  const [showsEncryption, setShowsEncryption] = useState(false);
  const [sub, setSub] = useState<'profile' | 'colors' | { device: DeviceRow }>();
  const client = useClient();
  const encryption = useEncryptionState(client);
  const isDevices = group.key === 'devices';
  const deviceList = useDeviceList(client, isDevices);
  if (showsEncryption) return <EncryptionSheet state={encryption} onClose={() => setShowsEncryption(false)} />;
  if (sub === 'profile') return <ProfileEdit onBack={() => setSub(undefined)} />;
  if (sub === 'colors') return <ColorsScreen onBack={() => setSub(undefined)} />;
  if (typeof sub === 'object') {
    const rows = deviceList.list.phase === 'loaded' ? deviceList.list.rows : [];
    // A device that left the list keeps the row it was opened with.
    const row = rows.find((r) => r.deviceId === sub.device.deviceId) ?? sub.device;
    return <DeviceDetail row={row} rows={rows} onBack={() => setSub(undefined)} />;
  }
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      {signingOut && <SignOutAlert all={signingOut === 'all'} onClose={() => setSigningOut(undefined)} />}
      <NavBar title={s(key(group.title))} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pt-2 pb-6">
        {/* Spec linked-devices, read-only in a browser: the device rows come first in the group. */}
        {isDevices && (
          <DeviceSections list={deviceList.list} onRetry={deviceList.retry} onOpen={(device) => setSub({ device })} />
        )}
        {webSections(group).map((section) => (
          <Section
            key={section.key}
            // The device rows above carry the header of section devices.
            header={'title' in section && section.title && !(isDevices && section.key === 'devices') ? s(key(section.title)) : undefined}
            footer={footerOf(section)}
          >
            {section.rows.map((row) =>
              row.key === 'namePhoto' || row.key === 'colors' ? (
                <li key={row.key}>
                  <button
                    type="button"
                    className="flex min-h-11 w-full items-center gap-2 px-4 text-left hover:bg-surface-sunken"
                    onClick={() => setSub(row.key === 'namePhoto' ? 'profile' : 'colors')}
                  >
                    <span className="min-w-0 flex-1 text-body text-ink-primary">{s(key(row.title))}</span>
                    <Icon path={caretRight} size={16} className="text-glyph-secondary" />
                  </button>
                </li>
              ) : row.key === 'appearance' ? (
                <AppearanceRows key={row.key} />
              ) : row.key === 'oldMessages' ? (
                // Settings row account.oldMessages (Sua conta › Aparelhos) opens the encryption-status sheet,
                // with this browser's verification state as its value.
                <li key={row.key}>
                  <button
                    type="button"
                    className="flex min-h-11 w-full items-center gap-2 px-4 text-left hover:bg-surface-sunken"
                    onClick={() => setShowsEncryption(true)}
                  >
                    <span className="min-w-0 flex-1 text-body text-ink-primary">{s(key(row.title))}</span>
                    <span className="text-body text-ink-secondary">
                      {encryption.verification === VerificationState.Verified
                        ? s('bipe_web_verification_verified')
                        : encryption.verification === VerificationState.Unverified
                          ? s('bipe_web_verification_unverified')
                          : ''}
                    </span>
                    <Icon path={caretRight} size={16} className="text-glyph-secondary" />
                  </button>
                </li>
              ) : row.key === 'mural' ? (
                <MuralSwitchRow key={row.key} title={s(key(row.title))} />
              ) : LOCAL_SWITCHES[row.key] ? (
                <SwitchRow key={row.key} title={s(key(row.title))} storageKey={LOCAL_SWITCHES[row.key]} />
              ) : row.kind === 'url' ? (
                <Row key={row.key}>
                  <a
                    className="min-w-0 flex-1 py-1 text-body text-ink-primary"
                    href={'url' in row && row.url ? urlOf(row.url) : undefined}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {s(key(row.title))}
                  </a>
                </Row>
              ) : (
                <Fragment key={row.key}>
                  <Row>
                    <button
                      type="button"
                      className="min-w-0 flex-1 py-1 text-left text-body text-trust-danger-ink"
                      onClick={() => setSigningOut('one')}
                    >
                      {s(key(row.title))}
                    </button>
                  </Row>
                  {/* ADR 0225: a browser that holds several accounts offers to sign out of all of them. */}
                  {accountCount > 1 && (
                    <Row>
                      <button
                        type="button"
                        className="min-w-0 flex-1 py-1 text-left text-body text-trust-danger-ink"
                        onClick={() => setSigningOut('all')}
                      >
                        {s('bipe_web_sign_out_all')}
                      </button>
                    </Row>
                  )}
                </Fragment>
              ),
            )}
          </Section>
        ))}
      </div>
    </div>
  );
}

// The section footer, or the footer of its one row (the apps show a row footer under its section).
function footerOf(section: ReturnType<typeof webSections>[number]): string | undefined {
  if ('footer' in section && section.footer) return s(key(section.footer));
  const row = section.rows.length === 1 ? section.rows[0] : undefined;
  return row && 'footer' in row && row.footer ? s(key(row.footer)) : undefined;
}

/**
 * Spec sign-out, for a browser: a confirmation, with the history warning when no other device holds the
 * message keys (Encryption.isLastDevice) and the account has no recovery key. The phone's ways-back list is
 * about getting the account back on a phone; a browser comes back by scanning a new code, so it is left out.
 */

function SignOutAlert({ all, onClose }: { all: boolean; onClose: () => void }) {
  const client = useClient();
  const accounts = useAccounts();
  const [onlyCopy, setOnlyCopy] = useState(false);
  const [phase, setPhase] = useState<'confirm' | 'signingOut' | 'failed'>('confirm');
  useEffect(() => {
    let stopped = false;
    const encryption = client.encryption();
    encryption
      .isLastDevice()
      .then((last) => !stopped && setOnlyCopy(last && encryption.recoveryState() !== RecoveryState.Enabled))
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [client]);
  return (
    <Alert
      title={all ? s('bipe_web_sign_out_all') : s('bipe_account_sign_out')}
      action={
        phase === 'signingOut'
          ? s('bipe_app_signing_out')
          : all
            ? s('bipe_web_sign_out_all')
            : s('bipe_account_sign_out')
      }
      busy={phase === 'signingOut'}
      onCancel={onClose}
      onAction={() => {
        setPhase('signingOut');
        (all ? accounts.signOutAll() : accounts.signOut()).catch((e: unknown) => {
          console.warn('[trace] sign-out failed', e);
          setPhase('failed');
        });
      }}
    >
      {onlyCopy && <p>{s('bipe_web_sign_out_history')}</p>}
      {phase === 'failed' && <p className="text-trust-danger-ink">{s('bipe_error_generic')}</p>}
    </Alert>
  );
}

// Tema (settings chats.look.appearance): Igual ao computador, Claro, Escuro, as one choice row each.
function AppearanceRows() {
  const value = useSetting('appearance');
  const options: [Appearance, string][] = [
    ['system', s('bipe_web_appearance_system')],
    ['light', s('bipe_chats_appearance_light')],
    ['dark', s('bipe_chats_appearance_dark')],
  ];
  return (
    <>
      {options.map(([v, label]) => (
        <Row key={v}>
          <button
            type="button"
            role="radio"
            aria-checked={value === v}
            className="flex min-w-0 flex-1 items-center gap-2 py-1 text-left"
            onClick={() => writeSetting('appearance', v)}
          >
            <span className="flex-1 text-body text-ink-primary">{label}</span>
            {value === v && <Icon path={check} size={18} className="text-accent-ink" />}
          </button>
        </Row>
      ))}
    </>
  );
}

// Cores (spec app-colors; AppColorsView.swift): the palettes of tokens appThemes, each a swatch in its own
// accent, then every chat background in the shared catalogue.
function ColorsScreen({ onBack }: { onBack: () => void }) {
  const palette = useSetting('palette');
  const background = useSetting('chatBackground');
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_chats_colors')} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pt-2 pb-6">
        <Section header={s('bipe_chats_colors')} footer={s('bipe_chats_colors_footer')}>
          {PALETTES.map((p) => (
            <Row
              key={p}
              leading={
                <span
                  // The default palette has no data-palette rule; its accent.fill is acai step 1100 in both schemes.
                  data-palette={p === 'acai' ? undefined : p}
                  className={`size-[29px] rounded-full ${p === 'acai' ? 'bg-[var(--bipe-acai-1100)]' : 'bg-accent-fill'}`}
                />
              }
            >
              <button
                type="button"
                role="radio"
                aria-checked={palette === p}
                className="flex min-w-0 flex-1 items-center gap-2 py-1 text-left"
                onClick={() => {
                  writeSetting('palette', p);
                  syncedSettingChanged('palette');
                }}
              >
                <span className="flex-1 text-body text-ink-primary">{s(key(`bipe_colors_${p}`))}</span>
                {palette === p && <Icon path={check} size={18} className="text-accent-ink" />}
              </button>
            </Row>
          ))}
        </Section>
        <Section header={s('bipe_chats_background')} footer={s('bipe_chats_background_footer')}>
          {BACKGROUNDS.map((b) => (
            <Row key={b}>
              <button
                type="button"
                role="radio"
                aria-checked={background === b}
                className="flex min-w-0 flex-1 items-center gap-2 py-1 text-left"
                onClick={() => {
                  writeSetting('chatBackground', b);
                  syncedSettingChanged('chatBackground');
                }}
              >
                <span className="flex-1 text-body text-ink-primary">{s(key(`bipe_chats_background_${b}`))}</span>
                {background === b && <Icon path={check} size={18} className="text-accent-ink" />}
              </button>
            </Row>
          ))}
        </Section>
      </div>
    </div>
  );
}

// Nome e foto (spec profile-edit, simplified): the display name (Client.setDisplayName) and the photo
// (Client.uploadAvatar, Client.removeAvatar).
function ProfileEdit({ onBack }: { onBack: () => void }) {
  const client = useClient();
  const { remember } = useAccounts();
  const [name, setName] = useState('');
  const [oldName, setOldName] = useState('');
  const [avatarUrl, setAvatarUrl] = useState<string>();
  const [photo, setPhoto] = useState<File | null>();
  const [preview, setPreview] = useState<string>();
  const [phase, setPhase] = useState<'editing' | 'saving' | 'failed' | 'saved'>('editing');
  useEffect(() => {
    let stopped = false;
    Promise.all([client.displayName().catch(() => ''), client.avatarUrl().catch(() => undefined)]).then(([n, a]) => {
      if (stopped) return;
      setName(n ?? '');
      setOldName(n ?? '');
      setAvatarUrl(a ?? undefined);
    });
    return () => {
      stopped = true;
    };
  }, [client]);
  useEffect(() => {
    if (!photo) return setPreview(undefined);
    const url = URL.createObjectURL(photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);
  const save = async () => {
    setPhase('saving');
    try {
      if (name.trim() && name.trim() !== oldName) await client.setDisplayName(name.trim());
      if (photo) await client.uploadAvatar(photo.type || 'image/jpeg', await photo.arrayBuffer());
      else if (photo === null) await client.removeAvatar();
      const fresh = await client.avatarUrl().catch(() => undefined);
      remember({ name: name.trim() || oldName, avatarUrl: fresh ?? undefined });
      setOldName(name.trim());
      setPhoto(undefined);
      setAvatarUrl(fresh ?? undefined);
      setPhase('saved');
    } catch (e) {
      console.warn('[trace] profile save failed', e);
      setPhase('failed');
    }
  };
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_settings_profile')} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pt-2 pb-6">
        <div className="flex flex-col items-center gap-2 py-2">
          {preview ? (
            <img src={preview} alt="" className="size-24 rounded-full object-cover" />
          ) : (
            <Avatar
              name={name || oldName}
              size={96}
              mxcUrl={photo === null ? undefined : avatarUrl}
              userId={client.userId()}
            />
          )}
          <label className="cursor-pointer text-body text-accent-ink">
            {s('bipe_app_group_photo_choose')}
            <input
              type="file"
              accept="image/*"
              hidden
              onChange={(e) => e.target.files?.[0] && setPhoto(e.target.files[0])}
            />
          </label>
          {(avatarUrl || photo) && (
            <button type="button" className="text-body text-trust-danger-ink" onClick={() => setPhoto(null)}>
              {s('bipe_app_group_photo_remove')}
            </button>
          )}
        </div>
        <Section>
          <Row>
            <input
              className="min-h-11 min-w-0 flex-1 bg-transparent text-body text-ink-primary outline-none"
              aria-label={s('bipe_profile_display_name')}
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                setPhase('editing');
              }}
            />
          </Row>
        </Section>
        {phase === 'failed' && <p className="px-4 text-footnote text-trust-danger-ink">{s('bipe_app_save_error')}</p>}
        <button
          type="button"
          disabled={phase === 'saving' || !name.trim()}
          className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent disabled:opacity-50"
          onClick={() => void save()}
        >
          {phase === 'saving' ? <Spinner /> : s('bipe_app_save')}
        </button>
      </div>
    </div>
  );
}

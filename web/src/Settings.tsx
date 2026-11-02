// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Settings for this browser (spec settings-root, SettingsRootView.swift and SettingsGroupView.swift).
// The groups, sections and rows come from registries settings, in its order. The web shows only the
// rows that work in a browser: url rows (pages on bipe.chat) and Sair deste aparelho. A group with no
// such row is left out. The root rows are the iPhone Settings layout: a 29 px accent.fill tile with an
// 18 px ink.onAccent glyph, the title and a chevron.

import { Fragment, useEffect, useState } from 'react';
import { useClient } from './media';
import { caretRight, chatCircle, bell, info, lockSimple, userCircle } from './phosphor/icons';
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
  info,
};

const key = (k: string) => k as StringKey;

// The rows of switches the web keeps itself, in this browser, under the key the apps use on the phone.
const LOCAL_SWITCHES: Record<string, string> = { readReceipts: 'bipe.settings.readReceipts' };

function webRow(row: SettingsRow): boolean {
  return (
    row.kind === 'url' || row.key === 'signOut' || row.key === 'oldMessages' || LOCAL_SWITCHES[row.key] !== undefined
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
    <Row>
      <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
        <span className="min-w-0 flex-1 text-body text-ink-primary">{title}</span>
        <input
          type="checkbox"
          role="switch"
          className="peer sr-only"
          checked={on}
          onChange={(e) => {
            setOn(e.target.checked);
            try {
              localStorage.setItem(storageKey, String(e.target.checked));
            } catch {
              // Private windows can refuse storage; the switch then lasts for this page only.
            }
          }}
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
  return group.sections
    .map((section) => ({ ...section, rows: section.rows.filter(webRow) }))
    .filter((section) => section.rows.length > 0);
}

function urlOf(name: string): string | undefined {
  const urls = settings.urls as Record<string, { pt: string; en: string }>;
  return urls[name]?.[locale === 'en' ? 'en' : 'pt'];
}

export function Settings({
  group,
  onGroup,
  onClose,
}: {
  group?: string;
  onGroup: (group: string | undefined) => void;
  onClose: () => void;
}) {
  const client = useClient();
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
        </div>
        <Section header={s('bipe_settings_section')}>
          {groups.map((g) => (
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
                <span className="flex min-h-11 min-w-0 flex-1 items-center gap-2 pr-4 [li:not(:last-child)_&]:border-b-[0.5px] [li:not(:last-child)_&]:border-line-subtle">
                  <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{s(key(g.title))}</span>
                  <Icon path={caretRight} size={16} className="text-glyph-secondary" />
                </span>
              </button>
            </li>
          ))}
        </Section>
      </div>
    </div>
  );
}

function GroupScreen({ group, onBack }: { group: Group; onBack: () => void }) {
  const [signingOut, setSigningOut] = useState<'one' | 'all'>();
  const accountCount = useAccounts().accounts.list.length;
  const [showsEncryption, setShowsEncryption] = useState(false);
  const encryption = useEncryptionState(useClient());
  if (showsEncryption) return <EncryptionSheet state={encryption} onClose={() => setShowsEncryption(false)} />;
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      {signingOut && <SignOutAlert all={signingOut === 'all'} onClose={() => setSigningOut(undefined)} />}
      <NavBar title={s(key(group.title))} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pt-2 pb-6">
        {webSections(group).map((section) => (
          <Section
            key={section.key}
            header={'title' in section && section.title ? s(key(section.title)) : undefined}
            footer={footerOf(section)}
          >
            {section.rows.map((row) =>
              row.key === 'oldMessages' ? (
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
                      {/* TODO(string): bipe_web_verification_verified and _unverified, this browser's state. */}
                      {encryption.verification === VerificationState.Verified
                        ? 'Approved'
                        : encryption.verification === VerificationState.Unverified
                          ? 'Needs approval'
                          : ''}
                    </span>
                    <Icon path={caretRight} size={16} className="text-glyph-secondary" />
                  </button>
                </li>
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
                        {/* TODO(string): bipe_web_sign_out_all, "Sair de todas as contas deste navegador". */}
                        {SIGN_OUT_ALL}
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
const SIGN_OUT_ALL = 'Sair de todas as contas deste navegador';

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
      title={all ? SIGN_OUT_ALL : s('bipe_account_sign_out')}
      action={phase === 'signingOut' ? s('bipe_app_signing_out') : all ? SIGN_OUT_ALL : s('bipe_account_sign_out')}
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
      {/* TODO(string): bipe_web_sign_out_history, the web variant of bipe_app_sign_out_history ("neste celular"). */}
      {onlyCopy && <p>Your old messages are only in this browser. Without the recovery key they do not come back.</p>}
      {phase === 'failed' && <p className="text-trust-danger-ink">{s('bipe_error_generic')}</p>}
    </Alert>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Specs encryption-status and recovery-key-enter (EncryptionStatusModel.swift, EncryptionStatusView.swift,
// RecoveryKeyEnterView.swift). After the QR sign-in the phone sends its secrets (syncingSecrets), so this
// browser is usually verified. When it is not, a quiet banner as the first chat-list row opens a sheet; the
// sheet offers the recovery key, which runs Encryption.recover and brings the message history back.

import { useEffect, useState } from 'react';
import { BackupState, RecoveryState, VerificationState, type ClientLike as Client } from './generated/matrix_sdk_ffi';
import { useClient } from './media';
import { info as infoIcon, x } from './phosphor/icons';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { NavBar } from './ui/List';
import { Spinner } from './ui/Spinner';

export type EncryptionState = {
  verification: VerificationState;
  recovery: RecoveryState;
  backup: BackupState;
  hasOtherDevices: boolean;
};

/** Action observe: the three SDK states and whether another device can approve this one. */
export function useEncryptionState(client: Client): EncryptionState {
  const encryption = client.encryption();
  const [state, setState] = useState<EncryptionState>(() => ({
    verification: encryption.verificationState(),
    recovery: encryption.recoveryState(),
    backup: encryption.backupState(),
    hasOtherDevices: false,
  }));
  useEffect(() => {
    let stopped = false;
    const set = (patch: Partial<EncryptionState>) => !stopped && setState((prev) => ({ ...prev, ...patch }));
    const handles = [
      encryption.verificationStateListener({ onUpdate: (verification) => set({ verification }) }),
      encryption.recoveryStateListener({ onUpdate: (recovery) => set({ recovery }) }),
      encryption.backupStateListener({ onUpdate: (backup) => set({ backup }) }),
    ];
    encryption
      .hasDevicesToVerifyAgainst()
      .then((hasOtherDevices) => set({ hasOtherDevices }))
      .catch(() => undefined);
    return () => {
      stopped = true;
      handles.forEach((h) => h.cancel());
    };
    // One encryption object per client.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client]);
  return state;
}

const keyUsable = (recovery: RecoveryState) =>
  recovery === RecoveryState.Enabled || recovery === RecoveryState.Incomplete;

/** The banner condition: unverified, and the account has history elsewhere (notes of encryption-status). */
export function needsApproval(state: EncryptionState): boolean {
  return state.verification === VerificationState.Unverified && (state.hasOtherDevices || keyUsable(state.recovery));
}

const DISMISSED = 'bipe.encryption.bannerDismissedAt';
const WEEK = 7 * 24 * 3600 * 1000;

export function bannerDismissed(): boolean {
  try {
    return Date.now() - Number(localStorage.getItem(DISMISSED) ?? 0) < WEEK;
  } catch {
    return false;
  }
}

// The banner, first row of the chat list (MZ, 25 Oct 2026): the line, then Agora não.
export function EncryptionBanner({ onOpen, onDismiss }: { onOpen: () => void; onDismiss: () => void }) {
  return (
    <li className="mx-4 mb-2 flex items-start gap-3 rounded-xl bg-accent-wash p-3">
      <Icon path={infoIcon} size={22} className="mt-0.5 shrink-0 text-accent-ink" />
      <button type="button" className="min-w-0 flex-1 text-left text-subheadline text-ink-primary" onClick={onOpen}>
        {s('bipe_web_history_banner')}
      </button>
      <button
        type="button"
        className="grid size-8 shrink-0 place-items-center rounded-full text-glyph-secondary"
        aria-label={s('bipe_fallback_skip')}
        title={s('bipe_fallback_skip')}
        onClick={() => {
          try {
            localStorage.setItem(DISMISSED, String(Date.now()));
          } catch {
            // Without storage the banner only hides for this page.
          }
          onDismiss();
        }}
      >
        <Icon path={x} size={14} />
      </button>
    </li>
  );
}

/** The sheet: the note and the ways that apply; the recovery key opens its screen in place. */
export function EncryptionSheet({ state, onClose }: { state: EncryptionState; onClose: () => void }) {
  const [enteringKey, setEnteringKey] = useState(false);
  if (enteringKey) return <RecoveryKeyEnter onBack={() => setEnteringKey(false)} onDone={onClose} />;
  const canUseKey = keyUsable(state.recovery);
  return (
    <div className="flex h-full min-h-0 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_app_history_title')} onBack={onClose} backLabel={s('bipe_app_close')} />
      <div className="mx-auto flex w-full max-w-[480px] flex-col gap-4 overflow-y-auto p-4">
        {state.verification === VerificationState.Verified ? (
          <p className="text-body text-ink-primary">{s('bipe_web_encryption_verified')}</p>
        ) : (
          <>
            <p className="text-body text-ink-primary">{s('bipe_web_recovery_history_note')}</p>
            {canUseKey && (
              <button
                type="button"
                className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent"
                onClick={() => setEnteringKey(true)}
              >
                {s('bipe_app_history_use_key')}
              </button>
            )}
            {!canUseKey && !state.hasOtherDevices && (
              <p className="text-subheadline text-ink-secondary">{s('bipe_web_history_none')}</p>
            )}
          </>
        )}
      </div>
    </div>
  );
}

type Phase = 'editing' | 'checking' | 'wrongKey' | 'done' | 'failed';

// Spec recovery-key-enter: the key field (spaces and line breaks ignored, never logged), Continuar,
// Não tenho a chave. Never offers an identity reset (ADR 0073).
function RecoveryKeyEnter({ onBack, onDone }: { onBack: () => void; onDone: () => void }) {
  const client = useClient();
  const [key, setKey] = useState('');
  const [phase, setPhase] = useState<Phase>('editing');
  const clean = key.replace(/\s+/g, ' ').trim();
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-base">
      <NavBar title={s('bipe_app_recovery_key_enter_title')} onBack={onBack} />
      <form
        className="mx-auto flex w-full max-w-[480px] flex-col gap-4 overflow-y-auto p-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!clean || phase === 'checking') return;
          setPhase('checking');
          client
            .encryption()
            .recover(clean)
            .then(() => {
              setPhase('done');
              setTimeout(onDone, 1500);
            })
            .catch((err: unknown) => {
              // The SDK says a wrong key apart from a network failure only in its message.
              const text = String((err as { message?: string })?.message ?? err);
              setPhase(/network|connect|fetch/i.test(text) ? 'failed' : 'wrongKey');
            });
        }}
      >
        <p className="text-body text-ink-secondary">{s('bipe_web_recovery_key_enter_body')}</p>
        <textarea
          className="min-h-24 resize-none rounded-xl bg-surface-raised p-3 font-mono text-body text-ink-primary outline-none focus-visible:outline-2 focus-visible:outline-accent-fill"
          value={key}
          autoComplete="off"
          spellCheck={false}
          autoFocus
          aria-label={s('bipe_app_recovery_key_enter_title')}
          onChange={(e) => {
            setKey(e.target.value);
            if (phase === 'wrongKey' || phase === 'failed') setPhase('editing');
          }}
        />
        {phase === 'wrongKey' && (
          <p className="text-subheadline text-trust-danger-ink">{s('bipe_app_recovery_key_wrong')}</p>
        )}
        {phase === 'failed' && <p className="text-subheadline text-trust-danger-ink">{s('bipe_error_network')}</p>}
        {phase === 'done' && (
          <p className="text-subheadline text-ink-primary">{s('bipe_app_recovery_key_enter_done')}</p>
        )}
        {phase === 'checking' ? (
          <span className="flex items-center justify-center gap-2 text-subheadline text-ink-secondary">
            <Spinner />
            {s('bipe_app_recovery_key_checking')}
          </span>
        ) : (
          <button
            type="submit"
            disabled={!clean || phase === 'done'}
            className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent disabled:opacity-50"
          >
            {s('bipe_action_continue')}
          </button>
        )}
        <button type="button" className="min-h-11 text-body text-accent-ink" onClick={onBack}>
          {s('bipe_app_recovery_key_none')}
        </button>
      </form>
    </div>
  );
}

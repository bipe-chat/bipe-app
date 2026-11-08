// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// QR sign-in from the phone (MSC4108, ADR 0116 and 0118). The page shows the QR code, the phone scans
// it and shows a two-digit check code, the person types it here, the phone approves.
// Layout: LinkPhoneShowView.swift (spec link-phone-show), the same flow with the roles swapped, beside the
// açaí cover of onboarding-welcome (Mac: MacLinkCover.swift): a large title, the code on a white field with a
// quiet zone in an açaí frame, the check code in large monospaced digits on surface.raised.

import { useEffect, useRef, useState, type ReactNode } from 'react';
import QRCode from 'qrcode';
import {
  GeneratedQrLoginProgress,
  type CheckCodeSenderLike as CheckCodeSender,
  type ClientLike as Client,
} from './generated/matrix_sdk_ffi';
import { newClient, oauthConfiguration, type Slot } from './sdk';
import { BipeMark } from './ui/BipeMark';
import { s } from './ui/i18n';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

// The steps (bipe_web_signin_*): ready shows the three instructions beside the code; scanned asks for the
// check code; then approve on the phone, keys, done.
// Before the scan the code changes this often (Synapse keeps a rendezvous session 60 s, see the effect below).
const REFRESH_MS = 25_000;
// The planned end of a code that nobody scanned in time.
class Refresh extends Error {}

type Step = 'starting' | 'ready' | 'checkCode' | 'approve' | 'keys' | 'expired' | 'done';
// slot: the new account's empty slot. onCancel: Adicionar conta from an account goes back to it.
export function QrLogin({
  slot,
  onDone,
  onError,
  onCancel,
}: {
  slot: Slot;
  onDone: (c: Client) => void;
  onError: (m: string) => void;
  onCancel?: () => void;
}) {
  const cancelled = useRef(false);
  useEffect(
    () => () => {
      cancelled.current = true;
    },
    [],
  );
  const canvas = useRef<HTMLCanvasElement>(null);
  const [step, setStep] = useState<Step>('starting');
  const [qr, setQr] = useState<ArrayBuffer>();
  const [checkSender, setCheckSender] = useState<CheckCodeSender>();
  const [code, setCode] = useState('');
  // After the scan the code has done its work; the later steps show a spinner in its place.
  const [wasScanned, setWasScanned] = useState(false);
  const sent = useRef(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    // Synapse deletes the rendezvous session 60 s after the code is made, and the phone's steps after the scan
    // (check code, warning, owner check, consent) must fit in what is left. So before the scan this page makes a
    // new code every REFRESH_MS, and a scan always has at least 35 s. If the session still ends after the scan
    // (HumanQrLoginError.NotFound or Expired), the page shows a new code in place of the error.
    const attempt = async (): Promise<Client> => {
      const client = await newClient(slot);
      const handler = client.newLoginWithQrCodeHandler(oauthConfiguration());
      const abort = new AbortController();
      let refresh: ReturnType<typeof setTimeout> | undefined;
      try {
        await handler.generate(
          {
            onUpdate(state) {
              console.info('[trace] qr progress', (state as { tag?: string }).tag ?? state);
              if (GeneratedQrLoginProgress.QrReady.instanceOf(state)) {
                setQr(state.inner.qrCode.toBytes());
                setStep('ready');
                refresh = setTimeout(() => abort.abort(), REFRESH_MS);
              } else if (GeneratedQrLoginProgress.QrScanned.instanceOf(state)) {
                clearTimeout(refresh);
                setWasScanned(true);
                setCheckSender(state.inner.checkCodeSender);
                setStep('checkCode');
              } else if (GeneratedQrLoginProgress.WaitingForToken.instanceOf(state)) {
                setCheckSender(undefined);
                setStep('approve');
              } else if (GeneratedQrLoginProgress.SyncingSecrets.instanceOf(state)) {
                setStep('keys');
              } else if (GeneratedQrLoginProgress.Done.instanceOf(state)) {
                setStep('done');
              }
            },
          },
          { signal: abort.signal },
        );
      } catch (e) {
        if (abort.signal.aborted) throw new Refresh();
        throw e;
      } finally {
        clearTimeout(refresh);
      }
      return client;
    };
    (async () => {
      for (;;) {
        try {
          const client = await attempt();
          if (!cancelled.current) onDone(client);
          return;
        } catch (e) {
          if (cancelled.current) return;
          const expired = !(e instanceof Refresh);
          if (expired && !/\b(NotFound|Expired)\b/.test(describe(e))) throw e;
          setQr(undefined);
          setWasScanned(false);
          setCheckSender(undefined);
          setCode('');
          sent.current = false;
          setStep(expired ? 'expired' : 'starting');
        }
      }
    })().catch((e) => onError(describe(e)));
  }, [onDone, onError, slot]);

  useEffect(() => {
    const target = canvas.current;
    if (!qr || !target) return;
    void QRCode.toCanvas(target, [{ data: new Uint8ClampedArray(qr), mode: 'byte' }], {
      errorCorrectionLevel: 'L',
      margin: 2,
      scale: 6,
    }).then(() => {
      // toCanvas writes a fixed pixel width and height into the inline style. That beats w-full, so the code sat at
      // the left of the white field. Without them the canvas fills the field and stays square.
      target.style.removeProperty('width');
      target.style.removeProperty('height');
    });
  }, [qr]);

  const showsQr = qr !== undefined && !wasScanned;

  return (
    // The açaí cover of the phones' onboarding-welcome (bipe-chat/bipe#1308) at the start side, the steps beside it;
    // on a narrow window the cover sits on top.
    <section className="flex h-full flex-col overflow-y-auto md:flex-row">
      <Cover>
        {onCancel && (
          <button
            type="button"
            className="-ml-3 min-h-11 self-start rounded-full px-3 text-body font-semibold text-ink-on-accent hover:bg-ink-on-accent/15"
            onClick={() => {
              cancelled.current = true;
              onCancel();
            }}
          >
            {s('bipe_app_cancel')}
          </button>
        )}
      </Cover>
      <div className="flex flex-1 flex-col items-center bg-gradient-to-b from-accent-wash to-surface-base to-50% px-6 py-10 md:px-12">
        <div className="my-auto flex w-full max-w-[860px] flex-col items-center gap-10 lg:flex-row lg:items-center lg:justify-center">
          {step === 'ready' ? (
            <div className="flex max-w-[400px] flex-col gap-6">
              <h1 className="text-large-title text-ink-primary">{s('bipe_web_signin_title')}</h1>
              <ol className="flex flex-col gap-5 text-body text-ink-primary">
                <Instruction n={1}>{s('bipe_web_signin_step_open')}</Instruction>
                <Instruction n={2}>
                  <MenuPath />
                </Instruction>
                <Instruction n={3}>{s('bipe_web_signin_step_scan')}</Instruction>
              </ol>
            </div>
          ) : (
            <h1 className="text-center text-large-title text-ink-primary">{stepTitle(step)}</h1>
          )}
          {/* A white field with a quiet zone, in light and dark mode (LinkPhoneShowView.swift), in an açaí frame. */}
          <div
            className={
              showsQr
                ? 'w-full max-w-[320px] rounded-[28px] bg-accent-wash p-2.5 shadow-[0_8px_32px_color-mix(in_srgb,var(--bipe-accent-fill)_22%,transparent)]'
                : 'hidden'
            }
          >
            <div className="rounded-[20px] bg-white p-4">
              <canvas ref={canvas} className="block h-auto w-full [image-rendering:pixelated]" />
            </div>
          </div>
          {!showsQr && !checkSender && <Spinner />}
          {checkSender && (
            <form
              className="flex flex-col items-center gap-5"
              onSubmit={(e) => {
                e.preventDefault();
                const n = Number(code);
                if (!Number.isInteger(n) || n < 0 || n > 99 || code === '') return;
                // The SDK takes the check code once; a second send fails with CheckCodeAlreadySent
                // and must not end a sign-in that is still waiting for the phone.
                if (sent.current) return;
                sent.current = true;
                setCheckSender(undefined);
                setStep('approve');
                console.info('[trace] check code send', n);
                checkSender
                  .send(n)
                  .then(() => console.info('[trace] check code send resolved'))
                  .catch((err: unknown) => {
                    console.warn('[trace] check code send failed', err);
                    if (!/\bCheckCodeAlreadySent\b/.test(describe(err))) onError(describe(err));
                  });
              }}
            >
              <input
                className="w-40 rounded-2xl border-2 border-accent-fill bg-surface-raised py-3 text-center font-mono text-[48px] leading-[56px] font-bold text-ink-primary outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-fill"
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus
                maxLength={2}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                aria-label={s('bipe_web_signin_check_code_title')}
              />
              <button
                className="min-h-14 min-w-[200px] rounded-2xl bg-accent-fill px-7 text-headline text-ink-on-accent shadow-[0_6px_20px_color-mix(in_srgb,var(--bipe-accent-fill)_30%,transparent)] transition-transform hover:bg-accent-fill-pressed active:scale-[0.98] active:bg-accent-fill-pressed motion-reduce:transition-none motion-reduce:active:scale-100"
                type="submit"
              >
                {s('bipe_web_signin_check_code_send')}
              </button>
            </form>
          )}
        </div>
      </div>
    </section>
  );
}

// The marks of the cover texture: centre and side as fractions of the panel, the same four as the phones.
const TEXTURE = [
  { x: 86, y: 22, size: 50, opacity: 0.12 },
  { x: 42, y: 36, size: 20, opacity: 0.07 },
  { x: 100, y: 66, size: 30, opacity: 0.09 },
  { x: 62, y: 44, size: 10, opacity: 0.12 },
];

// The açaí cover: mark and lowercase wordmark, the welcome line, faint Bipe marks (identity.md).
function Cover({ children }: { children?: ReactNode }) {
  return (
    <aside className="relative flex min-h-[220px] shrink-0 flex-col gap-4 overflow-hidden bg-gradient-to-br from-accent-fill to-accent-fill-pressed p-8 text-ink-on-accent md:min-h-0 md:w-[360px] md:p-9">
      <div aria-hidden className="pointer-events-none absolute inset-0 [container-type:size]">
        {TEXTURE.map((m, i) => (
          <BipeMark
            key={i}
            className="absolute -translate-x-1/2 -translate-y-1/2"
            style={{
              left: `${m.x}%`,
              top: `${m.y}%`,
              width: `min(${m.size}cqw, ${m.size * 1.4}cqh)`,
              height: `min(${m.size}cqw, ${m.size * 1.4}cqh)`,
              opacity: m.opacity,
            }}
          />
        ))}
      </div>
      {children}
      <div className="relative flex items-center gap-3">
        <BipeMark className="size-11 drop-shadow-[0_3px_8px_color-mix(in_srgb,var(--bipe-accent-fill-pressed)_60%,transparent)]" />
        <span className="text-[52px] leading-none font-black tracking-tight">{s('bipe_app_name').toLowerCase()}</span>
      </div>
      <p className="relative mt-auto text-large-title font-bold">{s('bipe_onboarding_title')}</p>
    </aside>
  );
}

function stepTitle(step: Step): string {
  switch (step) {
    case 'checkCode':
      return s('bipe_web_signin_check_code_title');
    case 'approve':
      return s('bipe_web_signin_approve');
    case 'keys':
    case 'done':
      return s('bipe_web_signin_keys');
    case 'expired':
      return s('bipe_web_signin_expired');
    default:
      return s('bipe_web_loading');
  }
}

// A numbered step: the number in an accent.fill circle, then the text.
function Instruction({ n, children }: { n: number; children: ReactNode }) {
  return (
    <li className="flex items-start gap-3">
      <span
        aria-hidden
        className="grid size-8 shrink-0 place-items-center rounded-[10px] bg-gradient-to-br from-accent-fill to-accent-fill-pressed text-headline font-bold text-ink-on-accent"
      >
        {n}
      </span>
      <span className="pt-1">{children}</span>
    </li>
  );
}

// Step 2: the path to the phone's Conectar um computador, each step in bold, separated by ›, with the
// app's own names for the tab, the settings group, the screen and the action.
function MenuPath() {
  const names = [
    s('bipe_tab_you'),
    s('bipe_settings_account'),
    s('bipe_linked_devices_title'),
    s('bipe_linked_devices_link_computer'),
  ];
  const template = s('bipe_web_signin_step_path', '\u0000', '\u0001', '\u0002', '\u0003');
  return (
    <>
      {template.split(/([\u0000-\u0003])/).map((part, i) => {
        const at = part.length === 1 ? part.charCodeAt(0) : -1;
        return at >= 0 && at <= 3 ? <strong key={i}>{names[at]}</strong> : part;
      })}
    </>
  );
}

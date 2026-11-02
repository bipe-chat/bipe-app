// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// QR sign-in from the phone (MSC4108, ADR 0116 and 0118). The page shows the QR code, the phone scans
// it and shows a two-digit check code, the person types it here, the phone approves.
// Layout: LinkPhoneShowView.swift (spec link-phone-show), the same flow with the roles swapped: a
// centred column, title2 title, the code on a white field with a quiet zone, the check code in large
// monospaced digits on surface.raised.

import { useEffect, useRef, useState, type ReactNode } from 'react';
import QRCode from 'qrcode';
import {
  GeneratedQrLoginProgress,
  type CheckCodeSenderLike as CheckCodeSender,
  type ClientLike as Client,
} from './generated/matrix_sdk_ffi';
import { newClient, oauthConfiguration, rememberSession } from './sdk';
import { pending, s } from './ui/i18n';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

// The steps (bipe_web_signin_*): ready shows the three instructions beside the code; scanned asks for the
// check code; then approve on the phone, keys, done.
type Step = 'starting' | 'ready' | 'checkCode' | 'approve' | 'keys' | 'expired' | 'done';
export function QrLogin({ onDone, onError }: { onDone: (c: Client) => void; onError: (m: string) => void }) {
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
    // The rendezvous code expires unscanned (HumanQrLoginError.NotFound or
    // Expired). Before the phone scans it, make a new code instead of failing.
    let scanned = false;
    const attempt = async (): Promise<Client> => {
      const client = await newClient();
      const handler = client.newLoginWithQrCodeHandler(oauthConfiguration());
      await handler.generate({
        onUpdate(state) {
          console.info('[trace] qr progress', (state as { tag?: string }).tag ?? state);
          if (GeneratedQrLoginProgress.QrReady.instanceOf(state)) {
            setQr(state.inner.qrCode.toBytes());
            setStep('ready');
          } else if (GeneratedQrLoginProgress.QrScanned.instanceOf(state)) {
            scanned = true;
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
      });
      return client;
    };
    (async () => {
      for (;;) {
        try {
          const client = await attempt();
          await rememberSession(client);
          onDone(client);
          return;
        } catch (e) {
          if (scanned || !/\b(NotFound|Expired)\b/.test(describe(e))) throw e;
          setQr(undefined);
          setStep('expired');
        }
      }
    })().catch((e) => onError(describe(e)));
  }, [onDone, onError]);

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
    <section className="flex h-full flex-col items-center overflow-y-auto p-4">
      <div className="my-auto flex w-full max-w-[860px] flex-col items-center gap-8 md:flex-row md:items-center md:justify-center">
        {step === 'ready' ? (
          <div className="flex max-w-[400px] flex-col gap-5">
            <h1 className="text-title2 text-ink-primary">{pending('bipe_web_signin_title')}</h1>
            <ol className="flex flex-col gap-4 text-body text-ink-primary">
              <Instruction n={1}>{pending('bipe_web_signin_step_open')}</Instruction>
              <Instruction n={2}>
                <MenuPath />
              </Instruction>
              <Instruction n={3}>{pending('bipe_web_signin_step_scan')}</Instruction>
            </ol>
          </div>
        ) : (
          <h1 className="text-center text-title2 text-ink-primary">{stepTitle(step)}</h1>
        )}
        {/* A white field with a quiet zone, in light and dark mode (LinkPhoneShowView.swift). */}
        <div className={showsQr ? 'w-full max-w-[320px] rounded-2xl bg-white p-4' : 'hidden'}>
          <canvas ref={canvas} className="block h-auto w-full [image-rendering:pixelated]" />
        </div>
        {!showsQr && !checkSender && <Spinner />}
        {checkSender && (
          <form
            className="flex flex-col items-center gap-4"
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
              className="w-40 rounded-xl bg-surface-raised py-2 text-center font-mono text-[48px] leading-[56px] font-bold text-ink-primary outline-none focus-visible:outline-2 focus-visible:outline-accent-fill"
              inputMode="numeric"
              autoComplete="one-time-code"
              autoFocus
              maxLength={2}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              aria-label={pending('bipe_web_signin_check_code_title')}
            />
            <button
              className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent hover:bg-accent-fill-pressed active:bg-accent-fill-pressed"
              type="submit"
            >
              {pending('bipe_web_signin_check_code_send')}
            </button>
          </form>
        )}
      </div>
    </section>
  );
}

function stepTitle(step: Step): string {
  switch (step) {
    case 'checkCode':
      return pending('bipe_web_signin_check_code_title');
    case 'approve':
      return pending('bipe_web_signin_approve');
    case 'keys':
    case 'done':
      return pending('bipe_web_signin_keys');
    case 'expired':
      return pending('bipe_web_signin_expired');
    default:
      return pending('bipe_web_loading');
  }
}

// A numbered step: the number in an accent.fill circle, then the text.
function Instruction({ n, children }: { n: number; children: ReactNode }) {
  return (
    <li className="flex items-start gap-3">
      <span
        aria-hidden
        className="grid size-7 shrink-0 place-items-center rounded-full bg-accent-fill text-subheadline font-semibold text-ink-on-accent"
      >
        {n}
      </span>
      <span className="pt-0.5">{children}</span>
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
  const template = pending('bipe_web_signin_step_path', '\u0000', '\u0001', '\u0002', '\u0003');
  return (
    <>
      {template.split(/([\u0000-\u0003])/).map((part, i) => {
        const at = part.length === 1 ? part.charCodeAt(0) : -1;
        return at >= 0 && at <= 3 ? <strong key={i}>{names[at]}</strong> : part;
      })}
    </>
  );
}

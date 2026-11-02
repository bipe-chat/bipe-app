// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// QR sign-in from the phone (MSC4108, ADR 0116 and 0118). The page shows the QR code, the phone scans
// it and shows a two-digit check code, the person types it here, the phone approves.
// Layout: LinkPhoneShowView.swift (spec link-phone-show), the same flow with the roles swapped: a
// centred column, title2 title, the code on a white field with a quiet zone, the check code in large
// monospaced digits on surface.raised.

import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import {
  GeneratedQrLoginProgress,
  type CheckCodeSenderLike as CheckCodeSender,
  type ClientLike as Client,
} from './generated/matrix_sdk_ffi';
import { newClient, oauthConfiguration, rememberSession } from './sdk';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

// TODO(string): every step text below is spike English. The web sign-in has no strings in bipe-shared yet.
export function QrLogin({ onDone, onError }: { onDone: (c: Client) => void; onError: (m: string) => void }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [step, setStep] = useState('Starting…');
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
            setStep('Scan this code with Bipe on your phone.');
          } else if (GeneratedQrLoginProgress.QrScanned.instanceOf(state)) {
            scanned = true;
            setWasScanned(true);
            setCheckSender(state.inner.checkCodeSender);
            setStep('Type the two-digit code that your phone shows.');
          } else if (GeneratedQrLoginProgress.WaitingForToken.instanceOf(state)) {
            setCheckSender(undefined);
            setStep(`Approve on your phone. Code: ${state.inner.userCode}`);
          } else if (GeneratedQrLoginProgress.SyncingSecrets.instanceOf(state)) {
            setStep('Getting your keys from the phone…');
          } else if (GeneratedQrLoginProgress.Done.instanceOf(state)) {
            setStep('Signed in.');
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
          setStep('The code expired. Making a new one…');
        }
      }
    })().catch((e) => onError(describe(e)));
  }, [onDone, onError]);

  useEffect(() => {
    if (!qr || !canvas.current) return;
    void QRCode.toCanvas(canvas.current, [{ data: new Uint8ClampedArray(qr), mode: 'byte' }], {
      errorCorrectionLevel: 'L',
      margin: 2,
      scale: 6,
    });
  }, [qr]);

  const showsQr = qr !== undefined && !wasScanned;

  return (
    <section className="flex h-full flex-col items-center overflow-y-auto p-4">
      <div className="my-auto flex w-full max-w-[420px] flex-col items-center gap-4 text-center">
        <h1 className="text-title2 text-ink-primary">{step}</h1>
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
              setStep('Approve on your phone.');
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
              // TODO(string): the label of the check code field.
              aria-label="Check code"
            />
            <button
              className="min-h-[50px] rounded-full bg-accent-fill px-6 text-headline text-ink-on-accent hover:bg-accent-fill-pressed active:bg-accent-fill-pressed"
              type="submit"
            >
              {/* TODO(string): the button that sends the check code. */}
              Send
            </button>
          </form>
        )}
      </div>
    </section>
  );
}

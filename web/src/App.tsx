// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Bipe web (bipe-chat/bipe#900, ADR 0216): QR sign-in from the phone, the chat list, one timeline,
// send a text. The screens follow the apps' design language: bipe-shared colour tokens and strings,
// the iOS type scale, Phosphor icons. Light and dark follow the system.

import { useEffect, useState } from 'react';
import { type ClientLike as Client } from './generated/matrix_sdk_ffi';
import { Chats } from './Chats';
import { QrLogin } from './QrLogin';
import { restoreClient } from './sdk';
import { s } from './ui/i18n';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

type Phase =
  | { kind: 'loading'; note: string }
  | { kind: 'error'; message: string }
  | { kind: 'login' }
  | { kind: 'chats'; client: Client };

export function App() {
  // TODO(string): the loading note is spike text; screen readers read it, the page shows a spinner.
  const [phase, setPhase] = useState<Phase>({ kind: 'loading', note: 'Loading matrix-sdk-ffi (wasm)…' });

  useEffect(() => {
    let cancelled = false;
    restoreClient()
      .then((client) => {
        if (cancelled) return;
        setPhase(client ? { kind: 'chats', client } : { kind: 'login' });
      })
      .catch((e) => !cancelled && setPhase({ kind: 'error', message: describe(e) }));
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="h-full bg-surface-base text-ink-primary">
      {phase.kind === 'loading' && (
        <div className="grid h-full place-items-center">
          <Spinner label={phase.note} />
        </div>
      )}
      {phase.kind === 'error' && (
        <div className="mx-auto flex h-full max-w-[560px] flex-col justify-center gap-2 p-4 text-center">
          <p className="text-headline text-trust-danger-ink">{s('bipe_error_generic')}</p>
          {/* The technical detail stays on screen while the web client is in testing. */}
          <pre className="font-mono text-footnote break-words whitespace-pre-wrap text-ink-secondary">
            {phase.message}
          </pre>
        </div>
      )}
      {phase.kind === 'login' && (
        <QrLogin
          onDone={(client) => setPhase({ kind: 'chats', client })}
          onError={(message) => setPhase({ kind: 'error', message })}
        />
      )}
      {phase.kind === 'chats' && <Chats client={phase.client} />}
    </div>
  );
}

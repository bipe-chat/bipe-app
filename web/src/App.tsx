// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spike page (bipe-chat/bipe#900): QR sign-in from the phone (MSC4108,
// ADR 0116 and 0118), room list, one timeline, send one text.
// Text on this page is for the spike only and is not product copy.

import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import {
  GeneratedQrLoginProgress,
  MsgLikeKind,
  ProfileDetails,
  RoomListEntriesDynamicFilterKind,
  RoomListEntriesUpdate,
  TimelineDiff,
  TimelineItemContent,
  messageEventContentFromMarkdown,
  type CheckCodeSenderLike as CheckCodeSender,
  type ClientLike as Client,
  type RoomLike as Room,
  type RoomListEntriesWithDynamicAdaptersResultLike as RoomListEntriesWithDynamicAdaptersResult,
  type SyncServiceLike as SyncService,
  type TaskHandleLike as TaskHandle,
  type TimelineLike as Timeline,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import { forgetSession, newClient, oauthConfiguration, rememberSession, restoreClient } from './sdk';

type Phase =
  | { kind: 'loading'; note: string }
  | { kind: 'error'; message: string }
  | { kind: 'login' }
  | { kind: 'chats'; client: Client };

function describe(e: unknown): string {
  if (e instanceof Error) return `${e.name}: ${e.message}`;
  try {
    return JSON.stringify(e);
  } catch {
    return String(e);
  }
}

export function App() {
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
    <main className="mx-auto flex min-h-screen max-w-5xl flex-col gap-4 bg-white p-4 text-neutral-900">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Bipe web (spike)</h1>
        {phase.kind === 'chats' && (
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={async () => {
              await phase.client.logout().catch(() => undefined);
              await forgetSession();
              window.location.reload();
            }}
          >
            Sign out
          </button>
        )}
      </header>
      {phase.kind === 'loading' && <p>{phase.note}</p>}
      {phase.kind === 'error' && <pre className="whitespace-pre-wrap text-red-700">{phase.message}</pre>}
      {phase.kind === 'login' && (
        <QrLogin
          onDone={(client) => setPhase({ kind: 'chats', client })}
          onError={(message) => setPhase({ kind: 'error', message })}
        />
      )}
      {phase.kind === 'chats' && <Chats client={phase.client} />}
    </main>
  );
}

// The page shows the QR code, the phone scans it and shows a two-digit check
// code, the person types it here, the phone approves.
function QrLogin({ onDone, onError }: { onDone: (c: Client) => void; onError: (m: string) => void }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [step, setStep] = useState('Starting…');
  const [qr, setQr] = useState<ArrayBuffer>();
  const [checkSender, setCheckSender] = useState<CheckCodeSender>();
  const [code, setCode] = useState('');
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

  return (
    <section className="flex flex-col items-start gap-3">
      <p>{step}</p>
      <canvas ref={canvas} className={qr ? 'border' : 'hidden'} />
      {checkSender && (
        <form
          className="flex gap-2"
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
            className="w-20 rounded border px-2 py-1"
            inputMode="numeric"
            maxLength={2}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            aria-label="Check code"
          />
          <button className="rounded border px-3 py-1" type="submit">
            Send
          </button>
        </form>
      )}
    </section>
  );
}

// Applies a VectorDiff list (room list and timeline use the same shape).
type DiffVariants = Record<
  'Append' | 'Clear' | 'PushFront' | 'PushBack' | 'PopFront' | 'PopBack' | 'Insert' | 'Set' | 'Remove' | 'Truncate' | 'Reset',
  { instanceOf(v: unknown): boolean }
>;

function applyDiffs<T>(list: T[], diffs: unknown[], V: DiffVariants): T[] {
  const next = [...list];
  for (const d of diffs) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const inner = (d as any).inner;
    if (V.Append.instanceOf(d)) next.push(...inner.values);
    else if (V.Clear.instanceOf(d)) next.length = 0;
    else if (V.PushFront.instanceOf(d)) next.unshift(inner.value);
    else if (V.PushBack.instanceOf(d)) next.push(inner.value);
    else if (V.PopFront.instanceOf(d)) next.shift();
    else if (V.PopBack.instanceOf(d)) next.pop();
    else if (V.Insert.instanceOf(d)) next.splice(inner.index, 0, inner.value);
    else if (V.Set.instanceOf(d)) next[inner.index] = inner.value;
    else if (V.Remove.instanceOf(d)) next.splice(inner.index, 1);
    else if (V.Truncate.instanceOf(d)) next.length = inner.length;
    else if (V.Reset.instanceOf(d)) next.splice(0, next.length, ...inner.values);
  }
  return next;
}

function Chats({ client }: { client: Client }) {
  const [rooms, setRooms] = useState<Room[]>([]);
  const [open, setOpen] = useState<Room>();
  const [status, setStatus] = useState('Starting sync…');
  const keep = useRef<{ sync?: SyncService; entries?: RoomListEntriesWithDynamicAdaptersResult }>({});

  useEffect(() => {
    let stopped = false;
    (async () => {
      const sync = await client.syncService().finish();
      keep.current.sync = sync;
      await sync.start();
      const list = await sync.roomListService().allRooms();
      const entries = list.entriesWithDynamicAdapters(100, {
        onUpdate(updates) {
          if (!stopped) setRooms((prev) => applyDiffs(prev, updates, RoomListEntriesUpdate as unknown as DiffVariants));
        },
      });
      keep.current.entries = entries;
      entries.controller().setFilter(new RoomListEntriesDynamicFilterKind.NonLeft());
      setStatus(`Signed in as ${client.userId()}`);
    })().catch((e) => setStatus(describe(e)));
    return () => {
      stopped = true;
      void keep.current.sync?.stop();
    };
  }, [client]);

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-[280px_1fr]">
      <aside className="flex flex-col gap-1">
        <p className="text-sm text-neutral-600">{status}</p>
        <ul className="flex flex-col">
          {rooms.map((room) => (
            <li key={room.id()}>
              <button
                className={`w-full truncate rounded px-2 py-2 text-left hover:bg-neutral-100 ${open?.id() === room.id() ? 'bg-neutral-200' : ''}`}
                onClick={() => setOpen(room)}
              >
                {room.displayName() ?? room.id()}
              </button>
            </li>
          ))}
        </ul>
      </aside>
      {open ? <RoomTimeline key={open.id()} room={open} /> : <p>Pick a chat.</p>}
    </div>
  );
}

function itemText(item: TimelineItem): { key: string; who: string; text: string; own: boolean } | undefined {
  const event = item.asEvent();
  const key = item.uniqueId().id;
  if (!event) return undefined;
  const who = ProfileDetails.Ready.instanceOf(event.senderProfile)
    ? (event.senderProfile.inner.displayName ?? event.sender)
    : event.sender;
  let text = '(event)';
  if (TimelineItemContent.MsgLike.instanceOf(event.content)) {
    const kind = event.content.inner.content.kind;
    if (MsgLikeKind.Message.instanceOf(kind)) text = kind.inner.content.body;
    else if (MsgLikeKind.UnableToDecrypt.instanceOf(kind)) text = '(unable to decrypt)';
    else if (MsgLikeKind.Redacted.instanceOf(kind)) text = '(deleted)';
  }
  return { key, who, text, own: event.isOwn };
}

function RoomTimeline({ room }: { room: Room }) {
  const [items, setItems] = useState<TimelineItem[]>([]);
  const [draft, setDraft] = useState('');
  const [note, setNote] = useState('');
  const timeline = useRef<Timeline>(undefined);
  const listener = useRef<TaskHandle>(undefined);

  useEffect(() => {
    let stopped = false;
    (async () => {
      const t = await room.timeline();
      timeline.current = t;
      listener.current = await t.addListener({
        onUpdate(diffs) {
          if (!stopped) setItems((prev) => applyDiffs(prev, diffs, TimelineDiff as unknown as DiffVariants));
        },
      });
      await t.paginateBackwards(30);
    })().catch((e) => setNote(describe(e)));
    return () => {
      stopped = true;
      listener.current?.cancel();
    };
  }, [room]);

  const rows = items.map(itemText).filter((r) => r !== undefined);

  return (
    <section className="flex min-h-[60vh] flex-col gap-2">
      <h2 className="font-semibold">{room.displayName() ?? room.id()}</h2>
      <ol className="flex flex-1 flex-col gap-1 overflow-y-auto">
        {rows.map((r) => (
          <li key={r.key} className={r.own ? 'self-end text-right' : 'self-start'}>
            <span className="block text-xs text-neutral-500">{r.who}</span>
            <span className="inline-block rounded bg-neutral-100 px-2 py-1">{r.text}</span>
          </li>
        ))}
      </ol>
      {note && <p className="text-sm text-red-700">{note}</p>}
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          const text = draft.trim();
          if (!text || !timeline.current) return;
          setDraft('');
          timeline.current.send(messageEventContentFromMarkdown(text)).catch((err: unknown) => setNote(describe(err)));
        }}
      >
        <input
          className="flex-1 rounded border px-2 py-1"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          aria-label="Message"
        />
        <button className="rounded border px-3 py-1" type="submit">
          Send
        </button>
      </form>
    </section>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec timeline-media (TimelineMediaRow.swift): photos and videos as a box at most 260 px wide with 12 px
// corners, its aspect ratio no taller than 1 : 1.4, the caption under it; a file as a card with a 40 px
// circle, the name and the size. A tap on a photo or video opens the viewer (media-viewer, simplified);
// a tap on a file saves it.

import { useEffect, useRef, useState } from 'react';
import { MessageType, type MediaSourceLike, type MessageType as MessageTypeValue } from './generated/matrix_sdk_ffi';
import { objectUrlOfContent, previewOf, useClient, useObjectUrl } from './media';
import { arrowClockwise, file, pause, play, x } from './phosphor/icons';
import { locale, s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';

export type MediaInfo = {
  kind: 'image' | 'video' | 'file' | 'audio';
  source: MediaSourceLike;
  thumbnail?: MediaSourceLike;
  filename: string;
  caption?: string;
  mimetype?: string;
  width?: number;
  height?: number;
  size?: number;
  /** audio: a voice message (MSC3245 voice field) rather than an audio file. */
  isVoice?: boolean;
  /** audio: the sender's waveform (MSC1767, values 0 to 1024). */
  waveform?: number[];
  /** Milliseconds. */
  duration?: number;
};

const num = (v: bigint | undefined) => (v === undefined ? undefined : Number(v));

/** The media of a message, or undefined for text and the kinds this page does not draw. */
export function mediaOf(type: MessageTypeValue): MediaInfo | undefined {
  if (MessageType.Image.instanceOf(type)) {
    const c = type.inner.content;
    return {
      kind: 'image',
      source: c.source,
      thumbnail: c.info?.thumbnailSource,
      filename: c.filename,
      caption: c.caption,
      mimetype: c.info?.mimetype,
      width: num(c.info?.width),
      height: num(c.info?.height),
      size: num(c.info?.size),
    };
  }
  if (MessageType.Video.instanceOf(type)) {
    const c = type.inner.content;
    return {
      kind: 'video',
      source: c.source,
      thumbnail: c.info?.thumbnailSource,
      filename: c.filename,
      caption: c.caption,
      mimetype: c.info?.mimetype,
      width: num(c.info?.width),
      height: num(c.info?.height),
      size: num(c.info?.size),
      duration: c.info?.duration,
    };
  }
  if (MessageType.Audio.instanceOf(type)) {
    const c = type.inner.content;
    return {
      kind: 'audio',
      source: c.source,
      filename: c.filename,
      caption: c.caption,
      mimetype: c.info?.mimetype,
      size: num(c.info?.size),
      duration: c.audio?.duration ?? c.info?.duration,
      waveform: c.audio?.waveform,
      isVoice: c.voice !== undefined,
    };
  }
  if (MessageType.File.instanceOf(type)) {
    const c = type.inner.content;
    return {
      kind: 'file',
      source: c.source,
      filename: c.filename,
      caption: c.caption,
      mimetype: c.info?.mimetype,
      size: num(c.info?.size),
    };
  }
  return undefined;
}

const MAX_WIDTH = 260;
// Device pixels for the preview: the 260 px box on a 3x screen.
const PREVIEW = 800;

export type Upload = { percent: number; cancel: () => void };

export function TimelineMediaRow({ media, upload }: { media: MediaInfo; upload?: Upload }) {
  const [viewing, setViewing] = useState(false);
  const caption = media.caption?.trim();
  return (
    <div className="flex flex-col gap-1">
      {media.kind === 'audio' ? (
        <VoiceRow media={media} />
      ) : media.kind === 'file' ? (
        <FileCard media={media} upload={upload} />
      ) : (
        <Visual media={media} upload={upload} onOpen={() => setViewing(true)} />
      )}
      {caption && media.kind === 'audio' && media.isVoice && (
        // ADR 0023: the caption of a voice message is the sender's transcript.
        <span className="text-caption font-semibold text-ink-secondary">{s('bipe_voice_transcript_title')}</span>
      )}
      {caption && <p className="max-w-[260px] text-body break-words whitespace-pre-wrap text-ink-primary">{caption}</p>}
      {viewing && <Viewer media={media} onClose={() => setViewing(false)} />}
    </div>
  );
}

function Visual({ media, upload, onOpen }: { media: MediaInfo; upload?: Upload; onOpen: () => void }) {
  const client = useClient();
  // The preview of a video is its thumbnail; without one the box shows only the play mark.
  const hasPreview = media.kind === 'image' || media.thumbnail !== undefined;
  const preview = useObjectUrl(
    hasPreview ? `${media.source.toJson()}#${PREVIEW}` : undefined,
    () => previewOf(client, media.source, media.thumbnail, PREVIEW),
    media.kind === 'image' && !media.thumbnail ? media.mimetype : undefined,
  );
  const ratio = media.width && media.height ? Math.max(media.width / media.height, 1 / 1.4) : 1;
  const label = media.kind === 'video' ? s('bipe_app_media_video') : s('bipe_app_media_image');
  return (
    <button
      type="button"
      className="relative block max-w-full overflow-hidden rounded-xl bg-surface-sunken"
      style={{ width: MAX_WIDTH, aspectRatio: ratio }}
      aria-label={label}
      onClick={preview.failed ? preview.retry : onOpen}
    >
      {preview.url && <img src={preview.url} alt="" className="absolute inset-0 size-full object-cover" />}
      <span className="absolute inset-0 grid place-items-center">
        {preview.failed ? (
          <span className="bipe-scrim m-2 flex flex-col items-center gap-1.5 rounded-[10px] p-2.5 text-center text-caption">
            <Icon path={arrowClockwise} size={22} />
            {s('bipe_app_media_failed')}
          </span>
        ) : !preview.url ? (
          !hasPreview ? (
            <PlayMark />
          ) : (
            <span className="bipe-scrim grid rounded-full p-2.5">
              <Spinner />
            </span>
          )
        ) : (
          media.kind === 'video' && <PlayMark />
        )}
      </span>
      {upload && (
        <span className="absolute inset-0 grid place-items-center">
          <UploadRing upload={upload} scrim />
        </span>
      )}
      {media.kind === 'video' && media.duration !== undefined && (
        <span className="bipe-scrim absolute bottom-2 left-2 rounded-full px-1.5 py-0.5 text-caption2 tabular-nums">
          {durationText(media.duration)}
        </span>
      )}
    </button>
  );
}

// The upload overlay (uploadOverlay, TimelineMediaRow.swift): a ring that fills with the progress and an x
// that cancels. On a photo it sits on the scrim; on a file card it fills the 40 px circle.
function UploadRing({ upload, scrim }: { upload: Upload; scrim?: boolean }) {
  const r = 18;
  const length = 2 * Math.PI * r;
  return (
    <span
      role="button"
      tabIndex={0}
      // TODO(string): bipe_web_upload_cancel, the label of the button that stops an upload (iOS uses bipe_app_cancel).
      aria-label={s('bipe_app_cancel')}
      className={`relative grid size-11 place-items-center rounded-full ${scrim ? 'bipe-scrim' : 'text-accent-ink'}`}
      onClick={(e) => {
        e.stopPropagation();
        upload.cancel();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          upload.cancel();
        }
      }}
    >
      <svg viewBox="0 0 44 44" className="absolute inset-0 size-full -rotate-90" aria-hidden>
        <circle
          cx="22"
          cy="22"
          r={r}
          fill="none"
          stroke="currentColor"
          strokeWidth="3"
          strokeLinecap="round"
          strokeDasharray={length}
          strokeDashoffset={length * (1 - upload.percent / 100)}
          className={scrim ? '' : 'text-accent-fill'}
        />
      </svg>
      <Icon path={x} size={16} />
    </span>
  );
}

function PlayMark() {
  return (
    <span className="bipe-scrim grid rounded-full p-3">
      <Icon path={play} size={22} />
    </span>
  );
}

function FileCard({ media, upload }: { media: MediaInfo; upload?: Upload }) {
  const client = useClient();
  const [state, setState] = useState<'idle' | 'opening' | 'failed'>('idle');
  return (
    <button
      type="button"
      className="flex max-w-[260px] items-center gap-2.5 p-1.5 text-left"
      aria-label={`${s('bipe_app_media_file')}, ${media.filename}`}
      onClick={async () => {
        setState('opening');
        try {
          const url = await objectUrlOfContent(client, media.source, media.mimetype);
          const a = document.createElement('a');
          a.href = url;
          a.download = media.filename;
          a.click();
          setTimeout(() => URL.revokeObjectURL(url), 60_000);
          setState('idle');
        } catch (e) {
          console.warn('[trace] file load failed', e);
          setState('failed');
        }
      }}
    >
      <span className="relative grid size-10 shrink-0 place-items-center rounded-full bg-accent-wash text-accent-ink">
        {upload ? (
          <UploadRing upload={upload} />
        ) : state === 'opening' ? (
          <Spinner />
        ) : (
          <Icon path={state === 'failed' ? arrowClockwise : file} size={20} />
        )}
      </span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="line-clamp-2 text-subheadline font-semibold break-all text-ink-primary">{media.filename}</span>
        {state === 'failed' ? (
          <span className="text-caption text-trust-danger-ink">{s('bipe_app_media_failed')}</span>
        ) : (
          media.size !== undefined && <span className="text-caption text-ink-secondary">{sizeText(media.size)}</span>
        )}
      </span>
    </button>
  );
}

// The viewer: the full file on black, Escape or the close button ends it.
function Viewer({ media, onClose }: { media: MediaInfo; onClose: () => void }) {
  const client = useClient();
  const full = useObjectUrl(
    `${media.source.toJson()}#full`,
    () => client.getMediaContent(media.source),
    media.mimetype,
  );
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    // Black in light and dark, like the apps' media viewer; no token covers it.
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 grid place-items-center bg-black"
      onClick={onClose}
    >
      {full.url ? (
        media.kind === 'video' ? (
          <video
            src={full.url}
            controls
            autoPlay
            className="max-h-full max-w-full"
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <img src={full.url} alt="" className="max-h-full max-w-full object-contain" />
        )
      ) : full.failed ? (
        <button
          type="button"
          className="bipe-scrim flex flex-col items-center gap-1.5 rounded-[10px] p-2.5 text-caption"
          onClick={(e) => {
            e.stopPropagation();
            full.retry();
          }}
        >
          <Icon path={arrowClockwise} size={22} />
          {s('bipe_app_media_failed')}
        </button>
      ) : (
        <span className="bipe-scrim grid rounded-full p-2.5">
          <Spinner />
        </span>
      )}
      <button
        type="button"
        className="bipe-scrim absolute top-3 right-3 grid size-11 place-items-center rounded-full"
        aria-label={s('bipe_app_close')}
        onClick={onClose}
      >
        <Icon path={x} size={22} />
      </button>
    </div>
  );
}

const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte'] as const;

/** A file size as the system shows it, e.g. 1,2 MB. */
export function sizeText(bytes: number): string {
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return new Intl.NumberFormat(locale, {
    style: 'unit',
    unit: units[unit],
    unitDisplay: 'short',
    maximumFractionDigits: unit < 2 ? 0 : 1,
  }).format(value);
}

function durationText(ms: number): string {
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const sec = String(total % 60).padStart(2, '0');
  return `${m}:${sec}`;
}

// Spec voice-message (VoiceMessageRow.swift, VoicePlayer.swift): a 40 px accent.fill play button, the
// waveform (2 px gaps, played bars in accent.fill, the rest in line.strong, drag or click to seek), the
// duration, and the speed button (1×, 1,5×, 2×, kept for the next row). One row plays at a time. The
// audio comes from the SDK as bytes (decrypted) and plays from a Blob object URL.
const SPEEDS = [1, 1.5, 2];
let speedIndex = 0;
let playing: HTMLAudioElement | undefined;
const BARS = 36;

function bars(waveform: number[] | undefined): number[] {
  if (!waveform || waveform.length === 0) return Array.from({ length: BARS }, () => 0.3);
  const max = Math.max(1, ...waveform);
  return Array.from({ length: BARS }, (_, i) => {
    const from = Math.floor((i * waveform.length) / BARS);
    const to = Math.max(from + 1, Math.floor(((i + 1) * waveform.length) / BARS));
    const slice = waveform.slice(from, to);
    return Math.max(0.1, slice.reduce((a, b) => a + b, 0) / slice.length / max);
  });
}

function VoiceRow({ media }: { media: MediaInfo }) {
  const client = useClient();
  const audio = useRef<HTMLAudioElement>(undefined);
  const url = useRef<string>(undefined);
  const [state, setState] = useState<'idle' | 'loading' | 'playing' | 'paused' | 'failed'>('idle');
  const [progress, setProgress] = useState(0);
  const [speed, setSpeed] = useState(SPEEDS[speedIndex]);
  const values = bars(media.waveform);
  useEffect(
    () => () => {
      audio.current?.pause();
      if (url.current) URL.revokeObjectURL(url.current);
    },
    [],
  );
  const element = async (): Promise<HTMLAudioElement> => {
    if (audio.current) return audio.current;
    const made = new Audio();
    url.current = await objectUrlOfContent(client, media.source, media.mimetype);
    made.src = url.current;
    made.preservesPitch = true;
    made.ontimeupdate = () => setProgress(made.duration ? made.currentTime / made.duration : 0);
    made.onended = () => {
      setState('paused');
      setProgress(0);
    };
    made.onpause = () => setState((s) => (s === 'playing' ? 'paused' : s));
    audio.current = made;
    return made;
  };
  const toggle = async () => {
    try {
      if (state === 'playing') return audio.current?.pause();
      setState((s) => (s === 'idle' || s === 'failed' ? 'loading' : s));
      const a = await element();
      if (playing && playing !== a) playing.pause();
      playing = a;
      a.playbackRate = SPEEDS[speedIndex];
      setSpeed(SPEEDS[speedIndex]);
      await a.play();
      setState('playing');
    } catch (e) {
      console.warn('[trace] voice playback failed', e);
      setState('failed');
    }
  };
  const seek = (fraction: number) => {
    const a = audio.current;
    if (a && a.duration) a.currentTime = fraction * a.duration;
    setProgress(fraction);
  };
  const speedLabel = speed.toLocaleString(locale);
  return (
    <div className="flex w-[256px] max-w-full flex-col gap-1">
      <div className="flex items-center gap-2.5">
        <button
          type="button"
          className="grid size-10 shrink-0 place-items-center rounded-full bg-accent-fill text-ink-on-accent"
          aria-label={state === 'playing' ? s('bipe_app_voice_pause') : s('bipe_app_voice_play')}
          onClick={() => void toggle()}
        >
          {state === 'loading' ? <Spinner /> : <Icon path={state === 'playing' ? pause : play} size={18} />}
        </button>
        <div
          role="slider"
          tabIndex={0}
          aria-label={media.isVoice ? s('bipe_app_media_voice') : media.filename}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(progress * 100)}
          className="flex h-8 min-w-0 flex-1 cursor-pointer items-center gap-0.5"
          onPointerDown={(e) => {
            const box = e.currentTarget.getBoundingClientRect();
            seek(Math.min(1, Math.max(0, (e.clientX - box.left) / box.width)));
          }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight') seek(Math.min(1, progress + 0.05));
            if (e.key === 'ArrowLeft') seek(Math.max(0, progress - 0.05));
          }}
        >
          {values.map((v, i) => (
            <span
              key={i}
              className={`min-w-0 flex-1 rounded-full ${i / values.length < progress ? 'bg-accent-fill' : 'bg-line-strong'}`}
              style={{ height: `${Math.max(3, v * 32)}px` }}
            />
          ))}
        </div>
        {media.duration !== undefined && (
          <span className="shrink-0 text-caption text-ink-secondary tabular-nums">{durationText(media.duration)}</span>
        )}
        <button
          type="button"
          className="min-h-11 shrink-0 px-1 text-caption font-semibold text-accent-ink tabular-nums"
          aria-label={s('bipe_voice_speed_label', speedLabel)}
          onClick={() => {
            speedIndex = (speedIndex + 1) % SPEEDS.length;
            setSpeed(SPEEDS[speedIndex]);
            if (audio.current) audio.current.playbackRate = SPEEDS[speedIndex];
          }}
        >
          {s('bipe_voice_speed', speedLabel)}
        </button>
      </div>
      {state === 'failed' && <span className="text-caption text-trust-danger-ink">{s('bipe_app_media_failed')}</span>}
    </div>
  );
}

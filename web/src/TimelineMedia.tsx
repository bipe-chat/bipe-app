// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec timeline-media (TimelineMediaRow.swift): photos and videos as a box at most 260 px wide with 12 px
// corners, its aspect ratio no taller than 1 : 1.4, the caption under it; a file as a card with a 40 px
// circle, the name and the size. A tap on a photo or video opens the viewer (media-viewer, simplified);
// a tap on a file saves it.

import { useEffect, useState } from 'react';
import { MessageType, type MediaSourceLike, type MessageType as MessageTypeValue } from './generated/matrix_sdk_ffi';
import { objectUrlOfContent, previewOf, useClient, useObjectUrl } from './media';
import { arrowClockwise, file, play, x } from './phosphor/icons';
import { locale, s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';

export type MediaInfo = {
  kind: 'image' | 'video' | 'file';
  source: MediaSourceLike;
  thumbnail?: MediaSourceLike;
  filename: string;
  caption?: string;
  mimetype?: string;
  width?: number;
  height?: number;
  size?: number;
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

export function TimelineMediaRow({ media }: { media: MediaInfo }) {
  const [viewing, setViewing] = useState(false);
  const caption = media.caption?.trim();
  return (
    <div className="flex flex-col gap-1">
      {media.kind === 'file' ? <FileCard media={media} /> : <Visual media={media} onOpen={() => setViewing(true)} />}
      {caption && <p className="max-w-[260px] text-body break-words whitespace-pre-wrap text-ink-primary">{caption}</p>}
      {viewing && <Viewer media={media} onClose={() => setViewing(false)} />}
    </div>
  );
}

function Visual({ media, onOpen }: { media: MediaInfo; onOpen: () => void }) {
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
      {media.kind === 'video' && media.duration !== undefined && (
        <span className="bipe-scrim absolute bottom-2 left-2 rounded-full px-1.5 py-0.5 text-caption2 tabular-nums">
          {durationText(media.duration)}
        </span>
      )}
    </button>
  );
}

function PlayMark() {
  return (
    <span className="bipe-scrim grid rounded-full p-3">
      <Icon path={play} size={22} />
    </span>
  );
}

function FileCard({ media }: { media: MediaInfo }) {
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
      <span className="grid size-10 shrink-0 place-items-center rounded-full bg-accent-wash text-accent-ink">
        {state === 'opening' ? <Spinner /> : <Icon path={state === 'failed' ? arrowClockwise : file} size={20} />}
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

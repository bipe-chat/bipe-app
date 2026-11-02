// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Specs attachment-picker and upload-preview. The + button of the composer opens a menu anchored to it
// (iOS Menu, Android DropdownMenu): photos and videos, a file. The web leaves out the camera, a
// location, stickers and polls. Picked, pasted or dropped files open the upload preview: the media, a
// caption for the first item, Send. Each item goes out as its own message through the SDK send queue.

import { useEffect, useRef, useState } from 'react';
import { UploadSource, type TimelineLike as Timeline } from './generated/matrix_sdk_ffi';
import { useClient } from './media';
import { file as fileIcon, image, plus, x } from './phosphor/icons';
import { sizeText } from './TimelineMedia';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { Spinner } from './ui/Spinner';
import { describe } from './util';

/** At most 10 items per preview (spec upload-preview). */
export const MAX_ITEMS = 10;

export function AttachButton({ onFiles }: { onFiles: (files: File[]) => void }) {
  const [open, setOpen] = useState(false);
  const photos = useRef<HTMLInputElement>(null);
  const files = useRef<HTMLInputElement>(null);
  const take = (input: HTMLInputElement | null) => {
    const picked = Array.from(input?.files ?? []);
    if (input) input.value = '';
    if (picked.length) onFiles(picked);
  };
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  const row = (icon: string, title: string, input: HTMLInputElement | null) => (
    <button
      type="button"
      role="menuitem"
      className="flex min-h-11 w-full items-center justify-between gap-4 px-4 text-left text-body text-ink-primary hover:bg-surface-sunken"
      onClick={() => {
        setOpen(false);
        input?.click();
      }}
    >
      {title}
      <Icon path={icon} size={22} className="text-glyph-primary" />
    </button>
  );
  return (
    <div className="relative shrink-0">
      <button
        type="button"
        className="bipe-glass grid size-11 place-items-center rounded-full text-glyph-primary"
        aria-label={s('bipe_app_composer_attach')}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon path={plus} size={22} />
      </button>
      {open && (
        <>
          <button
            type="button"
            className="fixed inset-0 z-20 cursor-default"
            tabIndex={-1}
            aria-hidden
            onClick={() => setOpen(false)}
          />
          <div
            role="menu"
            className="absolute bottom-full left-0 z-30 mb-2 min-w-[240px] overflow-hidden rounded-[14px] bg-surface-raised py-1 shadow-lg ring-1 ring-line-subtle"
          >
            {row(image, s('bipe_app_attach_photos'), photos.current)}
            {row(fileIcon, s('bipe_app_attach_file'), files.current)}
          </div>
        </>
      )}
      <input
        ref={photos}
        type="file"
        accept="image/*,video/*"
        multiple
        hidden
        onChange={(e) => take(e.currentTarget)}
      />
      <input ref={files} type="file" hidden onChange={(e) => take(e.currentTarget)} />
    </div>
  );
}

type Thumb = { bytes: ArrayBuffer; width: number; height: number };
type Prepared = {
  file: File;
  kind: 'image' | 'video' | 'file';
  bytes: ArrayBuffer;
  filename: string;
  mimetype: string;
  width?: number;
  height?: number;
  duration?: number;
  thumb?: Thumb;
};

// quality standard (spec upload-preview): photos at most 2048 px on the long side, as JPEG. Drawing
// through a canvas also drops the location and camera data. GIFs keep their frames, so they go as they are.
const PHOTO_MAX = 2048;
const THUMB_MAX = 800;

function fit(width: number, height: number, max: number) {
  const scale = Math.min(1, max / Math.max(width, height));
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}

async function jpeg(source: CanvasImageSource, width: number, height: number, quality: number): Promise<ArrayBuffer> {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.getContext('2d')!.drawImage(source, 0, 0, width, height);
  const blob = await new Promise<Blob | null>((done) => canvas.toBlob(done, 'image/jpeg', quality));
  if (!blob) throw new Error('canvas gave no JPEG');
  return blob.arrayBuffer();
}

async function prepare(file: File): Promise<Prepared> {
  const base = { file, filename: file.name || 'file', mimetype: file.type || 'application/octet-stream' };
  if (file.type.startsWith('image/') && file.type !== 'image/svg+xml') {
    const bitmap = await createImageBitmap(file);
    const t = fit(bitmap.width, bitmap.height, THUMB_MAX);
    const thumb = { bytes: await jpeg(bitmap, t.width, t.height, 0.8), ...t };
    if (file.type === 'image/gif') {
      return {
        ...base,
        kind: 'image',
        bytes: await file.arrayBuffer(),
        width: bitmap.width,
        height: bitmap.height,
        thumb,
      };
    }
    const p = fit(bitmap.width, bitmap.height, PHOTO_MAX);
    const bytes = await jpeg(bitmap, p.width, p.height, 0.9);
    bitmap.close();
    const filename = base.filename.replace(/\.[^.]*$/, '') + '.jpg';
    return { ...base, kind: 'image', bytes, filename, mimetype: 'image/jpeg', ...p, thumb };
  }
  if (file.type.startsWith('video/')) {
    // The video goes as it is; its first frame is the thumbnail when the browser can decode it.
    const meta = await videoFrame(file).catch(() => undefined);
    return { ...base, kind: 'video', bytes: await file.arrayBuffer(), ...meta };
  }
  return { ...base, kind: 'file', bytes: await file.arrayBuffer() };
}

function videoFrame(file: File): Promise<{ width: number; height: number; duration: number; thumb: Thumb }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.muted = true;
    video.preload = 'auto';
    const done = () => URL.revokeObjectURL(url);
    const timer = setTimeout(() => {
      done();
      reject(new Error('video frame timed out'));
    }, 10_000);
    video.onerror = () => {
      clearTimeout(timer);
      done();
      reject(new Error('video does not decode'));
    };
    video.onloadedmetadata = () => {
      video.currentTime = Math.min(0.1, video.duration || 0);
    };
    video.onseeked = async () => {
      clearTimeout(timer);
      try {
        const t = fit(video.videoWidth, video.videoHeight, THUMB_MAX);
        const bytes = await jpeg(video, t.width, t.height, 0.8);
        resolve({
          width: video.videoWidth,
          height: video.videoHeight,
          duration: video.duration * 1000,
          thumb: { bytes, ...t },
        });
      } catch (e) {
        reject(e);
      } finally {
        done();
      }
    };
    video.src = url;
  });
}

/** Hands one item to the SDK send queue. Throws when the SDK refuses it. */
function send(timeline: Timeline, item: Prepared, caption: string | undefined) {
  const params = { source: new UploadSource.Data({ bytes: item.bytes, filename: item.filename }), caption };
  const size = BigInt(item.bytes.byteLength);
  const thumbSource = item.thumb
    ? new UploadSource.Data({ bytes: item.thumb.bytes, filename: 'thumbnail.jpg' })
    : undefined;
  const thumbnailInfo = item.thumb
    ? {
        width: BigInt(item.thumb.width),
        height: BigInt(item.thumb.height),
        mimetype: 'image/jpeg',
        size: BigInt(item.thumb.bytes.byteLength),
      }
    : undefined;
  const width = item.width === undefined ? undefined : BigInt(item.width);
  const height = item.height === undefined ? undefined : BigInt(item.height);
  const handle =
    item.kind === 'image'
      ? timeline.sendImage(params, thumbSource, {
          width,
          height,
          mimetype: item.mimetype,
          size,
          thumbnailInfo,
          isAnimated: item.mimetype === 'image/gif',
        })
      : item.kind === 'video'
        ? timeline.sendVideo(params, thumbSource, {
            width,
            height,
            duration: item.duration,
            mimetype: item.mimetype,
            size,
            thumbnailInfo,
          })
        : timeline.sendFile(params, { mimetype: item.mimetype, size });
  // The send queue keeps going after the preview closes; a failure shows on the timeline row.
  handle.join().catch((e: unknown) => console.warn('[trace] upload failed', item.filename, e));
}

export function UploadPreview({
  files,
  timeline,
  onClose,
}: {
  files: File[];
  timeline: Timeline | undefined;
  onClose: () => void;
}) {
  const client = useClient();
  const [items, setItems] = useState<Prepared[]>();
  const [failed, setFailed] = useState<Set<File>>(new Set());
  const [caption, setCaption] = useState('');
  const [maxUpload, setMaxUpload] = useState<number>();
  const [error, setError] = useState('');

  useEffect(() => {
    let stopped = false;
    Promise.all(files.slice(0, MAX_ITEMS).map(prepare))
      .then((prepared) => !stopped && setItems(prepared))
      .catch((e: unknown) => !stopped && setError(describe(e)));
    client
      .getMaxMediaUploadSize()
      .then((max) => !stopped && setMaxUpload(Number(max)))
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [files, client]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const tooLarge = maxUpload !== undefined && (items ?? []).some((i) => i.bytes.byteLength > maxUpload);
  const canSend = items !== undefined && items.length > 0 && !tooLarge && timeline !== undefined;

  return (
    // The dim behind a sheet is black at 40% in both schemes, as the system draws it; no token covers it.
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-40 grid place-items-center bg-black/40 p-0 md:p-6"
      onClick={onClose}
    >
      <div
        className="flex h-full w-full max-w-[520px] flex-col bg-surface-base md:h-auto md:max-h-full md:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex h-11 shrink-0 items-center px-2">
          <button type="button" className="min-h-11 px-2 text-body text-accent-ink" onClick={onClose}>
            {s('bipe_app_cancel')}
          </button>
        </div>
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 pb-4">
          {!items ? (
            <div className="grid flex-1 place-items-center gap-2 py-12 text-center">
              {error ? (
                <p className="text-subheadline text-trust-danger-ink">{s('bipe_error_generic')}</p>
              ) : (
                <span className="flex flex-col items-center gap-2 text-footnote text-ink-secondary">
                  <Spinner />
                  {s('bipe_app_upload_preparing')}
                </span>
              )}
            </div>
          ) : (
            items.map((item, index) => (
              <PreviewItem
                key={index}
                item={item}
                failed={failed.has(item.file)}
                tooLarge={maxUpload !== undefined && item.bytes.byteLength > maxUpload}
                maxLabel={maxUpload === undefined ? '' : sizeText(maxUpload)}
                onRemove={() => {
                  const next = items.filter((_, i) => i !== index);
                  if (next.length === 0) onClose();
                  else setItems(next);
                }}
              />
            ))
          )}
        </div>
        <div className="flex shrink-0 items-end gap-2 border-t border-line-subtle px-3 py-2">
          <div className="flex min-w-0 flex-1 rounded-[22px] bg-surface-sunken pr-1 pl-4">
            <textarea
              className="max-h-[132px] min-h-11 w-full resize-none bg-transparent py-[11px] text-body text-ink-primary outline-none [field-sizing:content] placeholder:text-ink-disabled"
              rows={1}
              value={caption}
              placeholder={s('bipe_app_upload_caption_placeholder')}
              aria-label={s('bipe_app_upload_caption_placeholder')}
              onChange={(e) => setCaption(e.target.value)}
            />
          </div>
          <button
            type="button"
            className="min-h-11 shrink-0 rounded-full bg-accent-fill px-5 text-headline text-ink-on-accent hover:bg-accent-fill-pressed disabled:bg-surface-sunken disabled:text-ink-disabled"
            disabled={!canSend}
            onClick={() => {
              if (!items || !timeline) return;
              // The caption goes with the first item that gets through; items the SDK refuses stay.
              let text: string | undefined = caption.trim() || undefined;
              const left: Prepared[] = [];
              for (const item of items) {
                try {
                  send(timeline, item, text);
                  text = undefined;
                } catch (e) {
                  console.warn('[trace] upload refused', item.filename, e);
                  left.push(item);
                }
              }
              if (left.length === 0) onClose();
              else {
                setItems(left);
                setFailed(new Set(left.map((i) => i.file)));
                setCaption(text ?? '');
              }
            }}
          >
            {s('bipe_app_upload_send')}
          </button>
        </div>
      </div>
    </div>
  );
}

function PreviewItem({
  item,
  failed,
  tooLarge,
  maxLabel,
  onRemove,
}: {
  item: Prepared;
  failed: boolean;
  tooLarge: boolean;
  maxLabel: string;
  onRemove: () => void;
}) {
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (item.kind === 'file') return;
    const made = URL.createObjectURL(item.file);
    setUrl(made);
    return () => URL.revokeObjectURL(made);
  }, [item]);
  return (
    <div className="flex flex-col gap-1">
      <div className="relative overflow-hidden rounded-xl bg-surface-sunken">
        {item.kind === 'image' && url && (
          <img src={url} alt="" className="mx-auto max-h-[50vh] w-auto max-w-full object-contain" />
        )}
        {item.kind === 'video' && url && (
          <video src={url} controls muted className="mx-auto max-h-[50vh] w-auto max-w-full" />
        )}
        {item.kind === 'file' && (
          <div className="flex items-center gap-2.5 p-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-full bg-accent-wash text-accent-ink">
              <Icon path={fileIcon} size={20} />
            </span>
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="line-clamp-2 text-subheadline font-semibold break-all text-ink-primary">
                {item.filename}
              </span>
              <span className="text-caption text-ink-secondary">{sizeText(item.bytes.byteLength)}</span>
            </span>
          </div>
        )}
        <button
          type="button"
          className="bipe-scrim absolute top-2 right-2 grid size-8 place-items-center rounded-full"
          // TODO(string): the label of the button that drops one item from the upload preview.
          aria-label="Remove"
          onClick={onRemove}
        >
          <Icon path={x} size={16} />
        </button>
      </div>
      {tooLarge && <p className="text-footnote text-trust-danger-ink">{s('bipe_app_upload_too_large', maxLabel)}</p>}
      {failed && <p className="text-footnote text-trust-danger-ink">{s('bipe_error_generic')}</p>}
    </div>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Message kinds beyond text and files: stickers (spec timeline-media, TimelineMediaRow.swift: 160 px, no
// bubble), polls (PollCard and PollOptionLine in PollViews.swift; specs poll-detail, poll-create end) and
// locations (TimelineLocationRow in LocationViews.swift; spec location-view; ADR 0131: a static map from
// maps.bipe.net.br only, never another map service).

import { useEffect, useState } from 'react';
import { maps } from './bipe/generated/registries.json';
import { PollKind, type MediaSourceLike } from './generated/matrix_sdk_ffi';
import { previewOf, useClient, useObjectUrl } from './media';
import { chartBar, check, x } from './phosphor/icons';
import { Alert } from './ui/Alert';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';

export type StickerInfo = { source: MediaSourceLike; body: string; width?: number; height?: number; mimetype?: string };

export function StickerView({ sticker }: { sticker: StickerInfo }) {
  const client = useClient();
  const image = useObjectUrl(
    `${sticker.source.toJson()}#sticker`,
    () => previewOf(client, sticker.source, undefined, 480),
    sticker.mimetype,
  );
  const ratio = sticker.width && sticker.height ? sticker.width / sticker.height : 1;
  return (
    <span
      className="block"
      style={{ width: 160, aspectRatio: ratio }}
      role="img"
      aria-label={sticker.body || s('bipe_app_media_sticker')}
    >
      {image.url && <img src={image.url} alt="" className="size-full object-contain" />}
    </span>
  );
}

export type PollInfo = {
  question: string;
  disclosed: boolean;
  maxSelections: number;
  answers: { id: string; text: string }[];
  /** answer id → voters */
  votes: Map<string, string[]>;
  ended: boolean;
};

// PollCard: the chart glyph and Enquete (or Enquete encerrada), the question in headline, one line per option
// with its count and a 6 px bar (accent for the winner and the person's vote), then the vote total or the
// hidden-results line. A tap on an option votes (Timeline.sendPollResponse); the creator can end the poll.
export function PollCard({
  poll,
  ownUserId,
  canEnd,
  onVote,
  onEnd,
}: {
  poll: PollInfo;
  ownUserId: string;
  canEnd: boolean;
  onVote: (answerIds: string[]) => Promise<void>;
  onEnd: () => Promise<void>;
}) {
  const [error, setError] = useState('');
  const [confirmEnd, setConfirmEnd] = useState(false);
  const mine = poll.answers.filter((a) => poll.votes.get(a.id)?.includes(ownUserId)).map((a) => a.id);
  const voters = new Set([...poll.votes.values()].flat());
  const total = voters.size;
  // Undisclosed results show only when the poll ends (bipe_app_poll_results_hidden).
  const showsResults = poll.disclosed || poll.ended;
  const counts = poll.answers.map((a) => poll.votes.get(a.id)?.length ?? 0);
  const best = Math.max(0, ...counts);
  const vote = (id: string) => {
    if (poll.ended) return;
    const next =
      poll.maxSelections > 1
        ? mine.includes(id)
          ? mine.filter((m) => m !== id)
          : [...mine, id].slice(-poll.maxSelections)
        : [id];
    setError('');
    onVote(next).catch(() => setError(s('bipe_app_poll_vote_error')));
  };
  return (
    <div className="flex min-w-[220px] flex-col gap-2">
      <span className="flex items-center gap-1.5 text-caption font-bold text-accent-ink">
        <Icon path={chartBar} size={16} />
        {poll.ended ? s('bipe_app_poll_ended') : s('bipe_app_poll_title')}
      </span>
      <p className="text-headline text-ink-primary">{poll.question}</p>
      {poll.answers.map((a, i) => {
        const count = counts[i];
        const isMine = mine.includes(a.id);
        const winner = poll.ended && count === best && best > 0;
        return (
          <button
            key={a.id}
            type="button"
            disabled={poll.ended}
            aria-pressed={isMine}
            className="flex flex-col gap-1 text-left disabled:cursor-default"
            onClick={() => vote(a.id)}
          >
            <span className="flex items-center gap-2">
              <span className="min-w-0 flex-1 text-body text-ink-primary">{a.text}</span>
              {isMine && (
                <Icon path={check} size={14} label={s('bipe_app_poll_your_vote')} className="text-accent-ink" />
              )}
              {showsResults && (
                <span className="text-footnote text-ink-secondary tabular-nums">{count.toLocaleString()}</span>
              )}
            </span>
            {showsResults && total > 0 && (
              <span className="h-1.5 overflow-hidden rounded-full bg-surface-sunken">
                <span
                  className={`block h-full rounded-full ${winner || isMine ? 'bg-accent-fill' : 'bg-glyph-secondary'}`}
                  style={{ width: `${(count / total) * 100}%` }}
                />
              </span>
            )}
          </button>
        );
      })}
      <span className="text-caption text-ink-secondary">
        {showsResults ? s('bipe_app_poll_votes', total.toLocaleString()) : s('bipe_app_poll_results_hidden')}
      </span>
      {error && <span className="text-caption text-trust-danger-ink">{error}</span>}
      {canEnd && !poll.ended && (
        <button
          type="button"
          className="self-start text-subheadline font-semibold text-accent-ink"
          onClick={() => setConfirmEnd(true)}
        >
          {s('bipe_app_poll_end')}
        </button>
      )}
      {confirmEnd && (
        <Alert
          title={s('bipe_app_poll_end')}
          action={s('bipe_app_poll_end')}
          onCancel={() => setConfirmEnd(false)}
          onAction={() => {
            setConfirmEnd(false);
            onEnd().catch(() => setError(s('bipe_error_generic')));
          }}
        >
          <p>{s('bipe_app_poll_end_confirm')}</p>
        </Alert>
      )}
    </div>
  );
}

export type LocationInfo = { lat: number; lon: number; zoom?: number; description?: string; body: string };

/** geo:lat,lon[;u=...] of a Matrix location event, or undefined. */
export function parseGeoUri(uri: string): { lat: number; lon: number } | undefined {
  const m = /^geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/i.exec(uri.trim());
  if (!m) return undefined;
  const lat = Number(m[1]);
  const lon = Number(m[2]);
  return Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? { lat, lon } : undefined;
}

function staticMap(loc: LocationInfo, width: number, height: number): string {
  const d = maps.detailBounds;
  const inside = loc.lon >= d.west && loc.lon <= d.east && loc.lat >= d.south && loc.lat <= d.north;
  const wanted = loc.zoom ?? maps.defaultZoom;
  const zoom = Math.max(maps.minZoom, Math.min(inside ? maps.maxZoom : maps.outsideMaxZoom, wanted));
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const fix = (n: number) => n.toFixed(maps.coordinateDecimals);
  const size = (n: number) => Math.min(maps.maxStaticSize, Math.round(n));
  return (
    maps.baseUrl +
    maps.staticPath
      .replace('{style}', dark ? maps.styles.dark : maps.styles.light)
      .replace('{lon}', fix(loc.lon))
      .replace('{lat}', fix(loc.lat))
      .replace('{zoom}', String(zoom))
      .replace('{width}', String(size(width)))
      .replace('{height}', String(size(height)))
  );
}

// TimelineLocationRow: a 260 px 4:3 map with 12 px corners and the pin in the middle, then the label in
// footnote semibold and the description. A tap opens a larger map (location-view, simplified).
export function LocationView({ location }: { location: LocationInfo }) {
  const [full, setFull] = useState(false);
  const label = location.body || s('bipe_app_location_title');
  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        className="relative block max-w-full overflow-hidden rounded-xl bg-surface-sunken"
        style={{ width: 260, aspectRatio: 4 / 3 }}
        aria-label={label}
        onClick={() => setFull(true)}
      >
        <img src={staticMap(location, 260, 195)} alt="" className="absolute inset-0 size-full object-cover" />
        <MapPinMark />
      </button>
      <span className="text-footnote font-semibold text-ink-primary">{s('bipe_app_location_title')}</span>
      {location.description && <p className="max-w-[260px] text-body text-ink-primary">{location.description}</p>}
      {full && <FullMap location={location} onClose={() => setFull(false)} />}
    </div>
  );
}

// MapPin (StaticMapView.swift): a 20 px accent.fill dot with a 3 px surface.raised ring and a small shadow.
function MapPinMark() {
  return (
    <span className="absolute inset-0 grid place-items-center">
      <span className="size-5 rounded-full border-[3px] border-surface-raised bg-accent-fill shadow" />
    </span>
  );
}

function FullMap({ location, onClose }: { location: LocationInfo; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    // The map viewer is black around the map in both schemes, like the media viewer.
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 grid place-items-center bg-black"
      onClick={onClose}
    >
      <span className="relative" style={{ width: 'min(90vw, 1024px)', aspectRatio: 4 / 3 }}>
        <img src={staticMap(location, 1024, 768)} alt="" className="size-full object-contain" />
        <MapPinMark />
      </span>
      <span className="absolute bottom-3 left-3 text-caption text-white/80">{maps.attribution}</span>
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

export const pollKindDisclosed = (kind: PollKind) => kind === PollKind.Disclosed;

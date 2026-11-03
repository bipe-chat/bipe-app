// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec message-pins (bipe#665; PinnedMessages.swift): the pinned messages of a group, newest first, from
// the SDK's pinned-events timeline. A banner above the timeline shows one; a click jumps to it and moves
// the banner to the next older pin. Ver todas opens the list. Groups only (ADR 0141).

import { useEffect, useState } from 'react';
import {
  DateDividerMode,
  EventOrTransactionId,
  ProfileDetails,
  TimelineDiff,
  TimelineFilter,
  TimelineFocus,
  type RoomLike as Room,
  type TaskHandleLike as TaskHandle,
  type TimelineItemLike as TimelineItem,
} from './generated/matrix_sdk_ffi';
import { TimelineReadReceiptTracking } from './generated/matrix_sdk_ui';
import { Sheet } from './MessageSheets';
import { chatText } from './phosphor/icons';
import { previewText } from './row-preview';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { applyDiffs, type DiffVariants } from './util';

export type Pin = { eventId: string; senderName: string; text: string };

function pinOf(item: TimelineItem): Pin | undefined {
  const event = item.asEvent();
  if (!event || !EventOrTransactionId.EventId.instanceOf(event.eventOrTransactionId)) return undefined;
  const text = previewText(event.content);
  if (text === undefined) return undefined;
  const senderName = event.isOwn
    ? s('bipe_app_you')
    : ProfileDetails.Ready.instanceOf(event.senderProfile)
      ? (event.senderProfile.inner.displayName ?? event.sender)
      : event.sender;
  return { eventId: event.eventOrTransactionId.inner.eventId, senderName, text: text.replace(/\n/g, ' ') };
}

/** The pins of a group, newest first, live until the chat closes. enabled is false for a DM. */
export function usePinnedMessages(room: Room, enabled: boolean): Pin[] {
  const [items, setItems] = useState<TimelineItem[]>([]);
  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    let handle: TaskHandle | undefined;
    (async () => {
      const timeline = await room.timelineWithConfiguration({
        focus: new TimelineFocus.PinnedEvents(),
        filter: new TimelineFilter.All(),
        internalIdPrefix: 'pinned',
        dateDividerMode: DateDividerMode.Daily,
        trackReadReceipts: TimelineReadReceiptTracking.Disabled,
        reportUtds: false,
      });
      if (stopped) return;
      handle = await timeline.addListener({
        onUpdate(diffs) {
          if (!stopped) setItems((prev) => applyDiffs(prev, diffs, TimelineDiff as unknown as DiffVariants));
        },
      });
      if (stopped) handle.cancel();
    })().catch((e: unknown) => console.warn('[trace] pinned timeline failed', e));
    return () => {
      stopped = true;
      handle?.cancel();
      setItems([]);
    };
  }, [room, enabled]);
  return items
    .map(pinOf)
    .filter((p) => p !== undefined)
    .reverse();
}

/** Spec message-pins: the banner above the timeline. jump scrolls the chat to an event. */
export function PinnedBanner({ pins, jump }: { pins: Pin[]; jump: (eventId: string) => void }) {
  const [shownIndex, setShownIndex] = useState(0);
  const [showsList, setShowsList] = useState(false);
  const index = shownIndex < pins.length ? shownIndex : 0;
  const pin = pins[index];
  if (!pin) return null;
  return (
    <div className="relative z-10 flex shrink-0 items-center gap-2.5 bg-surface-raised px-3 py-2">
      <button
        type="button"
        className="flex min-w-0 flex-1 items-center gap-2.5 text-left"
        onClick={() => {
          // Action tapBanner: jump to the shown pin, then show the next older one.
          setShownIndex((index + 1) % pins.length);
          jump(pin.eventId);
        }}
      >
        <Icon path={chatText} size={18} className="shrink-0 text-accent-ink" />
        <span className="flex min-w-0 flex-1 flex-col gap-px">
          <span className="text-caption font-semibold text-accent-ink">
            {pins.length > 1
              ? s('bipe_pinned_banner_count', String(index + 1), String(pins.length))
              : s('bipe_pinned_banner_title')}
          </span>
          <span className="flex min-w-0 gap-1 text-subheadline">
            <span className="shrink-0 font-medium text-ink-secondary">{pin.senderName}</span>
            <span className="min-w-0 truncate text-ink-primary">{pin.text}</span>
          </span>
        </span>
      </button>
      {pins.length > 1 && (
        <button
          type="button"
          className="min-h-11 shrink-0 px-1 text-caption font-semibold text-accent-ink"
          onClick={() => setShowsList(true)}
        >
          {s('bipe_pinned_view_all')}
        </button>
      )}
      {showsList && (
        <Sheet title={s('bipe_pinned_list_title')} onClose={() => setShowsList(false)}>
          <ul className="py-1">
            {pins.map((p) => (
              <li key={p.eventId}>
                <button
                  type="button"
                  className="flex w-full flex-col gap-0.5 px-4 py-2 text-left hover:bg-surface-raised"
                  onClick={() => {
                    setShowsList(false);
                    jump(p.eventId);
                  }}
                >
                  <span className="text-caption font-semibold text-ink-secondary">{p.senderName}</span>
                  <span className="line-clamp-3 text-body break-words text-ink-primary">{p.text}</span>
                </button>
              </li>
            ))}
          </ul>
        </Sheet>
      )}
    </div>
  );
}

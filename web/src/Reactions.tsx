// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec message-reactions. Pills under a bubble (ReactionPills, TimelineRowView.swift): the key and the
// count, 8 x 3 px insets, a capsule in surface.sunken with a line.subtle edge, or in accent.wash with an
// accent.fill edge and the count in accent.ink when the person reacted. A tap toggles the person's own.
// The quick reactions (MessageContextMenu.swift, ChatTimelineModel.quickReactions) open from the react
// button that shows on hover or focus, or with a right-click on the bubble.

import { useEffect } from 'react';
import type { Reaction } from './generated/matrix_sdk_ffi';
import { s } from './ui/i18n';

/** Spec message-reactions notes: the 6 keys in this order. */
export const QUICK_REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🙏'];

export type ReactionPill = { key: string; count: number; own: boolean };

export function pillsOf(reactions: Reaction[], ownUserId: string): ReactionPill[] {
  return reactions
    .filter((r) => r.senders.length > 0)
    .map((r) => ({ key: r.key, count: r.senders.length, own: r.senders.some((x) => x.senderId === ownUserId) }));
}

/** Keys longer than 16 characters are cut (reactionKeyText). */
function keyText(key: string): string {
  const chars = Array.from(key);
  return chars.length > 16 ? chars.slice(0, 16).join('') + '…' : key;
}

export function ReactionPills({ pills, onToggle }: { pills: ReactionPill[]; onToggle: (key: string) => void }) {
  return (
    <div className="flex flex-wrap gap-1">
      {pills.map((p) => {
        const count = p.count.toLocaleString();
        return (
          <button
            key={p.key}
            type="button"
            className={`flex items-center gap-1 rounded-full border px-2 py-[3px] ${p.own ? 'border-accent-fill bg-accent-wash' : 'border-line-subtle bg-surface-sunken'}`}
            aria-label={s('bipe_app_reaction_label', p.key, count)}
            aria-pressed={p.own}
            onClick={() => onToggle(p.key)}
          >
            <span className="text-body">{keyText(p.key)}</span>
            <span className={`text-caption ${p.own ? 'text-accent-ink' : 'text-ink-secondary'}`}>{count}</span>
          </button>
        );
      })}
    </div>
  );
}

/** The row of quick reactions, anchored above the bubble. */
export function QuickReactions({
  own,
  onPick,
  onClose,
}: {
  own: boolean;
  onPick: (key: string) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <>
      <button type="button" className="fixed inset-0 z-20 cursor-default" tabIndex={-1} aria-hidden onClick={onClose} />
      <div
        role="menu"
        aria-label={s('bipe_a11y_react')}
        className={`absolute bottom-full z-30 mb-1 flex gap-0.5 rounded-full bg-surface-raised p-1 shadow-lg ring-1 ring-line-subtle ${own ? 'right-0' : 'left-0'}`}
      >
        {QUICK_REACTIONS.map((key) => (
          <button
            key={key}
            type="button"
            role="menuitem"
            className="grid size-10 place-items-center rounded-full text-[24px] hover:bg-surface-sunken"
            onClick={() => {
              onClose();
              onPick(key);
            }}
          >
            {key}
          </button>
        ))}
      </div>
    </>
  );
}

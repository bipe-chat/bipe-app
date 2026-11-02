// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// An insetGrouped list (SwiftUI List with .insetGrouped): sections as rounded cards on the grouped
// screen colour, a footnote header and footer, 44 px rows with a separator that starts at the text.

import type { ReactNode } from 'react';
import { caretLeft } from '../phosphor/icons';
import { s } from './i18n';
import { Icon } from './Icon';

export function Section({ header, footer, children }: { header?: string; footer?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-1.5">
      {header && <h3 className="px-4 text-footnote text-ink-secondary">{header}</h3>}
      <ul className="overflow-hidden rounded-xl bg-grouped-cell">{children}</ul>
      {footer && <p className="px-4 text-footnote text-ink-secondary">{footer}</p>}
    </section>
  );
}

/** One row. leading is an icon tile or an avatar; the separator starts after it. */
export function Row({ leading, children }: { leading?: ReactNode; children: ReactNode }) {
  return (
    <li className="flex items-center gap-3 pl-4">
      {leading}
      <div className="flex min-h-11 min-w-0 flex-1 items-center gap-2 py-1.5 pr-4 [li:not(:last-child)>&]:border-b-[0.5px] [li:not(:last-child)>&]:border-line-subtle">
        {children}
      </div>
    </li>
  );
}

/** The inline navigation bar of a pushed screen: back (or close) leading, the title in the middle. */
export function NavBar({ title, onBack, backLabel }: { title: string; onBack: () => void; backLabel?: string }) {
  return (
    <header className="grid h-[52px] shrink-0 grid-cols-[44px_1fr_44px] items-center px-2">
      <button
        type="button"
        className="grid size-11 place-items-center rounded-full text-glyph-primary"
        aria-label={backLabel ?? s('bipe_a11y_back')}
        onClick={onBack}
      >
        <Icon path={caretLeft} size={22} />
      </button>
      <h2 className="truncate text-center text-headline text-ink-primary">{title}</h2>
    </header>
  );
}

/** The Bipe badge after the name of an official account (OfficialBadge.swift). */
export function OfficialBadge() {
  return (
    <span
      className="shrink-0 rounded-full border border-trust-verified-line bg-trust-verified-wash px-1.5 text-caption2 font-semibold text-trust-verified-ink"
      aria-label={s('bipe_badge_official_a11y')}
      role="img"
    >
      <span aria-hidden>{s('bipe_badge_official')}</span>
    </span>
  );
}

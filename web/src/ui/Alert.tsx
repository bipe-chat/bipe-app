// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The system alert of the apps: a title, a short body, Cancel and one action, the destructive one in
// trust.danger. Escape or a click outside cancels.

import { useEffect, type ReactNode } from 'react';
import { s } from './i18n';

export function Alert({
  title,
  children,
  action,
  destructive = true,
  busy,
  onCancel,
  onAction,
}: {
  title: string;
  children?: ReactNode;
  action: string;
  destructive?: boolean;
  busy?: boolean;
  onCancel: () => void;
  onAction: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);
  return (
    // The dim behind an alert is black at 40% in both schemes, as the system draws it; no token covers it.
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-6" onClick={onCancel}>
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="alert-title"
        className="flex w-full max-w-[300px] flex-col overflow-hidden rounded-[14px] bg-surface-raised text-center"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-col gap-1 p-4">
          <h3 id="alert-title" className="text-headline text-ink-primary">
            {title}
          </h3>
          {children && <div className="text-footnote text-ink-primary">{children}</div>}
        </div>
        <div className="grid grid-cols-2 border-t-[0.5px] border-line-subtle">
          <button
            type="button"
            autoFocus
            disabled={busy}
            className="min-h-11 text-body text-accent-ink"
            onClick={onCancel}
          >
            {s('bipe_app_cancel')}
          </button>
          <button
            type="button"
            disabled={busy}
            className={`min-h-11 border-l-[0.5px] border-line-subtle text-headline disabled:opacity-50 ${destructive ? 'text-trust-danger-ink' : 'text-accent-ink'}`}
            onClick={onAction}
          >
            {action}
          </button>
        </div>
      </div>
    </div>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Shared parts of the Trabalho screens on the web (ADR 0226): loading a read, the error line, buttons,
// fields and the two-column layout (a list next to its detail on a wide screen, one column on a narrow one).
// The screens follow the same bipe-shared specs as the apps; these parts only carry the web layout.

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { s } from '../ui/i18n';
import { Spinner } from '../ui/Spinner';
import { WorkError, type BusinessClient } from './business-client';

/** Every Trabalho tool screen takes the account's business client and a way back to the Trabalho list. */
export type WorkScreenProps = { client: BusinessClient; onBack: () => void };

/** The user-facing text of a failed call: no connection, or the generic error. Never the raw API message. */
export function errorText(e: unknown): string {
  return e instanceof WorkError && e.code === 'network' ? s('bipe_error_network') : s('bipe_error_generic');
}

/** A read that loads on mount and on reload(). data stays while a reload runs. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps);
  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setData(await run());
      setError(undefined);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [run]);
  useEffect(() => {
    void reload();
  }, [reload]);
  return { data, setData, error, loading, reload };
}

/**
 * A write with a busy flag and its error. run() returns the result, or undefined after a failure, which
 * shows in error until the next run.
 */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const run = useCallback(async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(undefined);
    try {
      return await fn();
    } catch (e) {
      setError(e);
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, error, run, clearError: () => setError(undefined) };
}

export function ErrorLine({ error }: { error: unknown }) {
  if (error === undefined) return null;
  return (
    <p role="alert" className="text-footnote text-trust-danger-ink">
      {errorText(error)}
    </p>
  );
}

/** A centred spinner while the first read runs, or its error with a retry. */
export function LoadState({ loading, error, onRetry }: { loading: boolean; error: unknown; onRetry: () => void }) {
  if (loading)
    return (
      <div className="grid flex-1 place-items-center p-8">
        <Spinner />
      </div>
    );
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
      <ErrorLine error={error} />
      <SecondaryButton onClick={onRetry}>{s('bipe_app_retry')}</SecondaryButton>
    </div>
  );
}

type ButtonProps = { onClick?: () => void; disabled?: boolean; children: ReactNode; type?: 'button' | 'submit' };

export function PrimaryButton({ onClick, disabled, children, type = 'button' }: ButtonProps) {
  return (
    <button
      type={type}
      className="min-h-11 rounded-full bg-accent-fill px-5 text-subheadline font-semibold text-ink-on-accent disabled:opacity-50"
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}

export function SecondaryButton({ onClick, disabled, children, type = 'button' }: ButtonProps) {
  return (
    <button
      type={type}
      className="min-h-11 rounded-full bg-surface-sunken px-5 text-subheadline font-semibold text-ink-primary disabled:opacity-50"
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}

/** A plain text action, as the apps' toolbar buttons. */
export function TextButton({ onClick, disabled, children, danger }: ButtonProps & { danger?: boolean }) {
  return (
    <button
      type="button"
      className={`min-h-11 px-2 text-body disabled:opacity-50 ${danger ? 'text-trust-danger-ink' : 'text-accent-ink'}`}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}

/** A labelled field. children is the input. */
export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-footnote text-ink-secondary">{label}</span>
      {children}
      {hint && <span className="text-caption text-ink-secondary">{hint}</span>}
    </label>
  );
}

export const inputClass =
  'min-h-11 rounded-lg bg-grouped-cell px-3 text-body text-ink-primary outline-none focus-visible:outline-2 focus-visible:outline-accent-fill';

/**
 * The web layout of a tool: the list on the left and the open item on the right, from 900 px up. Narrower,
 * only one shows: the detail when one is open, else the list.
 */
export function SplitPane({ list, detail }: { list: ReactNode; detail?: ReactNode }) {
  return (
    <div className="flex min-h-0 flex-1">
      <div
        className={`min-h-0 flex-col overflow-y-auto min-[900px]:flex min-[900px]:w-[380px] min-[900px]:shrink-0 min-[900px]:border-r-[0.5px] min-[900px]:border-line-subtle ${detail ? 'hidden' : 'flex flex-1'}`}
      >
        {list}
      </div>
      {detail && <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">{detail}</div>}
    </div>
  );
}

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

/** A BRL amount in cents, as the apps show it: R$ 12,50. */
export function money(cents: number): string {
  return brl.format(cents / 100);
}

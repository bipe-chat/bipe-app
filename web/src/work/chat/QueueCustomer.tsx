// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec queue-customer (QueueViews.swift): join the queue of the day, see the position and a wait range. The wait is
// an estimate; with no data it says bipe_queue_wait_unknown, never zero. Bipe never asks for a location (ADR 0107).

import type { QueuePosition } from '../../bipe/generated/business-api';
import { NavBar } from '../../ui/List';
import { s } from '../../ui/i18n';
import type { BusinessClient } from '../business-client';
import { LoadState, PrimaryButton, useAction, useLoad } from '../common';
import { queueErrorText } from '../Queue';

export function QueuePositionSummary({ position }: { position: QueuePosition }) {
  if (position.position === 0 || position.place.status === 'called')
    return <span className="text-headline text-trust-verified-ink">{s('bipe_queue_called')}</span>;
  return (
    <>
      <span className="text-headline text-ink-primary">
        {s('bipe_queue_position_number', String(position.position))}
      </span>
      <span className="text-subheadline text-ink-secondary">
        {position.wait
          ? s(
              'bipe_queue_wait',
              s('bipe_queue_wait_range', String(position.wait.min_minutes), String(position.wait.max_minutes)),
            )
          : s('bipe_queue_wait_unknown')}
      </span>
      {position.wait && <span className="text-footnote text-ink-secondary">{s('bipe_queue_estimate_hint')}</span>}
    </>
  );
}

export function QueueCustomer({
  api,
  business,
  onClose,
}: {
  api: BusinessClient;
  business: string;
  onClose: (message?: string) => void;
}) {
  const queue = useLoad(() => api.publicQueue(business), [api, business]);
  const join = useAction();
  return (
    <>
      <NavBar title={s('bipe_queue_title')} onBack={() => onClose()} backLabel={s('bipe_app_cancel')} />
      {!queue.data ? (
        <LoadState loading={queue.loading} error={queue.error} onRetry={() => void queue.reload()} />
      ) : (
        <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8 text-center">
          {queue.data.open ? (
            <PrimaryButton
              disabled={join.busy}
              onClick={() =>
                void join.run(() => api.joinQueue(business)).then((place) => {
                  if (place) onClose(s('bipe_queue_joined'));
                })
              }
            >
              {s('bipe_queue_join')}
            </PrimaryButton>
          ) : (
            <p className="text-body text-ink-secondary">{s('bipe_queue_closed')}</p>
          )}
          {join.error !== undefined && (
            <p role="alert" className="text-footnote text-trust-danger-ink">
              {queueErrorText(join.error)}
            </p>
          )}
        </div>
      )}
    </>
  );
}

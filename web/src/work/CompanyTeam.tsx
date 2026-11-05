// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec company-team on the web (ADR 0251, ADR 0252): the same list as CompanyTeamView.swift, read only. Every action is
// in the app (ADR 0226 point 6), and bipe_web_work_phone_only says so.

import { useEffect, useState } from 'react';
import type { SeatDevice } from '../bipe/generated/business-api';
import { s } from '../ui/i18n';
import { NavBar, Row, Section } from '../ui/List';
import type { BusinessClient } from './business-client';
import { LoadState, useLoad, useWorkHost } from './common';

const deviceText = (device: SeatDevice) =>
  device.display_name ?? s(device.kind === 'web' ? 'bipe_team_device_web' : 'bipe_team_device_phone');

export function CompanyTeamScreen({ client, onBack }: { client: BusinessClient; onBack: () => void }) {
  const load = useLoad(() => client.seats(), [client]);
  const host = useWorkHost();
  const [handle, setHandle] = useState('');
  useEffect(() => {
    void host?.ownHandle().then((own) => setHandle(own ? `@${own}` : ''));
  }, [host]);
  const list = load.data;
  const people = list?.seats.filter((seat) => seat.status === 'active') ?? [];

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-grouped-screen">
      <NavBar title={s('bipe_team_title')} onBack={onBack} />
      {!list ? (
        <LoadState loading={load.loading} error={load.error} onRetry={() => void load.reload()} />
      ) : (
        <div className="flex flex-col gap-6 px-4 pb-8">
          <Section footer={people.length === 1 ? s('bipe_team_alone_body', handle) : undefined}>
            {people.map((seat) => (
              <Row key={seat.id}>
                <span className="flex min-w-0 flex-1 flex-col py-1">
                  <span className="truncate text-body text-ink-primary">
                    {list.me.seat_id === seat.id ? s('bipe_team_you') : seat.name}
                  </span>
                  <span className="truncate text-footnote text-ink-secondary">
                    {s(seat.role === 'owner' ? 'bipe_team_role_owner' : 'bipe_team_role_attendant')}
                  </span>
                  {seat.devices.map((device) => (
                    <span key={device.device_id} className="truncate text-footnote text-ink-secondary">
                      {deviceText(device)}
                    </span>
                  ))}
                </span>
              </Row>
            ))}
          </Section>
          {list.unassigned_devices.length > 0 && (
            <Section header={s('bipe_team_unassigned_title')} footer={s('bipe_team_unassigned_body')}>
              {list.unassigned_devices.map((device) => (
                <Row key={device.device_id}>
                  <span className="truncate py-1 text-body text-ink-primary">{deviceText(device)}</span>
                </Row>
              ))}
            </Section>
          )}
          {people.length > 1 && (
            <Section header={s('bipe_team_shared_title')}>
              {(['bipe_team_shared_chats', 'bipe_team_shared_read', 'bipe_team_shared_changes'] as const).map((line) => (
                <Row key={line}>
                  <span className="py-1 text-body text-ink-primary">{s(line)}</span>
                </Row>
              ))}
            </Section>
          )}
          <p className="px-4 text-footnote text-ink-secondary">{s('bipe_web_work_phone_only')}</p>
        </div>
      )}
    </div>
  );
}

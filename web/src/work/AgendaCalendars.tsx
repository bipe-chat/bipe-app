// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec agenda-settings (ADR 0101): per agenda, the secret feed for a calendar app and the busy-time imports.

import { useState } from 'react';
import type { Agenda } from '../bipe/generated/business-api';
import { s } from '../ui/i18n';
import { Row, Section } from '../ui/List';
import type { BusinessClient } from './business-client';
import { Field, inputClass, SecondaryButton, TextButton, useAction, useLoad } from './common';

export function AgendaCalendars({ client, agendas }: { client: BusinessClient; agendas: Agenda[] }) {
  return (
    <>
      {agendas.map((agenda) => (
        <AgendaCalendar key={agenda.id} client={client} agenda={agenda} showsName={agendas.length > 1} />
      ))}
    </>
  );
}

function AgendaCalendar({ client, agenda, showsName }: { client: BusinessClient; agenda: Agenda; showsName: boolean }) {
  const imports = useLoad(() => client.busyImports(agenda.id).then((r) => r.imports), [client, agenda.id]);
  const action = useAction();
  const [firstName, setFirstName] = useState(false);
  const [address, setAddress] = useState('');
  const [feedUrl, setFeedUrl] = useState<string>();
  const [invalid, setInvalid] = useState(false);
  const title = s('bipe_agenda_calendar_section');
  return (
    <Section
      header={showsName ? `${title} · ${agenda.name}` : title}
      footer={`${s('bipe_agenda_feed_footer')} ${s('bipe_agenda_busy_footer')}`}
    >
      <Row>
        <div className="flex min-w-0 flex-1 flex-col gap-2 py-2">
          <label className="flex items-center gap-2 text-body text-ink-primary">
            <input type="checkbox" checked={firstName} onChange={(e) => setFirstName(e.target.checked)} />
            {s('bipe_agenda_feed_first_name')}
          </label>
          <div>
            <SecondaryButton
              disabled={action.busy}
              onClick={() =>
                void action.run(() => client.issueAgendaFeed(agenda.id, firstName)).then((feed) => {
                  if (feed) setFeedUrl(feed.url);
                })
              }
            >
              {s('bipe_agenda_feed_get')}
            </SecondaryButton>
          </div>
          {feedUrl && <input readOnly aria-label={s('bipe_agenda_feed_get')} className={inputClass} value={feedUrl} onFocus={(e) => e.target.select()} />}
          {imports.data?.map((item) => (
            <div key={item.id} className="flex flex-col">
              <div>
                <TextButton
                  danger
                  onClick={() => void action.run(() => client.removeBusyImport(agenda.id, item.id)).then(() => imports.reload())}
                >
                  {s('bipe_agenda_busy_remove')}
                </TextButton>
              </div>
              {item.failing && <span className="text-footnote text-trust-danger-ink">{s('bipe_agenda_busy_failing')}</span>}
            </div>
          ))}
          <Field label={s('bipe_agenda_busy_url')}>
            <input className={inputClass} type="url" value={address} onChange={(e) => setAddress(e.target.value)} />
          </Field>
          <div>
            <SecondaryButton
              disabled={action.busy || !address.trim()}
              onClick={() => {
                setInvalid(false);
                void client.addBusyImport(agenda.id, address.trim()).then(
                  () => {
                    setAddress('');
                    void imports.reload();
                  },
                  () => setInvalid(true),
                );
              }}
            >
              {s('bipe_agenda_busy_add')}
            </SecondaryButton>
          </div>
          {invalid && <p role="alert" className="text-footnote text-trust-danger-ink">{s('bipe_agenda_busy_invalid')}</p>}
        </div>
      </Row>
    </Section>
  );
}

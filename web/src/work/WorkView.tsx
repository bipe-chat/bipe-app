// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Trabalho on the web (ADR 0226, bipe-chat/bipe#979): the business-tools list of a company account
// (spec business-tools, WorkView on iOS) and the opening hours (spec work-hours). Each row opens its tool
// in this pane. The actions that ADR 0226 point 6 keeps on an attested phone (create the company, CNPJ,
// seal, domains, seats, Pix charges) are not here; one footer line says they are in the app.

import { useState } from 'react';
import type { StringKey } from '../bipe/generated/strings';
import { caretRight } from '../phosphor/icons';
import { locale, s } from '../ui/i18n';
import { Icon } from '../ui/Icon';
import { NavBar, Row, Section } from '../ui/List';
import type { BusinessClient, WorkHours } from './business-client';
import { AgendaScreen } from './Agenda';
import { CatalogScreen } from './Catalog';
import {
  LoadState,
  setNameLookup,
  PrimaryButton,
  inputClass,
  useAction,
  useLoad,
  WorkHostProvider,
  type WorkHost,
} from './common';
import { OrdersScreen } from './Orders';
import { QueueScreen } from './Queue';
import { QuotesScreen } from './Quotes';

// The URL segment of each tool: /trabalho/<tool> (router.ts).
type Tool = 'agenda' | 'catalogo' | 'pedidos' | 'orcamentos' | 'fila' | 'horario';

const TOOLS: { tool: Tool; title: StringKey }[] = [
  { tool: 'agenda', title: 'bipe_tab_agenda' },
  { tool: 'catalogo', title: 'bipe_catalog_title' },
  { tool: 'pedidos', title: 'bipe_orders_title' },
  { tool: 'orcamentos', title: 'bipe_quotes_title' },
  { tool: 'fila', title: 'bipe_queue_title' },
];

/**
 * Shown only when the current account is a company account (bipe-api account type, ADR 0132). tool is the open
 * tool from the URL; onTool changes it (undefined: the list); onClose leaves Trabalho. nameOf gives the
 * Matrix profile name of a signed-in customer. host is the chat side: open a DM, send a text, the own @handle.
 */
export function WorkView({
  client,
  tool,
  onTool,
  onClose,
  nameOf,
  host,
}: {
  client: BusinessClient;
  tool?: string;
  onTool: (tool: string | undefined) => void;
  onClose: () => void;
  nameOf: (userId: string) => Promise<string | undefined>;
  host: WorkHost;
}) {
  setNameLookup(nameOf);
  return (
    <WorkHostProvider value={host}>
      <ToolScreen client={client} tool={tool} onTool={onTool} onClose={onClose} />
    </WorkHostProvider>
  );
}

function ToolScreen({
  client,
  tool,
  onTool,
  onClose,
}: {
  client: BusinessClient;
  tool?: string;
  onTool: (tool: string | undefined) => void;
  onClose: () => void;
}) {
  const back = () => onTool(undefined);
  switch (tool as Tool | undefined) {
    case 'agenda':
      return <AgendaScreen client={client} onBack={back} />;
    case 'catalogo':
      return <CatalogScreen client={client} onBack={back} />;
    case 'pedidos':
      return <OrdersScreen client={client} onBack={back} />;
    case 'orcamentos':
      return <QuotesScreen client={client} onBack={back} />;
    case 'fila':
      return <QueueScreen client={client} onBack={back} />;
    case 'horario':
      return <HoursScreen client={client} onBack={back} />;
    default:
      return <ToolList client={client} onOpen={onTool} onClose={onClose} />;
  }
}

function ToolList({ client, onOpen, onClose }: { client: BusinessClient; onOpen: (t: Tool) => void; onClose: () => void }) {
  // The Agenda row's subtitle (spec business-tools agendaPublished). A failed read shows no subtitle.
  const agenda = useLoad(() => client.agenda().catch(() => undefined), [client]);
  const agendaSubtitle =
    agenda.data === undefined
      ? undefined
      : agenda.data.published
        ? s('bipe_agenda_published')
        : s('bipe_agenda_not_published');
  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-grouped-screen">
      <NavBar title={s('bipe_tab_work')} onBack={onClose} />
      <div className="flex flex-col gap-6 px-4 pb-8">
        <Section header={s('bipe_business_tools_title')} footer={s('bipe_business_tools_footer')}>
          {TOOLS.map(({ tool, title }) => (
            <ToolRow
              key={tool}
              title={s(title)}
              subtitle={tool === 'agenda' ? agendaSubtitle : undefined}
              onClick={() => onOpen(tool)}
            />
          ))}
        </Section>
        <Section>
          <ToolRow title={s('bipe_work_hours_title')} onClick={() => onOpen('horario')} />
        </Section>
        <p className="px-4 text-footnote text-ink-secondary">{s('bipe_web_work_phone_only')}</p>
      </div>
    </div>
  );
}

function ToolRow({ title, subtitle, onClick }: { title: string; subtitle?: string; onClick: () => void }) {
  return (
    <Row>
      <button type="button" className="flex min-w-0 flex-1 items-center gap-2 py-1 text-left" onClick={onClick}>
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-body text-ink-primary">{title}</span>
          {subtitle && <span className="truncate text-footnote text-ink-secondary">{subtitle}</span>}
        </span>
        <Icon path={caretRight} size={16} className="text-glyph-secondary" />
      </button>
    </Row>
  );
}

const weekdayName = (isoDay: number) =>
  // 2024-01-01 was a Monday: ISO day 1.
  new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(new Date(2024, 0, isoDay));

/**
 * Spec work-hours: the switch and the first range of each weekday. As on the phone, saving keeps one range a
 * day; a day with no range is closed. The silence outside the hours is a phone rule; the web only edits the
 * hours that every device of the account reads.
 */
function HoursScreen({ client, onBack }: { client: BusinessClient; onBack: () => void }) {
  const load = useLoad(() => client.workHours(), [client]);
  const save = useAction();
  const [draft, setDraft] = useState<WorkHours>();
  const hours = draft ?? load.data;

  const setDay = (day: number, range: { start: string; end: string } | undefined) => {
    if (!hours) return;
    const days = hours.days.filter((d) => d.day !== day);
    if (range) days.push({ day, ranges: [range] });
    setDraft({ ...hours, days: days.sort((a, b) => a.day - b.day) });
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_work_hours_title')} onBack={onBack} />
      {!hours ? (
        <LoadState loading={load.loading} error={load.error} onRetry={load.reload} />
      ) : (
        <form
          className="flex flex-col gap-6 overflow-y-auto px-4 pb-8"
          onSubmit={async (e) => {
            e.preventDefault();
            const saved = await save.run(() => client.setWorkHours(hours));
            if (saved) {
              load.setData(saved);
              setDraft(undefined);
            } else {
              // Spec work-hours phase failed: the values go back.
              setDraft(undefined);
            }
          }}
        >
          <Section footer={s('bipe_work_hours_body')}>
            <Row>
              <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                <span className="min-w-0 flex-1 text-body text-ink-primary">{s('bipe_work_hours_enabled')}</span>
                <input
                  type="checkbox"
                  role="switch"
                  className="size-5 accent-[var(--color-accent-fill)]"
                  checked={hours.enabled}
                  onChange={(e) => setDraft({ ...hours, enabled: e.target.checked })}
                />
              </label>
            </Row>
          </Section>
          <Section>
            {[1, 2, 3, 4, 5, 6, 7].map((day) => {
              const range = hours.days.find((d) => d.day === day)?.ranges[0];
              return (
                <Row key={day}>
                  <span className="min-w-0 flex-1 text-body capitalize text-ink-primary">{weekdayName(day)}</span>
                  <label className="flex items-center gap-2 text-footnote text-ink-secondary">
                    <input
                      type="checkbox"
                      checked={range === undefined}
                      onChange={(e) => setDay(day, e.target.checked ? undefined : { start: '09:00', end: '18:00' })}
                    />
                    {s('bipe_work_hours_closed')}
                  </label>
                  {range && (
                    <span className="flex items-center gap-1">
                      <input
                        type="time"
                        className={inputClass}
                        value={range.start}
                        onChange={(e) => setDay(day, { ...range, start: e.target.value })}
                      />
                      <input
                        type="time"
                        className={inputClass}
                        value={range.end === '24:00' ? '23:59' : range.end}
                        onChange={(e) => setDay(day, { ...range, end: e.target.value === '23:59' ? '24:00' : e.target.value })}
                      />
                    </span>
                  )}
                </Row>
              );
            })}
          </Section>
          {save.error !== undefined && <p className="text-footnote text-trust-danger-ink">{s('bipe_work_hours_save_error')}</p>}
          <div>
            <PrimaryButton type="submit" disabled={save.busy || draft === undefined}>
              {s('bipe_app_save')}
            </PrimaryButton>
          </div>
        </form>
      )}
    </div>
  );
}

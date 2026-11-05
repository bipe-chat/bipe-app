// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec business-tools on the web (ADR 0194, ADR 0226 point 4): Hoje, Próximo passo, one suggestion, the tools and
// Todas as ferramentas, the same as WorkHome.swift. A failed read of the tools lists every tool, so that nothing
// hides. The web has no sector question at sign-up, because a work account is created on the phone: only the Ramo row.

import type { ReactNode } from 'react';
import { useState } from 'react';
import type { BusinessToolId, BusinessToolStatus, WorkSetupStep } from '../bipe/generated/business-api';
import type { StringKey } from '../bipe/generated/strings';
import { businessTools as registry } from '../bipe/generated/registries.json';
import { caretRight } from '../phosphor/icons';
import { Alert } from '../ui/Alert';
import { Icon } from '../ui/Icon';
import { NavBar, Row, Section } from '../ui/List';
import { s, timeText } from '../ui/i18n';
import type { BusinessClient } from './business-client';
import { LoadState, TextButton, ErrorLine, money, useAction, useLoad, useWorkHost } from './common';

// The URL segment of each tool: /trabalho/<tool> (router.ts).
const SEGMENT: Record<BusinessToolId, string> = {
  agenda: 'agenda',
  catalog: 'catalogo',
  orders: 'pedidos',
  quotes: 'orcamentos',
  queue: 'fila',
};
const TITLE: Record<BusinessToolId, StringKey> = {
  agenda: 'bipe_tab_agenda',
  catalog: 'bipe_catalog_title',
  orders: 'bipe_orders_title',
  quotes: 'bipe_quotes_title',
  queue: 'bipe_queue_title',
};

const stateText = (tool: BusinessToolStatus) =>
  tool.state === 'live' ? s('bipe_work_tool_state_live') : s('bipe_work_tool_state_setup');

const sectorText = (id: string) => s(`bipe_sector_${id}` as StringKey);

export function ToolRow({ title, subtitle, onClick }: { title: string; subtitle?: string; onClick: () => void }) {
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

function stepTitle(id: WorkSetupStep['id'], firstTool: BusinessToolId): string {
  if (id === 'first_tool') return s('bipe_work_step_first_tool', s(TITLE[firstTool]));
  return s(`bipe_work_step_${id}` as StringKey);
}

function stepBody(id: WorkSetupStep['id']): string {
  if (id === 'catalog') return s('bipe_work_step_catalog_body', String(registry.catalogMinItems));
  return s(`bipe_work_step_${id}_body` as StringKey);
}

export function WorkHome({
  client,
  onOpen,
  onClose,
}: {
  client: BusinessClient;
  onOpen: (tool: string) => void;
  onClose: () => void;
}) {
  const home = useLoad(async () => {
    const [tools, setup, suggestions] = await Promise.all([
      client.businessTools().catch(() => undefined),
      client.workSetup().catch(() => undefined),
      client.workSuggestions().then((r) => r.suggestions).catch(() => []),
    ]);
    const hasLive = tools?.tools.some((t) => t.state === 'live');
    const today = hasLive ? await client.businessToday().catch(() => undefined) : undefined;
    return { tools, setup, suggestion: suggestions[0], today };
  }, [client]);
  const action = useAction();
  const [blocked, setBlocked] = useState<BusinessToolStatus>();

  const act = (call: () => Promise<unknown>) => void action.run(call).then(() => home.reload());

  const turnOn = (tool: BusinessToolStatus) => {
    if (tool.missing) {
      setBlocked(tool);
      return;
    }
    void action.run(() => client.setBusinessTool(tool.id, 'setup')).then(() => onOpen(SEGMENT[tool.id]));
  };

  const data = home.data;
  const nextStep = data?.setup?.steps.find((step) => !step.done);
  const rows = data?.tools?.tools.filter((t) => t.state !== 'off') ?? [];
  const off = data?.tools?.tools.filter((t) => t.state === 'off') ?? [];

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-grouped-screen">
      <NavBar title={s('bipe_tab_work')} onBack={onClose} />
      {!data ? (
        <LoadState loading={home.loading} error={home.error} onRetry={() => void home.reload()} />
      ) : (
        <div className="flex flex-col gap-6 px-4 pb-8">
          <ErrorLine error={action.error} />
          {data.today && (
            <Section header={s('bipe_work_today')}>
              {data.today.bookings.map((booking) => (
                <TodayRow
                  key={booking.id}
                  title={timeText(Date.parse(booking.start))}
                  action={
                    booking.status === 'requested' ? (
                      <TextButton onClick={() => act(() => client.bookingAction(booking.id, 'accept'))}>
                        {s('bipe_work_today_confirm')}
                      </TextButton>
                    ) : undefined
                  }
                />
              ))}
              {data.today.orders.map((order) => (
                <TodayRow
                  key={order.id}
                  title={money(order.total_amount_cents)}
                  action={
                    order.status === 'sent' ? (
                      <TextButton onClick={() => act(() => client.orderAction(order.id, 'accept'))}>
                        {s('bipe_work_today_accept')}
                      </TextButton>
                    ) : undefined
                  }
                />
              ))}
              {(data.today.queue_open || data.today.queue_waiting > 0) && (
                <TodayRow
                  title={s('bipe_work_today_queue_waiting', String(data.today.queue_waiting))}
                  action={
                    data.today.queue_waiting > 0 ? (
                      <TextButton onClick={() => act(() => client.callNext())}>
                        {s('bipe_work_today_call_next')}
                      </TextButton>
                    ) : undefined
                  }
                />
              )}
              {data.today.quote_requests.length > 0 && (
                <TodayRow
                  title={s('bipe_work_today_quotes')}
                  action={
                    <TextButton onClick={() => onOpen(SEGMENT.quotes)}>{s('bipe_work_today_answer')}</TextButton>
                  }
                />
              )}
            </Section>
          )}
          {data.setup && nextStep && (
            <Section
              header={s('bipe_work_next_step')}
              footer={s('bipe_work_progress', String(data.setup.done_count), String(data.setup.steps.length))}
            >
              <ToolRow
                title={stepTitle(nextStep.id, data.setup.first_tool)}
                subtitle={stepBody(nextStep.id)}
                onClick={() => onOpen('passos')}
              />
            </Section>
          )}
          {data.suggestion && (
            <Section>
              <Row>
                <div className="flex min-w-0 flex-1 flex-col gap-1 py-1">
                  <p className="text-body text-ink-primary">{s(`bipe_work_suggest_${data.suggestion.id}` as StringKey)}</p>
                  <div className="flex flex-wrap gap-x-1">
                    <TextButton
                      onClick={() => {
                        const tool = data.tools?.tools.find((t) => t.id === data.suggestion?.tool);
                        if (tool) turnOn(tool);
                      }}
                    >
                      {s('bipe_work_tool_turn_on')}
                    </TextButton>
                    <TextButton onClick={() => act(() => client.dismissWorkSuggestion(data.suggestion!.id))}>
                      {s('bipe_work_suggest_dismiss')}
                    </TextButton>
                  </div>
                </div>
              </Row>
            </Section>
          )}
          {rows.length > 0 && (
            <Section header={s('bipe_business_tools_title')} footer={s('bipe_business_tools_footer')}>
              {rows.map((tool) => (
                <ToolRow
                  key={tool.id}
                  title={s(TITLE[tool.id])}
                  subtitle={stateText(tool)}
                  onClick={() => onOpen(SEGMENT[tool.id])}
                />
              ))}
            </Section>
          )}
          {!data.tools ? (
            <Section>
              {registry.tools.map(({ id }) => (
                <ToolRow
                  key={id}
                  title={s(TITLE[id as BusinessToolId])}
                  onClick={() => onOpen(SEGMENT[id as BusinessToolId])}
                />
              ))}
            </Section>
          ) : (
            off.length > 0 && (
              <Section header={s('bipe_work_all_tools')}>
                {off.map((tool) => (
                  <ToolRow
                    key={tool.id}
                    title={s(TITLE[tool.id])}
                    subtitle={s('bipe_work_tool_turn_on')}
                    onClick={() => turnOn(tool)}
                  />
                ))}
              </Section>
            )
          )}
          <Section>
            <ToolRow title={s('bipe_work_hours_title')} onClick={() => onOpen('horario')} />
            <ToolRow
              title={s('bipe_work_sector_row')}
              subtitle={data.tools?.sector ? sectorText(data.tools.sector) : s('bipe_work_sector_none')}
              onClick={() => onOpen('ramo')}
            />
            <ToolRow title={s('bipe_brand_marks_title')} onClick={() => onOpen('selos')} />
            <ToolRow title={s('bipe_business_kit_title')} onClick={() => onOpen('kit')} />
          </Section>
          <p className="px-4 text-footnote text-ink-secondary">{s('bipe_web_work_phone_only')}</p>
        </div>
      )}
      {blocked && (
        <Alert
          title={blocked.missing === 'catalog_product' ? s('bipe_work_tool_needs_product') : s('bipe_work_tool_needs_service')}
          action={s('bipe_work_tool_open_catalog')}
          destructive={false}
          onCancel={() => setBlocked(undefined)}
          onAction={() => {
            setBlocked(undefined);
            onOpen(SEGMENT.catalog);
          }}
        />
      )}
    </div>
  );
}

function TodayRow({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <Row>
      <div className="flex min-w-0 flex-1 items-center gap-2 py-1">
        <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{title}</span>
        {action}
      </div>
    </Row>
  );
}

/** Spec work-setup. The server computes each step. Perfil, Selo stay text here: they are on the phone (ADR 0226). */
export function WorkSetupScreen({
  client,
  onBack,
  onTool,
}: {
  client: BusinessClient;
  onBack: () => void;
  onTool: (tool: string) => void;
}) {
  const setup = useLoad(() => client.workSetup(), [client]);
  const host = useWorkHost();
  const share = async () => {
    const handle = await host?.ownHandle();
    if (handle) {
      const url = `https://bipe.chat/@${handle}`;
      if (navigator.share) await navigator.share({ url }).catch(() => undefined);
      else await navigator.clipboard?.writeText(url).catch(() => undefined);
    }
    await client.workLinkOpened().catch(() => undefined);
    await setup.reload();
  };
  const data = setup.data;
  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-grouped-screen">
      <NavBar title={s('bipe_work_setup_title')} onBack={onBack} />
      {!data ? (
        <LoadState loading={setup.loading} error={setup.error} onRetry={() => void setup.reload()} />
      ) : (
        <div className="flex flex-col gap-6 px-4 pb-8">
          <Section
            footer={
              data.done_count === data.steps.length
                ? s('bipe_work_setup_done')
                : s('bipe_work_progress', String(data.done_count), String(data.steps.length))
            }
          >
            {data.steps.map((step) => {
              const title = `${step.done ? '✓ ' : ''}${stepTitle(step.id, data.first_tool)}`;
              const open =
                step.id === 'catalog'
                  ? () => onTool(SEGMENT.catalog)
                  : step.id === 'first_tool'
                    ? () => onTool(SEGMENT[data.first_tool])
                    : step.id === 'link'
                      ? () => void share()
                      : undefined;
              return open ? (
                <ToolRow key={step.id} title={title} subtitle={stepBody(step.id)} onClick={open} />
              ) : (
                <Row key={step.id}>
                  <span className="flex min-w-0 flex-1 flex-col py-1">
                    <span className="text-body text-ink-primary">{title}</span>
                    <span className="text-footnote text-ink-secondary">{stepBody(step.id)}</span>
                  </span>
                </Row>
              );
            })}
          </Section>
          <p className="px-4 text-footnote text-ink-secondary">{s('bipe_web_work_phone_only')}</p>
        </div>
      )}
    </div>
  );
}

/** Spec work-sector, from the Ramo row. It never limits the tools (ADR 0194 point 9). */
export function WorkSectorScreen({ client, onBack }: { client: BusinessClient; onBack: () => void }) {
  const tools = useLoad(() => client.businessTools(), [client]);
  const save = useAction();
  const pick = (sector?: string) => void save.run(() => client.setWorkSector(sector)).then((r) => r && onBack());
  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-grouped-screen">
      <NavBar title={s('bipe_work_sector_title')} onBack={onBack} />
      <div className="flex flex-col gap-6 px-4 pb-8">
        <ErrorLine error={save.error} />
        <Section footer={s('bipe_work_sector_body')}>
          {registry.sectors.map(({ id }) => (
            <ToolRow
              key={id}
              title={`${tools.data?.sector === id ? '✓ ' : ''}${sectorText(id)}`}
              onClick={() => pick(id)}
            />
          ))}
        </Section>
        <div>
          <TextButton disabled={save.busy} onClick={() => pick()}>
            {s('bipe_work_sector_skip')}
          </TextButton>
        </div>
      </div>
    </div>
  );
}

/** Each owner screen has Publicar (live) and Desligar (off), below the screen. */
export function WithToolState({ client, id, screen }: { client: BusinessClient; id: BusinessToolId; screen: ReactNode }) {
  const tools = useLoad(() => client.businessTools(), [client]);
  const action = useAction();
  const web = useLoad(() => client.webSettings().catch(() => undefined), [client]);
  const tool = tools.data?.tools.find((t) => t.id === id);
  const webKey = id === 'agenda' ? 'web_booking' : id === 'orders' ? 'web_orders' : id === 'queue' ? 'web_queue' : undefined;
  const set = (state: 'live' | 'off') =>
    void action.run(() => client.setBusinessTool(id, state)).then((updated) => {
      if (updated) tools.setData(updated);
    });
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex min-h-0 flex-1 flex-col">{screen}</div>
      {tool && tool.state !== 'off' && (
        <div className="bipe-bar flex shrink-0 items-center gap-2 border-t-[0.5px] border-line-subtle px-4 py-1">
          <span className="min-w-0 flex-1 truncate text-footnote text-ink-secondary">{stateText(tool)}</span>
          <ErrorLine error={action.error} />
          {webKey && tool.state === 'live' && web.data && (
            <label className="flex items-center gap-1.5 text-footnote text-ink-primary">
              <input
                type="checkbox"
                checked={web.data[webKey]}
                disabled={action.busy}
                onChange={(e) => {
                  const next = { ...web.data!, [webKey]: e.target.checked };
                  void action.run(() => client.setWebSettings(next)).then((saved) => {
                    if (saved) web.setData(saved);
                  });
                }}
              />
              {id === 'agenda' ? s('bipe_web_booking_switch') : id === 'orders' ? s('bipe_web_orders_switch') : s('bipe_web_queue_switch')}
            </label>
          )}
          {tool.state === 'setup' && (
            <TextButton disabled={action.busy} onClick={() => set('live')}>
              {s('bipe_work_tool_publish')}
            </TextButton>
          )}
          <TextButton danger disabled={action.busy} onClick={() => set('off')}>
            {s('bipe_work_tool_turn_off')}
          </TextButton>
        </div>
      )}
    </div>
  );
}

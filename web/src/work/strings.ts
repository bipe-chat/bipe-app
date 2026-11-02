// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Web-only strings of Trabalho (ADR 0226), on their way to bipe-shared strings/. Until the regenerated
// strings.ts has a key, the text below shows: the same pt-BR and English text, word for word. Replace each
// w() with s() after the regen; nothing else changes. Same rule as PENDING in ui/i18n.ts.

import { locale } from '../ui/i18n';

const PENDING = {
  bipe_web_work_phone_only: [
    'Criar a empresa, o CNPJ, o selo, os domínios, as pessoas da equipe e as cobranças Pix ficam no app do celular.',
    'Creating the company, the CNPJ, the seal, domains, team members and Pix charges stay in the phone app.',
  ],
  bipe_web_work_in_app: ['Disponível no app do celular.', 'Available in the phone app.'],
  bipe_web_queue_now_calling: ["Chamando agora", "Now calling"],
  bipe_web_queue_number: ["Nº %1$s", "No. %1$s"],
  bipe_web_queue_none_called: ["Ninguém chamado agora.", "No one called right now."],
  bipe_web_queue_called_at: ["Chamada às %1$s", "Called at %1$s"],
  bipe_web_queue_key_hint: ["Espaço ou Enter chamam o próximo.", "Space or Enter calls the next one."],
  bipe_web_queue_waiting_since: ["Desde %1$s", "Since %1$s"],
  bipe_web_queue_earlier: ["Mais cedo hoje", "Earlier today"],
  bipe_web_queue_no_service: ["Sem serviço", "No service"],
  bipe_web_queue_window_range: ["De 1 a 240 minutos.", "From 1 to 240 minutes."],
  bipe_web_orders_closed: ["Encerrados", "Closed"],
  bipe_web_orders_keep: ["Manter pedido", "Keep order"],
  bipe_web_quotes_preview: ["Como o cliente vê", "What the customer sees"],
  bipe_web_catalog_move_up: ["Mover para cima", "Move up"],
  bipe_web_catalog_move_down: ["Mover para baixo", "Move down"],
  bipe_web_catalog_photo_drop: ["Ou arraste uma foto para cá.", "Or drag a photo here."],
  bipe_web_agenda_prev_week: ["Semana anterior", "Previous week"],
  bipe_web_agenda_next_week: ["Próxima semana", "Next week"],
  bipe_web_agenda_empty_week: ["Nenhum agendamento nesta semana.", "No bookings this week."],
  bipe_web_agenda_close_day: ["Fechar dia", "Close day"],
  bipe_web_agenda_start: ["Início", "Start"],
  bipe_web_agenda_end: ["Fim", "End"],
  bipe_web_agenda_no_services: ["Adicione um serviço nos ajustes da agenda.", "Add a service in the schedule settings."],
  bipe_web_agenda_rules: ["Publicação e regras", "Publishing and rules"],
  bipe_web_agenda_services: ["Serviços", "Services"],
  bipe_web_agenda_overrides: ["Dias com horário especial", "Days with special hours"],
  bipe_web_agenda_add_override: ["Adicionar dia especial", "Add a special day"],
  bipe_web_agenda_date_from: ["De", "From"],
  bipe_web_agenda_date_to: ["Até", "To"],
  bipe_web_agenda_ranges_invalid: ["Cada faixa precisa terminar depois de começar, sem cruzar outra faixa.", "Each range must end after it starts and must not overlap another."],
  bipe_web_agenda_takes_bookings: ["Recebe agendamentos", "Takes bookings"],
  bipe_web_agenda_remove_person_body: ["Os agendamentos dessa pessoa continuam na agenda.", "This person's bookings stay in the schedule."],
  bipe_web_agenda_price_in_catalog: ["O preço e a descrição ficam no Catálogo.", "Price and description are in the Catalog."],
  bipe_web_agenda_saved: ["Salvo.", "Saved."],
  bipe_web_work_remove: ["Remover", "Remove"],
} as const;

export type PendingKey = keyof typeof PENDING;

/** The pending string of key, with %1$s, %2$s, ... replaced by args. */
export function w(key: PendingKey, ...args: string[]): string {
  const text: string = PENDING[key][locale === 'en' ? 1 : 0];
  return args.reduce((out, arg, i) => out.replaceAll(`%${i + 1}$s`, arg), text);
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec catalog-view (CatalogViews.swift): the customer's view of a business's catálogo, items grouped by category,
// and an item detail. Descriptions are plain text with no link detection (security rule 10). Prices are text.

import { useState } from 'react';
import type { CatalogItem } from '../../bipe/generated/business-api';
import { NavBar } from '../../ui/List';
import { s } from '../../ui/i18n';
import type { BusinessClient } from '../business-client';
import { priceText, Thumb } from '../Catalog';
import { PrimaryButton, SecondaryButton, useLoad } from '../common';
import { isOrderable } from './useBusinessChat';

export function CatalogView({
  api,
  business,
  canBook,
  canOrder,
  onBook,
  onOrder,
  onClose,
}: {
  api: BusinessClient;
  business: string;
  canBook: boolean;
  canOrder: boolean;
  onBook: (serviceId: string) => void;
  onOrder: (itemId: string) => void;
  onClose: () => void;
}) {
  const items = useLoad(() => api.publicCatalog(business).then((c) => c.items), [api, business]);
  const [openId, setOpenId] = useState<string>();
  const open = items.data?.find((i) => i.id === openId);
  if (open)
    return (
      <>
        <NavBar title={open.name} onBack={() => setOpenId(undefined)} />
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4">
          <Thumb url={open.photo} size={160} label={s('bipe_catalog_photo_a11y', open.name)} />
          <h3 className="text-title2 text-ink-primary">{open.name}</h3>
          <p className="text-subheadline text-ink-secondary">{facts(open)}</p>
          {open.sold_out_today && (
            <p className="text-footnote font-semibold text-trust-caution-ink">{s('bipe_catalog_sold_out_today')}</p>
          )}
          {open.description && <p className="whitespace-pre-wrap text-body text-ink-primary">{open.description}</p>}
          {canBook && open.kind === 'service' && (
            <PrimaryButton onClick={() => onBook(open.id)}>{s('bipe_agenda_book')}</PrimaryButton>
          )}
          {canOrder && isOrderable(open) && !open.sold_out_today && (
            <SecondaryButton onClick={() => onOrder(open.id)}>{s('bipe_order_add')}</SecondaryButton>
          )}
        </div>
      </>
    );
  return (
    <>
      <NavBar title={s('bipe_catalog_title')} onBack={onClose} backLabel={s('bipe_app_close')} />
      <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto p-4">
        {!items.data ? (
          items.loading ? (
            <p className="text-footnote text-ink-secondary">{s('bipe_catalog_title')}</p>
          ) : (
            <div className="flex flex-col items-center gap-3 p-6 text-center">
              <p className="text-footnote text-ink-secondary">{s('bipe_catalog_load_failed')}</p>
              <SecondaryButton onClick={() => void items.reload()}>{s('bipe_app_retry')}</SecondaryButton>
            </div>
          )
        ) : items.data.length === 0 ? (
          <p className="text-body text-ink-secondary">{s('bipe_catalog_empty')}</p>
        ) : (
          groups(items.data).map(([category, group]) => (
            <section key={category} className="flex flex-col gap-1.5">
              <h3 className="px-1 text-footnote text-ink-secondary">{category}</h3>
              <ul className="overflow-hidden rounded-xl bg-grouped-cell">
                {group.map((item) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      className="flex w-full items-center gap-3 p-3 text-left"
                      aria-label={s('bipe_catalog_item_open_a11y', item.name)}
                      onClick={() => setOpenId(item.id)}
                    >
                      <Thumb url={item.photo} size={44} label={s('bipe_catalog_photo_a11y', item.name)} />
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span className="truncate text-body text-ink-primary">{item.name}</span>
                        <span className="truncate text-footnote text-ink-secondary">{facts(item)}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))
        )}
      </div>
    </>
  );
}

function facts(item: CatalogItem): string {
  const parts = [priceText(item.price)];
  if (item.duration_minutes !== undefined) parts.push(s('bipe_agenda_duration', String(item.duration_minutes)));
  if (item.sold_out_today) parts.push(s('bipe_catalog_sold_out_today'));
  return parts.join(' · ');
}

/** In the business's order; items with no category go under bipe_catalog_uncategorised. */
function groups(items: CatalogItem[]): [string, CatalogItem[]][] {
  const by = new Map<string, CatalogItem[]>();
  for (const item of items) {
    const key = item.category?.trim() || s('bipe_catalog_uncategorised');
    by.set(key, [...(by.get(key) ?? []), item]);
  }
  return [...by];
}

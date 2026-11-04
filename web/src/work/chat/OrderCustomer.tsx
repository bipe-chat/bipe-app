// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec order-customer (OrderViews.swift): the cart from the catálogo's fixed-price products, retirada or entrega,
// pay on site, send. The address and notes go in the chat (ADR 0105). No Pix on the web (ADR 0226 point 6).

import { useState } from 'react';
import { NavBar } from '../../ui/List';
import { s } from '../../ui/i18n';
import type { BusinessClient } from '../business-client';
import { inputClass, LoadState, money, PrimaryButton, useAction, useLoad } from '../common';
import { orderErrorText, unitCents } from '../Orders';
import { isOrderable } from './useBusinessChat';

const MAX_QUANTITY = 999;

export function OrderCustomer({
  api,
  business,
  itemId,
  onClose,
}: {
  api: BusinessClient;
  business: string;
  itemId?: string;
  onClose: (message?: string) => void;
}) {
  const products = useLoad(
    () => api.publicCatalog(business).then((c) => c.items.filter((i) => isOrderable(i) && !i.sold_out_today)),
    [api, business],
  );
  const [cart, setCart] = useState<Record<string, number>>(itemId ? { [itemId]: 1 } : {});
  const [fulfilment, setFulfilment] = useState<'pickup' | 'delivery'>('pickup');
  const send = useAction();
  const items = products.data ?? [];
  const lines = items.filter((i) => (cart[i.id] ?? 0) > 0);
  const total = lines.reduce((sum, i) => sum + (unitCents(i) ?? 0) * cart[i.id], 0);
  return (
    <>
      <NavBar title={s('bipe_order_make')} onBack={() => onClose()} backLabel={s('bipe_app_cancel')} />
      {!products.data ? (
        <LoadState loading={products.loading} error={products.error} onRetry={() => void products.reload()} />
      ) : (
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
          {items.length === 0 ? (
            <p className="text-body text-ink-secondary">{s('bipe_order_empty')}</p>
          ) : (
            <ul className="overflow-hidden rounded-xl bg-grouped-cell">
              {items.map((item) => (
                <li key={item.id} className="flex items-center gap-3 p-3">
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-body text-ink-primary">{item.name}</span>
                    <span className="text-footnote text-ink-secondary">{money(unitCents(item) ?? 0)}</span>
                  </span>
                  <input
                    type="number"
                    min={0}
                    max={MAX_QUANTITY}
                    aria-label={`${s('bipe_order_quantity')}: ${item.name}`}
                    className={`${inputClass} w-20 text-right`}
                    value={cart[item.id] ?? 0}
                    onChange={(e) => {
                      const typed = Math.trunc(Number(e.target.value));
                      const quantity = Number.isFinite(typed) ? Math.min(MAX_QUANTITY, Math.max(0, typed)) : 0;
                      setCart((c) => ({ ...c, [item.id]: quantity }));
                    }}
                  />
                </li>
              ))}
            </ul>
          )}
          <fieldset className="flex flex-col gap-2">
            <legend className="pb-1 text-footnote text-ink-secondary">{s('bipe_order_fulfilment')}</legend>
            {(['pickup', 'delivery'] as const).map((f) => (
              <label key={f} className="flex items-center gap-2 text-body text-ink-primary">
                <input type="radio" name="fulfilment" checked={fulfilment === f} onChange={() => setFulfilment(f)} />
                {f === 'pickup' ? s('bipe_order_pickup') : s('bipe_order_delivery')}
              </label>
            ))}
          </fieldset>
          <div className="flex flex-col gap-1">
            <span className="text-footnote text-ink-secondary">{s('bipe_order_payment')}</span>
            <span className="text-body text-ink-primary">{s('bipe_order_pay_on_site')}</span>
          </div>
          {fulfilment === 'delivery' && (
            <p className="text-footnote text-ink-secondary">{s('bipe_order_address_in_chat')}</p>
          )}
          <div className="flex items-baseline justify-between font-bold text-ink-primary">
            <span>{s('bipe_order_total')}</span>
            <span className="tabular-nums">{money(total)}</span>
          </div>
          {send.error !== undefined && (
            <p role="alert" className="text-footnote text-trust-danger-ink">
              {orderErrorText(send.error)}
            </p>
          )}
          <PrimaryButton
            disabled={lines.length === 0 || send.busy}
            onClick={() =>
              void send
                .run(() =>
                  api.placeOrder(business, {
                    lines: lines.map((i) => ({ item_id: i.id, quantity: cart[i.id] })),
                    fulfilment,
                    payment: 'in_person',
                  }),
                )
                .then((order) => {
                  if (order) onClose(s('bipe_order_sent'));
                })
            }
          >
            {s('bipe_order_send')}
          </PrimaryButton>
        </div>
      )}
    </>
  );
}

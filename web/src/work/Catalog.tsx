// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Catálogo for the owner on the web (ADR 0226, bipe-chat/bipe#980): spec catalog-owner on the left and spec
// catalog-item-edit on the right, as CatalogOwnerView and CatalogItemEditView on iOS (CatalogViews.swift).
// Same routes, same rules: the list is the owner's order with hidden items, one tap marks an item sold out
// today, reorder saves every ID at once, and a photo is scaled down to a JPEG under the profile photo limit
// in the browser before it goes up (bipe-api accepts only image/jpeg).

import { useEffect, useRef, useState, type DragEvent } from 'react';
import type { AgendaState, CatalogItem, CatalogItemInput, CatalogPrice } from '../bipe/generated/business-api';
import { profiles } from '../bipe/generated/registries.json';
import type { StringKey } from '../bipe/generated/strings';
import { caretLeft, caretRight, image, plus, trash } from '../phosphor/icons';
import { Alert } from '../ui/Alert';
import { s } from '../ui/i18n';
import { Icon } from '../ui/Icon';
import { NavBar, Row, Section } from '../ui/List';
import { Spinner } from '../ui/Spinner';
import type { BusinessClient } from './business-client';
import {
  Field,
  PrimaryButton,
  SecondaryButton,
  SplitPane,
  TextButton,
  inputClass,
  useAction,
  useLoad,
  type WorkScreenProps,
} from './common';

type Kind = CatalogItem['kind'];
type PriceMode = CatalogPrice['mode'];

const KIND_TEXT: Record<Kind, StringKey> = {
  service: 'bipe_catalog_kind_service',
  product: 'bipe_catalog_kind_product',
};

const PRICE_MODE_TEXT: Record<PriceMode, StringKey> = {
  fixed: 'bipe_catalog_price_fixed',
  from: 'bipe_catalog_price_from_option',
  on_request: 'bipe_catalog_on_request_option',
};

// The bipe-api limits (src/catalog.ts CatalogItemBody), the same as the phone's form.
const NAME_LIMIT = 120;
const DESCRIPTION_LIMIT = 500;
const CATEGORY_LIMIT = 80;
const MINUTES_LIMIT = 1440;

/** The item that the editor shows; no item adds a new one. key opens a fresh form each time. */
type Editing = { item?: CatalogItem; key: number };

export function CatalogScreen({ client, onBack }: WorkScreenProps) {
  const load = useLoad(() => client.catalog(), [client]);
  // The Agenda, when it is on: a service then edits its duration, buffers and people. A failed read is off.
  const agenda = useLoad(() => client.agenda().catch(() => undefined), [client]);
  const write = useAction();
  const [editing, setEditing] = useState<Editing>();
  // Spec state reordering: the local order while it is set; done saves it.
  const [order, setOrder] = useState<CatalogItem[]>();
  const [removing, setRemoving] = useState<CatalogItem>();
  const [notice, setNotice] = useState('');
  const dragFrom = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(''), 2500);
    return () => clearTimeout(timer);
  }, [notice]);

  const items = load.data?.items ?? [];
  const shown = order ?? items;
  const nextSort = items.reduce((max, i) => Math.max(max, i.sort), -1) + 1;

  const open = (item?: CatalogItem) => setEditing({ item, key: Date.now() });

  /** The editor closed: the text is the result to show. Nothing to show means Cancel, so no reload. */
  const closeEditor = (message?: string) => {
    setEditing(undefined);
    if (message) {
      setNotice(message);
      void load.reload();
    }
  };

  // Action toggleSoldOut: PUT the item with sold_out_today flipped.
  const toggleSoldOut = async (item: CatalogItem) => {
    const saved = await write.run(() =>
      client.replaceItem(item.id, { ...inputOf(item), sold_out_today: !item.sold_out_today }),
    );
    if (saved) load.setData((c) => c && { items: c.items.map((i) => (i.id === saved.id ? saved : i)) });
    else setNotice(s('bipe_catalog_save_failed'));
  };

  // Action move: local only until done.
  const move = (from: number, to: number) =>
    setOrder((o) => {
      if (!o || from === to || to < 0 || to >= o.length) return o;
      const next = [...o];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      return next;
    });

  // Action move, done: PUT /v1/me/business/catalog/order with every ID in the new order.
  const saveOrder = async () => {
    if (!order) return;
    const saved = await write.run(() => client.reorderCatalog(order.map((i) => i.id)));
    if (saved) {
      load.setData(saved);
      setOrder(undefined);
    } else {
      setNotice(s('bipe_catalog_save_failed'));
    }
  };

  // Action remove, after bipe_catalog_remove: DELETE archives the item.
  const remove = async (item: CatalogItem) => {
    const done = await write.run(async () => {
      await client.archiveItem(item.id);
      return true;
    });
    setRemoving(undefined);
    if (!done) {
      setNotice(s('bipe_catalog_save_failed'));
      return;
    }
    load.setData((c) => c && { items: c.items.filter((i) => i.id !== item.id) });
    if (editing?.item?.id === item.id) setEditing(undefined);
    setNotice(s('bipe_catalog_removed'));
  };

  const onDragStart = (index: number, e: DragEvent) => {
    dragFrom.current = index;
    e.dataTransfer.effectAllowed = 'move';
    // Firefox starts a drag only with data set.
    e.dataTransfer.setData('text/plain', shown[index]?.id ?? '');
  };
  const onDragOver = (index: number, e: DragEvent) => {
    const from = dragFrom.current;
    if (from === undefined) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (from !== index) {
      move(from, index);
      dragFrom.current = index;
    }
  };

  let list;
  if (!load.data) {
    list = load.loading ? (
      <div className="grid flex-1 place-items-center p-8">
        <Spinner />
      </div>
    ) : (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
        <p role="alert" className="text-body text-ink-secondary">
          {s('bipe_catalog_load_failed')}
        </p>
        <SecondaryButton onClick={load.reload}>{s('bipe_app_retry')}</SecondaryButton>
      </div>
    );
  } else if (items.length === 0) {
    list = (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8 text-center">
        <p className="text-body text-ink-secondary">{s('bipe_catalog_owner_empty')}</p>
        <PrimaryButton onClick={() => open()}>{s('bipe_catalog_add_item')}</PrimaryButton>
      </div>
    );
  } else {
    list = (
      <div className="flex flex-col gap-3 px-4 pb-8 pt-2">
        <div className="flex items-center justify-between gap-2">
          {order ? (
            <>
              <TextButton onClick={() => setOrder(undefined)} disabled={write.busy}>
                {s('bipe_app_cancel')}
              </TextButton>
              <TextButton onClick={saveOrder} disabled={write.busy}>
                {s('bipe_app_save')}
              </TextButton>
            </>
          ) : (
            <>
              <button
                type="button"
                className="flex min-h-11 items-center gap-1.5 px-2 text-body text-accent-ink"
                onClick={() => open()}
              >
                <Icon path={plus} size={20} />
                {s('bipe_catalog_add_item')}
              </button>
              {items.length > 1 && (
                <TextButton
                  onClick={() => {
                    setEditing(undefined);
                    setOrder(items);
                  }}
                >
                  {s('bipe_catalog_reorder')}
                </TextButton>
              )}
            </>
          )}
        </div>
        <Section>
          {shown.map((item, index) =>
            order ? (
              <Row key={item.id} leading={<Thumb url={item.photo} size={44} />}>
                <div
                  draggable
                  className="flex min-w-0 flex-1 cursor-grab items-center gap-2 py-1"
                  onDragStart={(e) => onDragStart(index, e)}
                  onDragOver={(e) => onDragOver(index, e)}
                  onDrop={(e) => e.preventDefault()}
                  onDragEnd={() => (dragFrom.current = undefined)}
                >
                  <ItemText item={item} />
                  <button
                    type="button"
                    className="grid size-11 place-items-center text-glyph-primary disabled:opacity-30"
                    aria-label={s('bipe_web_catalog_move_up')}
                    disabled={index === 0}
                    onClick={() => move(index, index - 1)}
                  >
                    <Icon path={caretLeft} size={18} className="rotate-90" />
                  </button>
                  <button
                    type="button"
                    className="grid size-11 place-items-center text-glyph-primary disabled:opacity-30"
                    aria-label={s('bipe_web_catalog_move_down')}
                    disabled={index === shown.length - 1}
                    onClick={() => move(index, index + 1)}
                  >
                    <Icon path={caretRight} size={18} className="rotate-90" />
                  </button>
                </div>
              </Row>
            ) : (
              <Row key={item.id} leading={<Thumb url={item.photo} size={44} />}>
                <div className="flex min-w-0 flex-1 flex-col items-start">
                  <button
                    type="button"
                    className="flex w-full min-w-0 py-1 text-left"
                    aria-label={s('bipe_catalog_item_open_a11y', item.name)}
                    aria-current={editing?.item?.id === item.id ? 'true' : undefined}
                    onClick={() => open(item)}
                  >
                    <ItemText item={item} />
                  </button>
                  <button
                    type="button"
                    className="min-h-8 text-footnote text-accent-ink disabled:opacity-50"
                    disabled={write.busy}
                    onClick={() => void toggleSoldOut(item)}
                  >
                    {s(item.sold_out_today ? 'bipe_catalog_mark_available' : 'bipe_catalog_mark_sold_out')}
                  </button>
                </div>
                <button
                  type="button"
                  className="grid size-11 shrink-0 place-items-center text-trust-danger-ink disabled:opacity-50"
                  aria-label={s('bipe_catalog_remove')}
                  disabled={write.busy}
                  onClick={() => setRemoving(item)}
                >
                  <Icon path={trash} size={20} />
                </button>
              </Row>
            ),
          )}
        </Section>
      </div>
    );
  }

  const detail = editing ? (
    agenda.loading ? (
      <div className="grid flex-1 place-items-center p-8">
        <Spinner />
      </div>
    ) : (
      <ItemEditor
        key={editing.key}
        client={client}
        item={editing.item}
        nextSort={nextSort}
        agenda={agenda.data}
        onClose={closeEditor}
        onRemove={() => editing.item && setRemoving(editing.item)}
      />
    )
  ) : undefined;

  return (
    <div className="relative flex min-h-0 flex-1 flex-col bg-grouped-screen">
      <NavBar title={s('bipe_catalog_title')} onBack={onBack} />
      <SplitPane list={list} detail={detail} />
      {removing && (
        <Alert
          title={s('bipe_catalog_remove')}
          action={s('bipe_catalog_remove')}
          busy={write.busy}
          onCancel={() => setRemoving(undefined)}
          onAction={() => void remove(removing)}
        >
          {s('bipe_catalog_remove_body')}
        </Alert>
      )}
      {notice && (
        <p
          role="status"
          className="absolute inset-x-0 bottom-6 mx-auto w-max max-w-[90%] rounded-full bg-surface-raised px-4 py-2 text-center text-subheadline text-ink-primary shadow"
        >
          {notice}
        </p>
      )}
    </div>
  );
}

/** Name, kind and price, and the sold-out and hidden badges (CatalogItemRow with showsHidden on iOS). */
function ItemText({ item }: { item: CatalogItem }) {
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
      <span className="truncate text-body text-ink-primary">{item.name}</span>
      <span className="truncate text-footnote text-ink-secondary">
        {s(KIND_TEXT[item.kind])} · {priceText(item.price)}
      </span>
      {(item.sold_out_today || !item.active) && (
        <span className="flex gap-1.5">
          {item.sold_out_today && <Badge text={s('bipe_catalog_sold_out_today')} className="text-trust-caution-ink" />}
          {!item.active && <Badge text={s('bipe_catalog_hidden')} className="text-ink-secondary" />}
        </span>
      )}
    </span>
  );
}

function Badge({ text, className }: { text: string; className: string }) {
  return (
    <span className={`rounded-full border border-current px-2 text-caption font-semibold ${className}`}>{text}</span>
  );
}

/**
 * The item photo, or the image glyph when there is none or it does not load. bipe-api serves the photo of an
 * active item only (the public route), so a hidden item shows the glyph.
 */
function Thumb({ url, size, label }: { url?: string; size: number; label?: string }) {
  const [failed, setFailed] = useState<string>();
  if (url && url !== failed)
    return (
      <img
        src={url}
        alt={label ?? ''}
        width={size}
        height={size}
        className="shrink-0 rounded-lg bg-surface-sunken object-cover"
        style={{ width: size, height: size }}
        onError={() => setFailed(url)}
      />
    );
  return (
    <span
      className="grid shrink-0 place-items-center rounded-lg bg-surface-sunken text-glyph-secondary"
      style={{ width: size, height: size }}
      role={label ? 'img' : undefined}
      aria-label={label}
    >
      <Icon path={image} size={Math.round(size / 2)} />
    </span>
  );
}

/** A photo picked on this page, already a JPEG; url is its preview. */
type NewPhoto = { blob: Blob; url: string };

/**
 * Spec catalog-item-edit: one form for a product or a service. A service also has its duration, buffers and
 * the people who do it when the Agenda is on. The photo goes up after the item exists, as on the phone.
 */
function ItemEditor({
  client,
  item,
  nextSort,
  agenda,
  onClose,
  onRemove,
}: {
  client: BusinessClient;
  item?: CatalogItem;
  nextSort: number;
  agenda?: AgendaState;
  onClose: (message?: string) => void;
  onRemove: () => void;
}) {
  const [kind, setKind] = useState<Kind>(item?.kind ?? 'service');
  const [name, setName] = useState(item?.name ?? '');
  const [priceMode, setPriceMode] = useState<PriceMode>(item?.price.mode ?? 'fixed');
  const [amount, setAmount] = useState(() => {
    const cents = item && item.price.mode !== 'on_request' ? item.price.amount_cents : undefined;
    return cents === undefined ? '' : `${Math.floor(cents / 100)},${String(cents % 100).padStart(2, '0')}`;
  });
  const [description, setDescription] = useState(item?.description ?? '');
  const [category, setCategory] = useState(item?.category ?? '');
  const [active, setActive] = useState(item?.active ?? true);
  const [duration, setDuration] = useState(item?.duration_minutes?.toString() ?? '');
  const [bufferBefore, setBufferBefore] = useState(item?.buffer_before_minutes?.toString() ?? '');
  const [bufferAfter, setBufferAfter] = useState(item?.buffer_after_minutes?.toString() ?? '');
  // A new service starts with the first person, as on the phone.
  const [agendaIds, setAgendaIds] = useState<string[]>(() =>
    item ? (item.agenda_ids ?? []) : agenda?.agendas[0] ? [agenda.agendas[0].id] : [],
  );
  const [newPhoto, setNewPhoto] = useState<NewPhoto>();
  const [removesPhoto, setRemovesPhoto] = useState(false);
  const [converting, setConverting] = useState(false);
  const [dropping, setDropping] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const fileInput = useRef<HTMLInputElement>(null);

  const schedules = kind === 'service' && agenda !== undefined;
  const cents = centsFrom(amount);
  const minutes = (text: string, min: number) => {
    const n = text === '' ? 0 : Number(text);
    return Number.isInteger(n) && n >= min && n <= MINUTES_LIMIT;
  };
  const canSave =
    !saving &&
    !converting &&
    name.trim() !== '' &&
    (priceMode === 'on_request' || cents !== undefined) &&
    (!schedules ||
      (duration !== '' && minutes(duration, 1) && minutes(bufferBefore, 0) && minutes(bufferAfter, 0) && agendaIds.length > 0));

  const photo = newPhoto?.url ?? (removesPhoto ? undefined : item?.photo);

  // Action pickPhoto: scaled down to a JPEG under the profile photo limit.
  const pickPhoto = async (file: File | undefined) => {
    if (!file) return;
    setConverting(true);
    const jpeg = await jpegPhoto(file);
    setConverting(false);
    if (!jpeg) {
      setError(s('bipe_catalog_photo_failed'));
      return;
    }
    setError(undefined);
    setNewPhoto(jpeg);
    setRemovesPhoto(false);
  };

  const input = (): CatalogItemInput => {
    const price: CatalogPrice =
      priceMode === 'on_request'
        ? { mode: 'on_request' }
        : { mode: priceMode, text: moneyText(cents ?? 0), amount_cents: cents ?? 0 };
    const base: CatalogItemInput = {
      kind,
      name: name.trim(),
      price,
      description,
      category: category.trim(),
      active,
      sort: item?.sort ?? nextSort,
      sold_out_today: item?.sold_out_today ?? false,
    };
    // Products never send scheduling fields; a service keeps its fields when the Agenda is off.
    if (kind !== 'service') return base;
    if (!schedules)
      return {
        ...base,
        duration_minutes: item?.duration_minutes,
        buffer_before_minutes: item?.buffer_before_minutes,
        buffer_after_minutes: item?.buffer_after_minutes,
        agenda_ids: item?.agenda_ids,
      };
    return {
      ...base,
      duration_minutes: Number(duration),
      buffer_before_minutes: Number(bufferBefore || '0'),
      buffer_after_minutes: Number(bufferAfter || '0'),
      // In the Agenda's order.
      agenda_ids: (agenda?.agendas ?? []).map((a) => a.id).filter((id) => agendaIds.includes(id)),
    };
  };

  // Action save: POST or PUT, then the photo once the item exists.
  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    setError(undefined);
    let saved: CatalogItem;
    try {
      saved = item ? await client.replaceItem(item.id, input()) : await client.createItem(input());
    } catch {
      setSaving(false);
      setError(s('bipe_catalog_save_failed'));
      return;
    }
    try {
      if (newPhoto) await client.setItemPhoto(saved.id, newPhoto.blob);
      else if (removesPhoto) await client.deleteItemPhoto(saved.id);
    } catch {
      onClose(s('bipe_catalog_photo_failed'));
      return;
    }
    onClose(s('bipe_catalog_saved'));
  };

  const numberField = (label: StringKey, value: string, set: (v: string) => void) => (
    <Field label={s(label)}>
      <input
        className={inputClass}
        inputMode="numeric"
        value={value}
        onChange={(e) => set(e.target.value.replace(/\D/g, '').slice(0, 4))}
      />
    </Field>
  );

  return (
    <form
      className="flex min-h-0 flex-1 flex-col"
      onSubmit={(e) => {
        e.preventDefault();
        void save();
      }}
    >
      <header className="grid h-[52px] shrink-0 grid-cols-[1fr_auto_1fr] items-center px-2">
        <div>
          <TextButton onClick={() => onClose()}>{s('bipe_app_cancel')}</TextButton>
        </div>
        <h2 className="truncate text-center text-headline text-ink-primary">
          {s(item ? 'bipe_catalog_edit_item' : 'bipe_catalog_add_item')}
        </h2>
        <div className="flex justify-end">
          {saving ? (
            <span className="grid size-11 place-items-center">
              <Spinner />
            </span>
          ) : (
            <button
              type="submit"
              className="min-h-11 px-2 text-headline text-accent-ink disabled:opacity-50"
              disabled={!canSave}
            >
              {s('bipe_catalog_save')}
            </button>
          )}
        </div>
      </header>
      <div className="flex flex-col gap-6 overflow-y-auto px-4 pb-8 pt-2">
        <Section header={s('bipe_catalog_photo')}>
          <li
            className={`flex items-center gap-4 p-4 ${dropping ? 'outline-2 -outline-offset-2 outline-accent-fill' : ''}`}
            onDragOver={(e) => {
              if (!e.dataTransfer.types.includes('Files')) return;
              e.preventDefault();
              e.dataTransfer.dropEffect = 'copy';
              setDropping(true);
            }}
            onDragLeave={() => setDropping(false)}
            onDrop={(e) => {
              if (!e.dataTransfer.types.includes('Files')) return;
              e.preventDefault();
              setDropping(false);
              void pickPhoto(e.dataTransfer.files[0]);
            }}
          >
            <Thumb url={photo} size={72} label={s('bipe_catalog_photo')} />
            <div className="flex min-w-0 flex-1 flex-col items-start gap-1">
              <input
                ref={fileInput}
                type="file"
                accept="image/*"
                hidden
                onChange={(e) => {
                  void pickPhoto(e.target.files?.[0]);
                  // The same file can be picked again after a failure.
                  e.target.value = '';
                }}
              />
              <TextButton onClick={() => fileInput.current?.click()} disabled={converting || saving}>
                {s('bipe_catalog_add_photo')}
              </TextButton>
              {photo !== undefined && (
                <TextButton
                  danger
                  disabled={converting || saving}
                  onClick={() => {
                    setNewPhoto(undefined);
                    setRemovesPhoto(item?.photo !== undefined);
                  }}
                >
                  {s('bipe_catalog_remove_photo')}
                </TextButton>
              )}
              <span className="px-2 text-caption text-ink-secondary">{s('bipe_web_catalog_photo_drop')}</span>
            </div>
            {converting && <Spinner />}
          </li>
        </Section>

        <div className="flex flex-col gap-4">
          <Field label={s('bipe_catalog_item_name')}>
            <input
              className={inputClass}
              value={name}
              maxLength={NAME_LIMIT}
              required
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <Field label={s('bipe_catalog_item_kind')}>
            <select className={inputClass} value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
              {(['service', 'product'] as const).map((k) => (
                <option key={k} value={k}>
                  {s(KIND_TEXT[k])}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <div className="flex flex-col gap-4">
          <Field label={s('bipe_catalog_price')}>
            <select
              className={inputClass}
              value={priceMode}
              onChange={(e) => setPriceMode(e.target.value as PriceMode)}
            >
              {(['fixed', 'from', 'on_request'] as const).map((m) => (
                <option key={m} value={m}>
                  {s(PRICE_MODE_TEXT[m])}
                </option>
              ))}
            </select>
          </Field>
          {priceMode !== 'on_request' && (
            <Field
              label={s('bipe_catalog_amount')}
              hint={cents === undefined ? undefined : priceText({ mode: priceMode, text: moneyText(cents) })}
            >
              <input
                className={inputClass}
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </Field>
          )}
        </div>

        <div className="flex flex-col gap-4">
          <Field label={s('bipe_catalog_description')}>
            <textarea
              className={`${inputClass} resize-y py-2`}
              rows={4}
              value={description}
              maxLength={DESCRIPTION_LIMIT}
              onChange={(e) => setDescription(e.target.value)}
            />
          </Field>
          <Field label={s('bipe_catalog_category')}>
            <input
              className={inputClass}
              value={category}
              maxLength={CATEGORY_LIMIT}
              onChange={(e) => setCategory(e.target.value)}
            />
          </Field>
          <Section>
            <Row>
              <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                <span className="min-w-0 flex-1 text-body text-ink-primary">{s('bipe_catalog_active')}</span>
                <input
                  type="checkbox"
                  role="switch"
                  className="size-5 accent-[var(--color-accent-fill)]"
                  checked={active}
                  onChange={(e) => setActive(e.target.checked)}
                />
              </label>
            </Row>
          </Section>
        </div>

        {schedules && agenda && (
          <>
            <div className="flex flex-col gap-4">
              {numberField('bipe_agenda_service_duration', duration, setDuration)}
              {numberField('bipe_agenda_buffer_before', bufferBefore, setBufferBefore)}
              {numberField('bipe_agenda_buffer_after', bufferAfter, setBufferAfter)}
            </div>
            <Section header={s('bipe_agenda_setup_people')}>
              {agenda.agendas.map((person) => (
                <Row key={person.id}>
                  <label className="flex min-w-0 flex-1 items-center gap-3 py-1">
                    <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{person.name}</span>
                    <input
                      type="checkbox"
                      role="switch"
                      className="size-5 accent-[var(--color-accent-fill)]"
                      checked={agendaIds.includes(person.id)}
                      onChange={(e) =>
                        setAgendaIds((ids) =>
                          e.target.checked ? [...ids, person.id] : ids.filter((id) => id !== person.id),
                        )
                      }
                    />
                  </label>
                </Row>
              ))}
            </Section>
          </>
        )}

        {error && (
          <p role="alert" className="text-footnote text-trust-danger-ink">
            {error}
          </p>
        )}

        {item && (
          <div>
            <TextButton danger onClick={onRemove} disabled={saving}>
              {s('bipe_catalog_remove')}
            </TextButton>
          </div>
        )}
      </div>
    </form>
  );
}

/** The input of a saved item, for a PUT that changes one field (toggleSoldOut). */
function inputOf(item: CatalogItem): CatalogItemInput {
  return {
    kind: item.kind,
    name: item.name,
    price: item.price,
    description: item.description,
    category: item.category,
    active: item.active,
    sort: item.sort,
    sold_out_today: item.sold_out_today,
    duration_minutes: item.duration_minutes,
    buffer_before_minutes: item.buffer_before_minutes,
    buffer_after_minutes: item.buffer_after_minutes,
    agenda_ids: item.agenda_ids,
  };
}

/** Spec priceText: the business's text, bipe_catalog_price_from with it, or bipe_catalog_price_on_request. */
function priceText(price: CatalogPrice): string {
  if (price.mode === 'on_request') return s('bipe_catalog_price_on_request');
  return price.mode === 'from' ? s('bipe_catalog_price_from', price.text) : price.text;
}

/**
 * R$ 1.234,56, byte for byte as BipeMoney.text on iOS (a plain space, not the no-break space of
 * Intl.NumberFormat), so that the price text is the same from every client.
 */
function moneyText(cents: number): string {
  const value = Math.abs(cents);
  const reais = String(Math.floor(value / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${cents < 0 ? '-' : ''}R$ ${reais},${String(value % 100).padStart(2, '0')}`;
}

/**
 * Centavos from a typed amount in reais, as BipeMoney.cents on iOS: 40, 40,5, 1.234,56 or 40.50. With a comma,
 * dots group thousands. Rounds half up at the third decimal. undefined when it is not a number.
 */
function centsFrom(typed: string): number | undefined {
  let text = typed.replaceAll('R$', '').replace(/\s/g, '');
  if (text === '') return undefined;
  if (text.includes(',')) text = text.replaceAll('.', '').replace(',', '.');
  const match = /^(\d*)(?:\.(\d*))?$/.exec(text);
  if (!match || (match[1] === '' && !match[2])) return undefined;
  const fraction = (match[2] ?? '').padEnd(3, '0');
  const cents = Number(match[1] || '0') * 100 + Number(fraction.slice(0, 2)) + (Number(fraction[2]) >= 5 ? 1 : 0);
  return Number.isSafeInteger(cents) ? cents : undefined;
}

// BipeProfilePhoto on iOS: the JPEG qualities to try, best first, until the file fits.
const QUALITIES = [0.85, 0.75, 0.65, 0.55, 0.45, 0.35, 0.25];

/**
 * Any image the browser can decode, redrawn as an sRGB JPEG at most profiles.photoMaxPixels on each side and
 * profiles.photoMaxBytes (bipe-api stripJpeg checks both). Drawing again drops all metadata. undefined when
 * the file is not an image or does not fit even at the lowest quality.
 */
async function jpegPhoto(file: Blob): Promise<NewPhoto | undefined> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return undefined;
  }
  try {
    const { width, height } = bitmap;
    if (width === 0 || height === 0) return undefined;
    const ratio = Math.min(1, profiles.photoMaxPixels / Math.max(width, height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.floor(width * ratio));
    canvas.height = Math.max(1, Math.floor(height * ratio));
    const context = canvas.getContext('2d');
    if (!context) return undefined;
    // JPEG has no alpha: paint transparent areas white, not black (as on iOS). Not a UI colour.
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.imageSmoothingQuality = 'high';
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    for (const quality of QUALITIES) {
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
      if (blob && blob.size <= profiles.photoMaxBytes) return { blob, url: canvas.toDataURL('image/jpeg', quality) };
    }
    return undefined;
  } finally {
    bitmap.close();
  }
}

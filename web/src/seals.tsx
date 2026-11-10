// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Company and person seals on the web (bipe-chat/bipe#1457; specs company-seal, person-seal, person-profile notes),
// the rules of BipeCompanySeals.swift. The envelope comes from GET /v1/badges/{userId}; bipe-core checks it on this
// device against the issuer registry and the account's cross-signing master key (UserIdentity.masterKey). The HTTP
// status alone never shows a seal, and any failure shows none (fail closed, security rule 2). A notice (revoked,
// suspended) shows only where this browser saw the seal before.

import { useEffect, useSyncExternalStore } from 'react';
import type { ClientLike as Client } from './generated/matrix_sdk_ffi';
import { badge, type BadgeGoneReason } from './api';
import { formatCnpj, isBipeUserID } from './bipe/generated/lib/registries.mjs';
import { cvmLines } from './bipe/generated/lib/cvm.mjs';
import { evaluateTrustFacts } from './bipe/generated/lib/trust.mjs';
import { bipeServers, homoglyphs, profileFacts, trust } from './bipe/generated/registries.json';
import type { StringKey } from './bipe/generated/strings';
import { useClient } from './media';
import { sealCheck, sealCheckFill, sealWarning, warningCircle } from './phosphor/icons';
import { coreReady, currentList, onListsChange } from './signed-lists';
import {
  badgeClass,
  isForeign,
  ListType,
  personProofsOf,
  releaseTrust,
  verifyBadge,
  type PersonProofShown,
  type ShownBadge,
} from './seal-rules';
import { Sheet } from './MessageSheets';
import { Icon } from './ui/Icon';
import { locale, s } from './ui/i18n';

const reg = { bipeServers, homoglyphs, profileFacts };
const TTL_MS = 10 * 60 * 1000;
const ERROR_TTL_MS = 2 * 60 * 1000;

export type CompanySeal =
  | { state: 'none' }
  | { state: 'verified'; badge: ShownBadge }
  | { state: 'revoked'; reason: BadgeGoneReason }
  | { state: 'suspended' };

export type PersonSeal =
  | { state: 'none' }
  | { state: 'verified'; proof: PersonProofShown; issuerName?: string }
  | { state: 'suspended' };

type Entry = { company: CompanySeal; person: PersonSeal; expiry: number };

const NONE: Entry = { company: { state: 'none' }, person: { state: 'none' }, expiry: 0 };

/** The seals of one account's session. One store per client. */
class SealStore {
  private entries = new Map<string, Entry>();
  private inFlight = new Set<string>();
  private listeners = new Set<() => void>();
  private seen: Set<string>;
  private readonly seenKey: string;

  constructor(private readonly client: Client) {
    let me = '';
    try {
      me = client.userId();
    } catch {
      // No user ID: the seen set is not kept.
    }
    this.seenKey = `bipe.seals.seen.${me}`;
    this.seen = new Set(this.readSeen());
    // A new issuer registry can end or add seals: check every account again.
    onListsChange(() => {
      for (const entry of this.entries.values()) entry.expiry = 0;
      this.emit();
    });
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** The cached entry. Queues a fetch when it is not fresh. A badge past its exp shows no seal while the fetch runs. */
  get(userId: string): Entry {
    if (!isBipeUserID(userId, reg)) return NONE;
    const entry = this.entries.get(userId);
    if (!entry || entry.expiry <= Date.now()) this.fetch(userId);
    return entry ?? NONE;
  }

  private emit() {
    for (const listener of this.listeners) listener();
  }

  private readSeen(): string[] {
    try {
      const value: unknown = JSON.parse(localStorage.getItem(this.seenKey) ?? '[]');
      return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
    } catch {
      return [];
    }
  }

  private setSeen(key: string, isSeen: boolean) {
    const changed = isSeen ? !this.seen.has(key) : this.seen.has(key);
    if (!changed) return;
    if (isSeen) this.seen.add(key);
    else this.seen.delete(key);
    try {
      localStorage.setItem(this.seenKey, JSON.stringify([...this.seen]));
    } catch {
      // The notice then shows only for this page.
    }
  }

  private set(userId: string, company: CompanySeal, person: PersonSeal, ttl = TTL_MS, until?: number) {
    this.entries.set(userId, { company, person, expiry: Math.min(Date.now() + ttl, until ?? Infinity) });
    this.emit();
  }

  private fetch(userId: string) {
    if (this.inFlight.has(userId)) return;
    this.inFlight.add(userId);
    void this.load(userId).finally(() => this.inFlight.delete(userId));
  }

  /** Never keeps a seal without a fresh answer. A notice stays. */
  private failure(userId: string) {
    const old = this.entries.get(userId);
    const company: CompanySeal = old?.company.state === 'verified' ? { state: 'none' } : (old?.company ?? { state: 'none' });
    const person: PersonSeal = old?.person.state === 'verified' ? { state: 'none' } : (old?.person ?? { state: 'none' });
    this.set(userId, company, person, ERROR_TTL_MS);
  }

  private async load(userId: string) {
    const personKey = `person:${userId}`;
    const wasSeen = this.seen.has(userId);
    const wasPersonSeen = this.seen.has(personKey);
    const lookup = await badge(userId);
    if (!lookup) return this.failure(userId);
    if ('none' in lookup) {
      this.setSeen(userId, false);
      this.setSeen(personKey, false);
      return this.set(userId, { state: 'none' }, { state: 'none' });
    }
    if ('gone' in lookup) {
      this.setSeen(personKey, false);
      return this.set(userId, wasSeen ? { state: 'revoked', reason: lookup.gone } : { state: 'none' }, { state: 'none' });
    }
    let masterKey: string | undefined;
    try {
      await coreReady();
      masterKey = (await this.client.encryption().userIdentity(userId, true))?.masterKey();
    } catch {
      return this.failure(userId);
    }
    const issuers = currentList(ListType.Issuers);
    const check = verifyBadge(lookup.envelope, userId, masterKey, releaseTrust(issuers), BigInt(Math.floor(Date.now() / 1000)));
    if (!check.badge) {
      return this.set(userId, wasSeen ? { state: 'suspended' } : { state: 'none' }, wasPersonSeen ? { state: 'suspended' } : { state: 'none' });
    }
    const { claims } = check.badge;
    const until = Number(claims.exp) * 1000;
    const kind = badgeClass(claims.level);
    if (kind === 'org') {
      this.setSeen(userId, true);
      return this.set(userId, { state: 'verified', badge: check.badge }, { state: 'none' }, TTL_MS, until);
    }
    if (kind === 'person') {
      // The seal shows only when identityVerified holds (registries/trust-facts.json, BipeTrustSources.profileInput).
      const facts = evaluateTrustFacts(
        masterKey
          ? {
              accountId: userId,
              deviceKey: masterKey,
              lifecycleKnown: true,
              badgeStatus: 'valid',
              badgeAccountId: userId,
              badgeDeviceKey: masterKey,
              badgeName: claims.name,
              badgeIssuedAt: 2,
            }
          : { accountId: userId, lifecycleKnown: false },
        trust,
      ) as { identity?: string };
      // The strongest proof only (ADR 0275 point 15): the server answers the strongest, the mark comes from the badge.
      const proof = personProofsOf([claims], issuers?.payloadJson, locale)[0];
      if (facts.identity !== 'verified' || !proof) return this.set(userId, { state: 'none' }, { state: 'none' });
      this.setSeen(personKey, true);
      return this.set(userId, { state: 'none' }, { state: 'verified', proof, issuerName: check.badge.issuerName }, TTL_MS, until);
    }
    // institution and brand-mark have their own places, not a seal.
    this.set(userId, { state: 'none' }, { state: 'none' });
  }
}

const stores = new WeakMap<Client, SealStore>();

function storeFor(client: Client): SealStore {
  let store = stores.get(client);
  if (!store) {
    store = new SealStore(client);
    stores.set(client, store);
  }
  return store;
}

/** The seals of an account, fetched and verified on first use. A badge past its exp is no seal. */
export function useSeals(userId: string | undefined): { company: CompanySeal; person: PersonSeal } {
  const store = storeFor(useClient());
  const entry = useSyncExternalStore(store.subscribe, () => (userId ? store.get(userId) : NONE));
  const now = Date.now();
  const expired = (exp: bigint) => Number(exp) * 1000 <= now;
  return {
    company: entry.company.state === 'verified' && expired(entry.company.badge.claims.exp) ? { state: 'none' } : entry.company,
    person: entry.person.state === 'verified' && expired(entry.person.proof.claims.exp) ? { state: 'none' } : entry.person,
  };
}

type Mark = { icon: string; label: string; caution: boolean; opens: boolean };

/** The compact mark next to a name (BipeSealMark): person first, then company. A revoked seal has no mark. */
function markOf(company: CompanySeal, person: PersonSeal): Mark | undefined {
  if (person.state === 'verified') {
    const { proof } = person;
    return { icon: proof.fill ? sealCheckFill : sealCheck, label: s('bipe_badge_verified_via', proof.name), caution: false, opens: true };
  }
  if (person.state === 'suspended') return { icon: sealWarning, label: s('bipe_badge_suspended'), caution: true, opens: false };
  if (company.state === 'verified') return { icon: sealCheckFill, label: s('bipe_badge_company'), caution: false, opens: true };
  if (company.state === 'suspended') return { icon: sealWarning, label: s('bipe_badge_company_suspended'), caution: true, opens: false };
  return undefined;
}

/**
 * The seal next to a name. With onOpen it is a button that opens the seal sheet (person-profile); without, a mark
 * only (chat-list, the chat header, a sender line). An amber seal never opens a sheet.
 */
export function SealMark({ userId, size = 18, onOpen }: { userId: string | undefined; size?: number; onOpen?: () => void }) {
  const { company, person } = useSeals(userId);
  const mark = markOf(company, person);
  if (!mark) return null;
  const className = `shrink-0 ${mark.caution ? 'text-trust-caution-ink' : 'text-trust-verified-ink'}`;
  if (onOpen && mark.opens) {
    return (
      <button type="button" className={`grid place-items-center ${className}`} aria-label={mark.label} onClick={onOpen}>
        <Icon path={mark.icon} size={size} />
      </button>
    );
  }
  return (
    <span className={className} role="img" aria-label={mark.label}>
      <Icon path={mark.icon} size={size} />
    </span>
  );
}

const monthYear = new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' });
const longDate = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', year: 'numeric' });
const regionNames = (() => {
  try {
    return new Intl.DisplayNames([locale], { type: 'region' });
  } catch {
    return undefined;
  }
})();

/** The country in the page's language, the code itself when the browser has no name. */
function countryName(code: string): string {
  try {
    return regionNames?.of(code) ?? code;
  } catch {
    return code;
  }
}

/** A YYYY-MM registry month in the middle of the month, so every time zone shows the same month (BipeCNPJ.checkedDate). */
function monthOf(value: string | undefined): string | undefined {
  const m = value ? /^(\d{4})-(0[1-9]|1[0-2])$/.exec(value) : null;
  return m ? monthYear.format(new Date(Number(m[1]), Number(m[2]) - 1, 15)) : undefined;
}

function Fact({ label, value }: { label: string; value: string | undefined }) {
  if (!value) return null;
  return (
    <div className="flex flex-col gap-0.5 px-4 py-2 [&:not(:last-child)]:border-b-[0.5px] [&:not(:last-child)]:border-line-subtle">
      <span className="text-footnote text-ink-secondary">{label}</span>
      <span className="text-body text-ink-primary">{value}</span>
    </div>
  );
}

/** Spec company-seal: the facts behind a verified company seal. */
function CompanySealSheet({ seal, onClose }: { seal: ShownBadge; onClose: () => void }) {
  const { claims, issuerName } = seal;
  const org = claims.cnpj === undefined;
  const foreign = isForeign(claims);
  let cvm: { lines: { key: StringKey; arg?: string }[]; danger: boolean } = { lines: [], danger: false };
  try {
    if (claims.cvmJson) cvm = cvmLines(JSON.parse(claims.cvmJson));
  } catch {
    // A bad CVM field is no CVM line, never a bad seal.
  }
  const cvmText = cvm.lines.map((line) => s(line.key, ...(line.arg ? [line.arg] : [])));
  return (
    <Sheet title={s('bipe_app_seal_title')} onClose={onClose}>
      <div className="flex flex-col gap-4 p-4">
        <div className="flex flex-col items-center gap-2 text-trust-verified-ink">
          <Icon path={sealCheckFill} size={40} />
          <p className="text-headline">{s('bipe_badge_company')}</p>
        </div>
        {cvm.danger && (
          <p className="flex items-start gap-2 text-subheadline text-trust-danger-ink">
            <Icon path={warningCircle} size={18} className="mt-0.5 shrink-0" />
            {cvmText[0]}
          </p>
        )}
        <div className="overflow-hidden rounded-xl bg-grouped-cell">
          <Fact label={s('bipe_badge_company_legal_name')} value={claims.legalName ?? claims.name} />
          <Fact label={s('bipe_badge_company_trade_name')} value={claims.tradeName} />
          {org ? (
            <>
              <Fact label={s('bipe_badge_org_id')} value={claims.orgId} />
              <Fact label={s('bipe_badge_seal_country')} value={claims.country ? countryName(claims.country) : undefined} />
              <Fact label={s('bipe_badge_seal_issuer')} value={issuerName} />
            </>
          ) : (
            <>
              <Fact label={s('bipe_badge_company_cnpj')} value={formatCnpj(claims.cnpj)} />
              <Fact label={s('bipe_badge_company_checked')} value={monthOf(claims.checkedMonth)} />
            </>
          )}
          <Fact label={s('bipe_badge_company_valid_until')} value={longDate.format(Number(claims.exp) * 1000)} />
        </div>
        {!cvm.danger && cvmText.length > 0 && (
          <div className="flex flex-col gap-1 text-footnote text-ink-secondary">
            {cvmText.map((line) => (
              <p key={line}>{line}</p>
            ))}
          </div>
        )}
        {cvmText.length > 0 && <p className="text-footnote text-ink-secondary">{s('bipe_cvm_source')}</p>}
        <p className="text-footnote text-ink-secondary">{s(org ? 'bipe_badge_org_body' : 'bipe_badge_company_body')}</p>
        {foreign && <p className="text-footnote text-ink-secondary">{s('bipe_badge_foreign_body')}</p>}
      </div>
    </Sheet>
  );
}

/** Spec person-seal: mark, label, verified name, scheme, month of the check, end of validity, then the limits. */
function PersonSealSheet({ proof, issuerName, onClose }: { proof: PersonProofShown; issuerName?: string; onClose: () => void }) {
  const { claims } = proof;
  const foreign = isForeign(claims);
  return (
    <Sheet title={s('bipe_badge_verified_via', proof.name)} onClose={onClose}>
      <div className="flex flex-col gap-4 p-4">
        <div className="flex flex-col items-center gap-2 text-trust-verified-ink">
          <Icon path={proof.fill ? sealCheckFill : sealCheck} size={40} />
          <p className="text-headline">{s('bipe_badge_verified_via', proof.name)}</p>
        </div>
        <div className="overflow-hidden rounded-xl bg-grouped-cell">
          <Fact label={s('bipe_badge_verified')} value={claims.name} />
          <Fact label={s('bipe_person_seal_scheme')} value={proof.name} />
          {foreign && <Fact label={s('bipe_badge_seal_issuer')} value={issuerName} />}
          {foreign && <Fact label={s('bipe_badge_seal_country')} value={countryName(proof.country)} />}
          <Fact label={s('bipe_person_seal_checked')} value={monthYear.format(Number(claims.iat) * 1000)} />
          <Fact label={s('bipe_person_seal_valid_until')} value={monthYear.format(Number(claims.exp) * 1000)} />
        </div>
        <p className="text-footnote text-ink-secondary">{s('bipe_person_seal_body')}</p>
        {foreign && <p className="text-footnote text-ink-secondary">{s('bipe_badge_foreign_body')}</p>}
      </div>
    </Sheet>
  );
}

/**
 * The seal sheet of an account: the person seal when there is one, else the company seal. It closes when the seal
 * no longer verifies (person-seal notes).
 */
export function SealSheet({ userId, onClose }: { userId: string; onClose: () => void }) {
  const { company, person } = useSeals(userId);
  const open = person.state === 'verified' || company.state === 'verified';
  useEffect(() => {
    if (!open) onClose();
  }, [open, onClose]);
  if (person.state === 'verified') return <PersonSealSheet proof={person.proof} issuerName={person.issuerName} onClose={onClose} />;
  if (company.state === 'verified') return <CompanySealSheet seal={company.badge} onClose={onClose} />;
  return null;
}

/**
 * The notice of a seal that ended, under the name in person-profile: only where this browser saw the seal before
 * (company-seal revoked and suspended, bipe_badge_suspended_body for a person). Caution with an icon, not colour alone.
 */
export function SealNotice({ userId }: { userId: string | undefined }) {
  const { company, person } = useSeals(userId);
  const text =
    person.state === 'suspended'
      ? s('bipe_badge_suspended_body')
      : company.state === 'revoked'
        ? s(company.reason === 'not_active' || company.reason === 'not_found' ? 'bipe_badge_company_revoked_not_active' : 'bipe_badge_company_revoked')
        : company.state === 'suspended'
          ? s('bipe_badge_company_suspended')
          : undefined;
  if (!text) return null;
  return (
    <p className="flex items-start justify-center gap-2 text-center text-subheadline text-trust-caution-ink">
      <Icon path={sealWarning} size={18} className="mt-0.5 shrink-0" />
      {text}
    </p>
  );
}

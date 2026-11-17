// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Seals and signed lists on the web through bipe-core (ADR 0267, ADR 0272, bipe-chat/bipe#1457). bipe-core verifies
// every badge and every list; this module only picks the trust, reads the result and names the scheme. It has no
// DOM, no network and no React, so scripts/seal-vectors.ts runs the bipe-shared vectors through it in Node.
// The same steps as BipeKit BadgeVerifier.swift, SignedLists.swift and BipePersonProofs.swift.

import {
  BadgeLevel,
  BoundBadgeResult,
  IssuedBadgeResult,
  ListType,
  SealMark,
  SignedListResult,
  badgeFailureCode,
  personProofs,
  signedListResultCode,
  verifyBoundSenderBadge,
  verifyBundledList,
  verifyIssuedBadge,
  verifySignedList,
  type BadgeClaims,
  type SignerSet,
  type TrustedKey,
  type VerifiedList,
} from './core';
import { badgeKeys, schemes } from './bipe/generated/registries.json';
import signedLists from './bipe/generated/signed-lists.json';

export { ListType };
export type { BadgeClaims, VerifiedList };

/** Strict base64url without padding (RFC 4648 section 5) to bytes, or undefined. */
export function base64UrlBytes(text: string): ArrayBuffer | undefined {
  if (!/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return undefined;
  const binary = atob(text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0)).buffer;
}

/** kid to base64url key, as trusted keys for bipe-core. A key that is not base64url is dropped. */
export function trustedKeys(keys: Record<string, string>): TrustedKey[] {
  return Object.entries(keys).flatMap(([kid, key]) => {
    const publicKey = base64UrlBytes(key);
    return publicKey ? [{ kid, publicKey }] : [];
  });
}

/** The list signer set of this build (registries/list-signers.json). */
export function signerSet(): SignerSet {
  return { threshold: signedLists.threshold, keys: trustedKeys(signedLists.keys) };
}

/** The bundled envelope of a list type. */
export function bundledList(type: ListType): string {
  return type === ListType.Operators ? signedLists.operators : signedLists.issuers;
}

/**
 * The higher version of the valid envelopes, bundled and stored (SignedLists.newest). The bundled check skips
 * expiry, so an old build still starts; a stale list follows the core's stale rule.
 */
export function newestList(type: ListType, stored: string | undefined): VerifiedList | undefined {
  let best: VerifiedList | undefined;
  for (const text of [bundledList(type), stored]) {
    if (text === undefined) continue;
    const result = verifyBundledList(text, type, signerSet());
    if (SignedListResult.Valid.instanceOf(result) && (!best || result.inner.list.version > best.version)) {
      best = result.inner.list;
    }
  }
  return best;
}

/** Verifies a fetched envelope against the current list (SignedLists.accept). list is set only when it is valid. */
export function acceptList(
  text: string,
  type: ListType,
  current: VerifiedList | undefined,
  nowSeconds: bigint,
): { code: string; list?: VerifiedList } {
  const result = verifySignedList(text, type, signerSet(), nowSeconds, current?.stored);
  return {
    code: signedListResultCode(result),
    list: SignedListResult.Valid.instanceOf(result) ? result.inner.list : undefined,
  };
}

/** A badge that bipe-core accepted, with the issuer name when the issuer registry checked it. */
export type ShownBadge = { claims: BadgeClaims; issuerName?: string };

export type BadgeCheck = { code: 'valid'; badge: ShownBadge } | { code: string; badge?: undefined };

/**
 * The trust of a check: the payload JSON of a valid issuer registry, or a flat key list (tests,
 * test-vectors/badges.json, and the last fallback of registries/badge-keys.json).
 */
export type BadgeTrust = { registry: string } | { keys: Record<string, string> };

/** The release trust (BadgeVerifier.bipeTrusted): the issuer registry, else the flat keys of the build. */
export function releaseTrust(issuers: VerifiedList | undefined): BadgeTrust {
  return issuers ? { registry: issuers.payloadJson } : { keys: badgeKeys.keys };
}

/** Runs the verification order of docs/badge-attestation.md in bipe-core. Any failure is no seal. */
export function verifyBadge(
  envelopeJson: string,
  sender: string,
  masterKey: string | undefined,
  trust: BadgeTrust,
  nowSeconds: bigint,
): BadgeCheck {
  if ('registry' in trust) {
    const result = verifyIssuedBadge(envelopeJson, sender, masterKey, trust.registry, badgeKeys.lifeDays, nowSeconds);
    if (IssuedBadgeResult.Valid.instanceOf(result)) {
      return { code: 'valid', badge: { claims: result.inner.badge.claims, issuerName: result.inner.badge.issuerName } };
    }
    return { code: badgeFailureCode(result.inner.failure) };
  }
  const result = verifyBoundSenderBadge(envelopeJson, sender, masterKey, trustedKeys(trust.keys), nowSeconds);
  if (BoundBadgeResult.Valid.instanceOf(result)) return { code: 'valid', badge: { claims: result.inner.badge.claims } };
  return { code: badgeFailureCode(result.inner.failure) };
}

/** What a level proves, for feature gates (ADR 0272 decision 11, BadgeLevel.badgeClass in the apps). */
export function badgeClass(level: BadgeLevel): 'person' | 'org' | undefined {
  switch (level) {
    case BadgeLevel.Pix:
    case BadgeLevel.Govbr:
    case BadgeLevel.Person:
      return 'person';
    case BadgeLevel.Cnpj:
    case BadgeLevel.Org:
      return 'org';
    default:
      return undefined;
  }
}

/** A scheme outside Brazil: the sheet names the issuer and the country (ADR 0272 decision 7). */
export function isForeign(claims: BadgeClaims): boolean {
  return claims.country !== undefined && claims.country !== 'BR';
}

type Names = { 'pt-BR'?: string; en?: string };
const pick = (names: Names | undefined, locale: 'pt-BR' | 'en') =>
  names && ((locale === 'en' ? names.en : names['pt-BR']) ?? names['pt-BR'] ?? names.en);

/**
 * The name of a scheme in the locale (BipeSchemeNames): a Brazilian scheme from the build, another country's from
 * the schemes of the issuer registry. undefined: unknown scheme.
 */
export function schemeName(id: string, registryJson: string | undefined, locale: 'pt-BR' | 'en'): string | undefined {
  const local = schemes.schemes.find((scheme) => scheme.id === id);
  if (local) return pick(local.names, locale);
  if (!registryJson) return undefined;
  try {
    const root = JSON.parse(registryJson) as { schemes?: { id?: unknown; names?: Names }[] };
    return pick(root.schemes?.find((scheme) => scheme.id === id)?.names, locale);
  } catch {
    return undefined;
  }
}

/** One person proof that verified on this device (ADR 0275). */
export type PersonProofShown = { scheme: string; name: string; fill: boolean; country: string; claims: BadgeClaims };

/**
 * The person proofs of verified claims, strongest first, one per scheme (bipe-core personProofs). A level or a
 * scheme outside the issuer registry, or a scheme with no name, gives nothing (fail closed).
 */
export function personProofsOf(
  claims: BadgeClaims[],
  registryJson: string | undefined,
  locale: 'pt-BR' | 'en',
): PersonProofShown[] {
  const badges = claims.map((c) => ({ level: c.level, scheme: c.scheme }));
  return personProofs(badges, registryJson ?? '').flatMap((proof) => {
    const source = claims[proof.badgeIndex];
    const name = schemeName(proof.scheme, registryJson, locale);
    if (!source || !name) return [];
    return [{ scheme: proof.scheme, name, fill: proof.mark === SealMark.Fill, country: proof.country, claims: source }];
  });
}

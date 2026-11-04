// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Trust facts of a person with bipe-api (spec chat-request; RequestTrustContent in RequestNameWarning.swift,
// ProfileFactsLines in ProfileFactsViews.swift, BipeProfileFactLines in BipeKit). The rules are the generated
// ones the apps' vectors check: profileFactLines (ADR 0049), contactCollision (security rule 8) and
// isBipeUserID. A failed API call shows nothing extra; it never blocks accept or decline.

import { useEffect, useState } from 'react';
import { apiFor } from './api';
import { contactCollision, isBipeUserID, profileFactLines } from './bipe/generated/lib/registries.mjs';
import { bipeServers, homoglyphs, profileFacts as profileFactRules } from './bipe/generated/registries.json';
import { useClient } from './media';
import { warning } from './phosphor/icons';
import { locale, s } from './ui/i18n';
import { Icon } from './ui/Icon';

const reg = { bipeServers, homoglyphs, profileFacts: profileFactRules };

/** The time since a date in one unit, rounded down: "3 dias", "5 horas", "1 minuto" (BipeProfileFactLines.age). */
function age(at: number, now: number): string {
  const seconds = Math.max(60, (now - at) / 1000);
  const [value, unit] =
    seconds >= 86_400
      ? [Math.floor(seconds / 86_400), 'day']
      : seconds >= 3_600
        ? [Math.floor(seconds / 3_600), 'hour']
        : [Math.floor(seconds / 60), 'minute'];
  return new Intl.NumberFormat(locale, { style: 'unit', unit, unitDisplay: 'long' }).format(value);
}

type FactLine = { fact: 'created' | 'photo' | 'name'; count?: number; at: string };

/** The facts of a person as text (spec chat-request and person-profile profileFacts), or undefined. */
function useProfileFacts(userId: string | undefined): { texts: string[]; createdAt?: number } | undefined {
  const client = useClient();
  const [result, setResult] = useState<{ texts: string[]; createdAt?: number }>();
  useEffect(() => {
    if (!userId) return;
    let stopped = false;
    void apiFor(client)
      .profileFacts(userId)
      .then((facts) => {
        if (stopped || !facts) return;
        const now = Date.now();
        const { lines } = profileFactLines(facts, now, reg) as { lines: FactLine[] };
        const window = String(profileFactRules.windowDays);
        const texts = lines.map((line) => {
          const when = age(Date.parse(line.at), now);
          const count = line.count ?? 1;
          if (line.fact === 'created') return s('bipe_profile_fact_created', when);
          if (line.fact === 'photo')
            return count >= 2
              ? s('bipe_profile_fact_photo_times', String(count), window)
              : s('bipe_profile_fact_photo_once', when);
          return count >= 2
            ? s('bipe_profile_fact_name_times', String(count), window)
            : s('bipe_profile_fact_name_once', when);
        });
        const created = facts.created_at ? Date.parse(facts.created_at) : NaN;
        setResult({ texts, createdAt: Number.isFinite(created) ? created : undefined });
      });
    return () => {
      stopped = true;
    };
  }, [client, userId]);
  return result;
}

/** ProfileFactsLines: footnote lines in ink.secondary under the name. */
export function ProfileFactsLines({ userId }: { userId?: string }) {
  const facts = useProfileFacts(userId);
  if (!facts || facts.texts.length === 0) return null;
  return (
    <div className="flex flex-col gap-0.5 text-center text-footnote text-ink-secondary">
      {facts.texts.map((t) => (
        <p key={t}>{t}</p>
      ))}
    </div>
  );
}

/** The @handle of a person, from bipe-api, when there is one. */
export function useHandle(userId: string | undefined): string | undefined {
  const client = useClient();
  const [handle, setHandle] = useState<string>();
  useEffect(() => {
    if (!userId) return;
    let stopped = false;
    void apiFor(client)
      .resolveHandles([userId])
      .then((m) => !stopped && setHandle(m.get(userId)));
    return () => {
      stopped = true;
    };
  }, [client, userId]);
  return handle;
}

// The trust block of a request. Verified needs a company seal that verifies against the inviter's master key
// (ADR 0041); matrix-sdk-ffi does not export the master key, so the web never shows verified: a Bipe account
// is unverified, another server's account external. Then the contact collision line.
export function RequestTrust({
  userId,
  name,
  contactNames,
}: {
  userId?: string;
  name: string;
  contactNames: string[];
}) {
  const external = userId !== undefined && !isBipeUserID(userId, reg);
  const collision = external ? null : (contactCollision(name, contactNames, reg) as string | null);
  return (
    <div className="flex w-full flex-col gap-3">
      <div className="flex flex-col gap-2 rounded-xl bg-trust-caution-wash p-4 text-center">
        {external ? (
          <p className="text-body text-trust-neutral-ink">{s('bipe_banner_external')}</p>
        ) : (
          <>
            <p className="text-headline text-trust-caution-ink">{s('bipe_banner_unverified_title')}</p>
            <p className="text-body text-ink-secondary">{s('bipe_request_unverified_body')}</p>
          </>
        )}
      </div>
      {collision && (
        // Security rule 8: caution with an icon, not colour alone.
        <p className="flex items-start justify-center gap-2 text-subheadline text-trust-caution-ink">
          <Icon path={warning} size={18} className="mt-0.5 shrink-0" />
          {s('bipe_warning_not_your_contact', collision)}
        </p>
      )}
    </div>
  );
}

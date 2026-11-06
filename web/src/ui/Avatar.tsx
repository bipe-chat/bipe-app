// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// AvatarView.swift: the photo, or the first letter in accent.ink on accent.wash, semibold at 42% of the side.
// The photo is the SDK thumbnail at 3 x the size, as on iOS. A company account is a rounded square (corner
// 25% of the side), a person or an unknown account a circle (ADR 0132, ADR 0120). The type comes from bipe-api
// GET /v1/accounts/{id}/type; a failure keeps the circle (fail closed).
// MemberCluster (AvatarView.swift): a group with no photo shows its members as overlapping circles on
// surface.sunken, each with a surface.sunken ring; one member shows that member's avatar.
// Official avatar (ADR 0117): the app draws the logo, so no profile photo can replace it.

import { useContext, useEffect, useState } from 'react';
import { accountType } from '../api';
import { ClientContext, thumbnailOfUrl, useObjectUrl } from '../media';
import { isOfficial } from '../registries';
import { BipeMark } from './BipeMark';

function initial(name: string): string {
  const letter = Array.from(name.replace(/^[@#!+ ]+|[@#!+ ]+$/g, ''))[0];
  return letter ? letter.toUpperCase() : '';
}

/** True once bipe-api says the account is a company. */
export function useIsCompany(userId: string | undefined): boolean {
  const [company, setCompany] = useState(false);
  useEffect(() => {
    if (!userId) return setCompany(false);
    let stopped = false;
    void accountType(userId).then((t) => !stopped && setCompany(t === 'company'));
    return () => {
      stopped = true;
    };
  }, [userId]);
  return company;
}

/** A member of a group with no photo (RoomFace.members). */
export type AvatarMember = { userId: string; displayName?: string; avatarUrl?: string };

// Diameter and centre of each circle as fractions of the group circle, for 2 and 3 members.
const LAYOUTS = [
  [
    [0.56, 0.36, 0.38],
    [0.48, 0.66, 0.64],
  ],
  [
    [0.5, 0.36, 0.38],
    [0.36, 0.72, 0.36],
    [0.4, 0.6, 0.73],
  ],
];

function MemberCluster({ members, size }: { members: AvatarMember[]; size: number }) {
  if (members.length === 1) {
    const m = members[0];
    return <Avatar name={m.displayName ?? m.userId} size={size} mxcUrl={m.avatarUrl} userId={m.userId} />;
  }
  const slots = LAYOUTS[Math.min(members.length, 3) - 2];
  const ring = Math.max(1, size * 0.025);
  return (
    <span
      aria-hidden
      className="relative block shrink-0 overflow-hidden rounded-full bg-surface-sunken"
      style={{ width: size, height: size }}
    >
      {slots.map(([diameter, x, y], i) => {
        const m = members[i];
        const side = size * diameter;
        return (
          <span
            key={m.userId}
            className="absolute block rounded-full bg-surface-sunken"
            style={{ left: size * x - side / 2 - ring, top: size * y - side / 2 - ring, padding: ring }}
          >
            {/* No userId: the hero already carries the photo, and the circles stay round. */}
            <span className="block overflow-hidden rounded-full">
              <Avatar name={m.displayName ?? m.userId} size={side} mxcUrl={m.avatarUrl} official={isOfficial(m.userId)} />
            </span>
          </span>
        );
      })}
    </span>
  );
}

export function Avatar({
  name,
  size,
  mxcUrl,
  userId,
  official,
  members,
}: {
  name: string;
  size: number;
  mxcUrl?: string;
  /** The account behind the avatar, for its shape. */
  userId?: string;
  /** An official room with no user behind the avatar: the news room. */
  official?: boolean;
  /** A group with no photo: its members, shown as a cluster when mxcUrl is empty. */
  members?: AvatarMember[];
}) {
  if (official || isOfficial(userId)) return <OfficialLogo size={size} />;
  if (!mxcUrl && members?.length) return <MemberCluster members={members} size={size} />;
  return <Single name={name} size={size} mxcUrl={mxcUrl} userId={userId} />;
}

function OfficialLogo({ size }: { size: number }) {
  return (
    <span
      aria-hidden
      className="grid shrink-0 place-items-center overflow-hidden bg-accent-fill text-ink-on-accent"
      style={{ width: size, height: size, borderRadius: size * 0.25 }}
    >
      <BipeMark style={{ width: size * 0.58, height: size * 0.58 }} />
    </span>
  );
}

function Single({ name, size, mxcUrl, userId }: { name: string; size: number; mxcUrl?: string; userId?: string }) {
  const client = useContext(ClientContext);
  const square = useIsCompany(userId);
  const pixels = size * 3;
  const photo = useObjectUrl(client && mxcUrl ? `${mxcUrl}#${pixels}` : undefined, () =>
    thumbnailOfUrl(client!, mxcUrl!, pixels),
  );
  return (
    <span
      aria-hidden
      className="grid shrink-0 place-items-center overflow-hidden bg-accent-wash leading-none font-semibold text-accent-ink"
      style={{ width: size, height: size, fontSize: size * 0.42, borderRadius: square ? size * 0.25 : '9999px' }}
    >
      {photo.url ? <img src={photo.url} alt="" className="size-full object-cover" /> : initial(name)}
    </span>
  );
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// AvatarView.swift: the photo, or the first letter in accent.ink on accent.wash, semibold at 42% of the side.
// The photo is the SDK thumbnail at 3 x the size, as on iOS. A company account is a rounded square (corner
// 25% of the side), a person or an unknown account a circle (ADR 0132, ADR 0120). The type comes from bipe-api
// GET /v1/accounts/{id}/type; a failure keeps the circle (fail closed).

import { useContext, useEffect, useState } from 'react';
import { accountType } from '../api';
import { ClientContext, thumbnailOfUrl, useObjectUrl } from '../media';

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

export function Avatar({
  name,
  size,
  mxcUrl,
  userId,
}: {
  name: string;
  size: number;
  mxcUrl?: string;
  /** The account behind the avatar, for its shape. */
  userId?: string;
}) {
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

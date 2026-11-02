// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// AvatarView.swift: the photo, or a circle in accent.wash with the first letter in accent.ink,
// semibold at 42% of the side. The photo is the SDK thumbnail at 3 x the size, as on iOS.
// TODO: the company rounded square (ADR 0132) needs the account-type API (GET /v1/accounts/{id}/type),
// which the web client does not call yet; every avatar is a circle.

import { useContext } from 'react';
import { ClientContext, thumbnailOfUrl, useObjectUrl } from '../media';

function initial(name: string): string {
  const letter = Array.from(name.replace(/^[@#!+ ]+|[@#!+ ]+$/g, ''))[0];
  return letter ? letter.toUpperCase() : '';
}

export function Avatar({ name, size, mxcUrl }: { name: string; size: number; mxcUrl?: string }) {
  const client = useContext(ClientContext);
  const pixels = size * 3;
  const photo = useObjectUrl(client && mxcUrl ? `${mxcUrl}#${pixels}` : undefined, () =>
    thumbnailOfUrl(client!, mxcUrl!, pixels),
  );
  return (
    <span
      aria-hidden
      className="grid shrink-0 place-items-center overflow-hidden rounded-full bg-accent-wash leading-none font-semibold text-accent-ink"
      style={{ width: size, height: size, fontSize: size * 0.42 }}
    >
      {photo.url ? <img src={photo.url} alt="" className="size-full object-cover" /> : initial(name)}
    </span>
  );
}

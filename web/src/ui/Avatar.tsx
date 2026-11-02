// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// AvatarView.swift: a circle in accent.wash with the first letter in accent.ink, semibold at 42% of
// the side. TODO: photos (MediaLoader.thumbnail) and the company square (ADR 0132) need SDK media
// and the account-type API; the web client has neither yet.

function initial(name: string): string {
  const letter = Array.from(name.replace(/^[@#!+ ]+|[@#!+ ]+$/g, ''))[0];
  return letter ? letter.toUpperCase() : '';
}

export function Avatar({ name, size }: { name: string; size: number }) {
  return (
    <span
      aria-hidden
      className="grid shrink-0 place-items-center overflow-hidden rounded-full bg-accent-wash leading-none font-semibold text-accent-ink"
      style={{ width: size, height: size, fontSize: size * 0.42 }}
    >
      {initial(name)}
    </span>
  );
}

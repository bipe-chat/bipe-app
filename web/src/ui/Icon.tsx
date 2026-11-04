// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// A Phosphor icon (web/src/phosphor, the same set as the apps). Decorative unless it has a label,
// like PhosphorIcon.image and labelledImage on iOS. The colour comes from the text colour.

export function Icon({
  path,
  size,
  label,
  className,
}: {
  path: string;
  size: number;
  label?: string;
  className?: string;
}) {
  return (
    <svg
      viewBox="0 0 256 256"
      width={size}
      height={size}
      fill="currentColor"
      className={className}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      <path d={path} />
    </svg>
  );
}

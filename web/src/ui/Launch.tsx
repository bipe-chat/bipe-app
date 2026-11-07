// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec account-start: the launch screen. The icon background of the palette, full bleed, one turning ring, no text.
// The same markup as index.html, which draws it before the script runs. The label is for screen readers only.

export function Launch({ label }: { label: string }) {
  return (
    <div role="status" className="bipe-launch">
      <svg className="bipe-launch-ring" aria-hidden="true">
        <circle pathLength={1} />
      </svg>
      <span className="sr-only">{label}</span>
    </div>
  );
}

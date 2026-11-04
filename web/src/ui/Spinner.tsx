// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The ProgressView of the apps: a small turning ring. It stands still under reduced motion.

export function Spinner({ label }: { label?: string }) {
  return (
    <span role="status" className="inline-grid place-items-center">
      <span className="size-5 rounded-full border-2 border-line-strong border-t-transparent motion-safe:animate-spin" />
      {label && <span className="sr-only">{label}</span>}
    </span>
  );
}

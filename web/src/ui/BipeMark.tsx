// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import type { CSSProperties } from 'react';

// Bipe's mark, the shape of the app icon (BipeMark.swift): a rounded bubble with a tight top-leading
// corner. Path: bipe-shared design/tokens.json appThemes.icon.path, the 48-unit mark inside its 64-unit box.
const MARK = 'M8 10c0-1.1.9-2 2-2h28c9.9 0 18 8.1 18 18v12c0 9.9-8.1 18-18 18H26C16.1 56 8 47.9 8 38V10Z';

export function BipeMark({ className, style }: { className?: string; style?: CSSProperties }) {
  return (
    <svg viewBox="8 8 48 48" fill="currentColor" className={className} style={style} aria-hidden focusable="false">
      <path d={MARK} />
    </svg>
  );
}

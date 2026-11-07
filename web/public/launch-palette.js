// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec account-start: the stored palette before the first paint, so the launch screen has its colour at once.
// A blocking script, not part of the bundle. src/appearance.ts owns this key and corrects an unknown id.
try {
  var palette = localStorage.getItem('bipe.settings.palette');
  if (palette) document.documentElement.dataset.palette = palette;
} catch (e) {
  // Without storage the launch screen shows the default palette.
}

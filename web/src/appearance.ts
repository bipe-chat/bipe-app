// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The browser's look and notification choices (settings chats.look and the web notification rows), kept in
// this browser like the phone keeps them on the phone. Theme (spec settings-group appearance): system, light
// or dark. The token CSS follows the system with media queries; to force a scheme, the page copies the
// generated token rules of that scheme, as they are, into one style element after the others. No colour is
// typed here. Palette (spec app-colors): data-palette on the root element selects the generated palette
// tokens. The choices and defaults come from the same catalogue as Swift and Kotlin.

import { useSyncExternalStore } from 'react';
import { PALETTES, BACKGROUNDS, DEFAULT_PALETTE, DEFAULT_BACKGROUND } from './bipe/generated/appearance';
export { PALETTES, BACKGROUNDS };

export type Appearance = 'system' | 'light' | 'dark';
export type ChatBackgroundOption = (typeof BACKGROUNDS)[number];
export type Palette = (typeof PALETTES)[number];

const KEYS = {
  appearance: 'bipe.settings.appearance',
  palette: 'bipe.settings.palette',
  chatBackground: 'bipe.settings.chatBackground',
  sound: 'bipe.web.notifications.sound',
  preview: 'bipe.web.notifications.preview',
} as const;
type Key = keyof typeof KEYS;

const DEFAULTS = { appearance: 'system', palette: DEFAULT_PALETTE, chatBackground: DEFAULT_BACKGROUND, sound: 'true', preview: 'true' };

const listeners = new Set<() => void>();

export function readSetting(key: Key): string {
  try {
    const value = localStorage.getItem(KEYS[key]) ?? DEFAULTS[key];
    if (key === 'palette' && !PALETTES.some((p) => p === value)) return DEFAULT_PALETTE;
    if (key === 'chatBackground' && !BACKGROUNDS.some((b) => b === value)) return DEFAULT_BACKGROUND;
    return value;
  } catch {
    return DEFAULTS[key];
  }
}

export function writeSetting(key: Key, value: string) {
  try {
    localStorage.setItem(KEYS[key], value);
  } catch {
    // Without storage the choice lasts for this page.
  }
  if (key === 'appearance' || key === 'palette') applyLook();
  listeners.forEach((l) => l());
}

/** One setting, re-rendering when it changes. */
export function useSetting(key: Key): string {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => readSetting(key),
  );
}

/** True when the page shows the dark scheme now. */
export function isDark(): boolean {
  const a = readSetting('appearance');
  return a === 'dark' || (a === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

function tokenRules(scheme: 'light' | 'dark'): string {
  const out: string[] = [];
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList;
    try {
      rules = sheet.cssRules;
    } catch {
      continue;
    }
    for (const rule of Array.from(rules)) {
      if (scheme === 'dark' && rule instanceof CSSMediaRule) {
        const media = rule.media.mediaText;
        if (media.includes('prefers-color-scheme: dark') && !media.includes('prefers-contrast')) {
          out.push(...Array.from(rule.cssRules).map((r) => r.cssText));
        }
      } else if (scheme === 'light' && rule instanceof CSSStyleRule) {
        if (rule.selectorText.startsWith(':root') || rule.selectorText.startsWith('[data-palette'))
          out.push(rule.cssText);
      }
    }
  }
  return out.join('\n');
}

/** Applies the stored theme and palette to the page. */
export function applyLook() {
  const root = document.documentElement;
  const palette = readSetting('palette');
  if (palette === DEFAULT_PALETTE) delete root.dataset.palette;
  else root.dataset.palette = palette;
  const appearance = readSetting('appearance') as Appearance;
  document.getElementById('bipe-theme')?.remove();
  root.style.colorScheme = appearance === 'system' ? '' : appearance;
  if (appearance === 'system') return;
  const style = document.createElement('style');
  style.id = 'bipe-theme';
  style.textContent = tokenRules(appearance);
  document.head.appendChild(style);
}

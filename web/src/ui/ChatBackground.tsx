// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec app-colors, ADR 0213: the default chat background, marca (ChatBackgroundView.swift). Soft blobs
// and Bipe marks in the chat.* tokens on chat.background. It stays still while the messages scroll. In
// high contrast every chat token is chat.background, so only the flat colour shows.
//
// The places come from bipe-shared design/tokens.json appThemes.chatBackground.
// TODO(bipe-shared): tools/gen.mjs writes BipeChatBackground for Swift and Kotlin only. Read a generated
// web module here when it exists, and delete this copy.

import { useSetting } from '../appearance';
import { BipeMark } from './BipeMark';

const blobOpacity = 0.85;
// Centre as fractions of the width and height; radius as a fraction of the longer side.
const blobs = [
  { x: 0.95, y: 0.02, radius: 0.62, tone: '--bipe-chat-blob' },
  { x: 0.05, y: 0.55, radius: 0.55, tone: '--bipe-chat-blob-deep' },
];
// Top-leading corner as fractions of the width and height; side as a fraction of the shorter side.
// Drawn in order: the big mark is last, on top.
const marks = [
  { x: 0.6, y: 0.04, side: 0.22, opacity: 0.7, tone: '--bipe-chat-mark' },
  { x: -0.07, y: 0.08, side: 0.3, opacity: 0.7, tone: '--bipe-chat-mark-alt' },
  { x: 0.14, y: 0.34, side: 0.12, opacity: 0.7, tone: '--bipe-chat-mark' },
  { x: -0.06, y: 0.58, side: 0.26, opacity: 0.7, tone: '--bipe-chat-mark-alt' },
  { x: 0.69, y: 0.91, side: 0.15, opacity: 0.7, tone: '--bipe-chat-mark' },
  { x: 0.37, y: 0.18, side: 0.075, opacity: 0.7, tone: '--bipe-chat-mark-alt' },
  { x: 0.44, y: 0.37, side: 0.86, opacity: 1, tone: '--bipe-chat-mark' },
];

// A radial gradient from the tone at blobOpacity to clear. CSS paints the first layer on top, so the
// list is reversed to keep the drawing order of the apps.
const blobLayers = blobs
  .map(
    (b) =>
      `radial-gradient(circle calc(${b.radius} * max(100cqw, 100cqh)) at ${b.x * 100}% ${b.y * 100}%, ` +
      `color-mix(in srgb, var(${b.tone}) ${blobOpacity * 100}%, transparent), transparent)`,
  )
  .reverse()
  .join(', ');

export function ChatBackground() {
  // The Cores choice (spec app-colors pickBackground): marca, cor (chat.background alone) or liso
  // (surface.base). Salada de frutas needs the fruit tile data, which the web does not have yet.
  const option = useSetting('chatBackground');
  if (option === 'cor' || option === 'liso')
    return (
      <div
        aria-hidden
        className={`pointer-events-none absolute inset-0 -z-10 ${option === 'cor' ? 'bg-chat-background' : 'bg-surface-base'}`}
      />
    );
  return (
    // The container query units (cqw, cqh) of the children measure this box.
    <div
      aria-hidden
      className="pointer-events-none absolute inset-0 -z-10 overflow-hidden bg-chat-background [container-type:size]"
    >
      <div className="absolute inset-0" style={{ backgroundImage: blobLayers }} />
      {marks.map((m, i) => (
        <BipeMark
          key={i}
          className="absolute"
          style={{
            left: `${m.x * 100}%`,
            top: `${m.y * 100}%`,
            width: `calc(${m.side} * min(100cqw, 100cqh))`,
            height: `calc(${m.side} * min(100cqw, 100cqh))`,
            color: `var(${m.tone})`,
            opacity: m.opacity,
          }}
        />
      ))}
    </div>
  );
}

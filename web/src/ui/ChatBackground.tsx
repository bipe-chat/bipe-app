// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec app-colors, ADR 0213/0215. Geometry and artwork come from the native theme generator.
import { lazy, Suspense } from 'react';
import { useSetting } from '../appearance';
import { CHAT_BACKGROUND } from '../bipe/generated/appearance';
import { BipeMark } from './BipeMark';

const FruitBackground = lazy(() => import('./FruitBackground'));
const tone = (name: string) => '--bipe-' + name.replaceAll('.', '-').replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
// CSS paints the first gradient on top, so reverse the native drawing order.
const blobLayers = CHAT_BACKGROUND.blobs.map((b) =>
  `radial-gradient(circle calc(${b.radius} * max(100cqw, 100cqh)) at ${b.x * 100}% ${b.y * 100}%, ` +
  `color-mix(in srgb, var(${tone(b.tone)}) ${CHAT_BACKGROUND.blobOpacity * 100}%, transparent), transparent)`,
).reverse().join(', ');

export function ChatBackground() {
  const option = useSetting('chatBackground');
  return (
    <div aria-hidden className={`pointer-events-none absolute inset-0 -z-10 overflow-hidden [container-type:size] ${option === 'liso' ? 'bg-surface-base' : 'bg-chat-background'}`}>
      {option === 'frutas' && <Suspense fallback={null}><FruitBackground /></Suspense>}
      {option === 'marca' && <>
        <div className="absolute inset-0" style={{ backgroundImage: blobLayers }} />
        {CHAT_BACKGROUND.marks.map((m, i) => (
          <BipeMark key={i} className="absolute" style={{
            left: `${m.x * 100}%`, top: `${m.y * 100}%`,
            width: `calc(${m.side} * min(100cqw, 100cqh))`, height: `calc(${m.side} * min(100cqw, 100cqh))`,
            color: `var(${tone(m.tone)})`, opacity: m.opacity,
          }} />
        ))}
      </>}
    </div>
  );
}

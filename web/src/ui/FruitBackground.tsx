// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import { useId } from 'react';
import { CHAT_FRUITS as fruit } from '../bipe/generated/chat-fruits';

/** ADR 0215: same tile, placements, clipped edges and constant outline width as the phone renderer. */
export default function FruitBackground() {
  const id = useId();
  const side = fruit.tile * fruit.scale;
  return (
    <svg aria-hidden className="absolute inset-0 size-full">
      <defs>
        <pattern id={id} width={side} height={side} patternUnits="userSpaceOnUse" overflow="hidden">
          <g transform={`scale(${fruit.scale})`} strokeLinecap="round" strokeLinejoin="round">
            {fruit.placements.map((p, i) => {
              const glyph = fruit.glyphs[p.glyph];
              const scale = p.size / 48;
              return (
                <g key={i} transform={`translate(${p.x} ${p.y}) rotate(${p.rotation}) scale(${p.mirror ? -scale : scale} ${scale}) translate(-24 -24)`}>
                  {glyph.bodies.map((d, j) => <path key={`b${j}`} d={d} fill="var(--bipe-chat-fruit-fill)" stroke="var(--bipe-chat-fruit)" strokeWidth={fruit.stroke * fruit.scale} vectorEffect="non-scaling-stroke" />)}
                  {glyph.lines.map((d, j) => <path key={`l${j}`} d={d} fill="none" stroke="var(--bipe-chat-fruit)" strokeWidth={fruit.stroke * fruit.scale} vectorEffect="non-scaling-stroke" />)}
                  {glyph.dots.map((d, j) => <path key={`d${j}`} d={d} fill="var(--bipe-chat-fruit)" />)}
                </g>
              );
            })}
          </g>
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill={`url(#${id})`} />
    </svg>
  );
}

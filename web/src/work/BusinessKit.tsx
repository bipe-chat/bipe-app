// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import QRCode from 'qrcode';
import { useEffect, useRef, useState } from 'react';
import { s } from '../ui/i18n';
import { NavBar, Row, Section } from '../ui/List';
import { SecondaryButton, shareOrSave, useWorkHost } from './common';

// A quiet zone of 4 modules, dark on white (spec business-kit).
const QR_OPTIONS = { errorCorrectionLevel: 'M', margin: 4 } as const;
const PNG_SCALE = 16;

/** Spec business-kit: a button for the company's site, a QR code and ready lines, for its own channels (ADR 0082). */
export function BusinessKitScreen({ onBack }: { onBack: () => void }) {
  const host = useWorkHost();
  const [handle, setHandle] = useState<string | null>();
  useEffect(() => {
    let live = true;
    (host?.ownHandle() ?? Promise.resolve(undefined))
      .catch(() => undefined)
      .then((h) => live && setHandle(h ?? null));
    return () => {
      live = false;
    };
  }, [host]);

  return (
    <div className="flex h-full flex-col">
      <NavBar title={s('bipe_business_kit_title')} onBack={onBack} />
      <div className="flex-1 overflow-y-auto px-4 py-4">
        {handle === null && (
          <p className="text-center text-body text-ink-secondary">{s('bipe_public_profile_needs_handle')}</p>
        )}
        {handle && <Kit link={`https://bipe.chat/@${handle}`} />}
      </div>
    </div>
  );
}

function Kit({ link }: { link: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [notice, setNotice] = useState<string>();
  const copy = (text: string) => {
    void navigator.clipboard?.writeText(text).then(() => setNotice(s('bipe_app_copied')));
  };

  useEffect(() => {
    const target = canvas.current;
    if (!target) return;
    void QRCode.toCanvas(target, link, { ...QR_OPTIONS, scale: PNG_SCALE }).then(() => {
      // As in QrLogin.tsx: without the fixed inline size the canvas fills the white field.
      target.style.removeProperty('width');
      target.style.removeProperty('height');
    });
  }, [link]);

  const sharePng = () => {
    canvas.current?.toBlob((blob) => {
      if (blob) void shareOrSave(new File([blob], 'bipe-qr.png', { type: 'image/png' }), s('bipe_business_kit_qr_header'));
    }, 'image/png');
  };
  const shareSvg = async () => {
    const svg = await QRCode.toString(link, { ...QR_OPTIONS, type: 'svg' });
    await shareOrSave(new File([svg], 'bipe-qr.svg', { type: 'image/svg+xml' }), s('bipe_business_kit_qr_header'));
  };

  const code = s('bipe_business_kit_button_code', link, s('bipe_business_kit_button_label'));
  const lines = [
    s('bipe_business_kit_line_site', link),
    s('bipe_business_kit_line_email', link),
    s('bipe_business_kit_line_receipt', link),
  ];

  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4">
      <p className="text-body text-ink-primary">{s('bipe_business_kit_intro')}</p>
      {notice && (
        <p role="status" className="text-footnote text-ink-secondary">
          {notice}
        </p>
      )}
      <Section header={s('bipe_business_kit_button_header')}>
        <div className="flex flex-col gap-3 p-4">
          <code className="break-all font-mono text-footnote text-ink-primary">{code}</code>
          <SecondaryButton onClick={() => copy(code)}>{s('bipe_business_kit_copy_code')}</SecondaryButton>
        </div>
      </Section>
      <p className="px-4 text-footnote text-ink-secondary">{s('bipe_business_kit_button_footer')}</p>
      <Section header={s('bipe_business_kit_qr_header')}>
        <div className="flex flex-col items-center gap-3 p-4">
          <div className="w-full max-w-[240px] rounded-2xl bg-white">
            <canvas ref={canvas} className="block h-auto w-full [image-rendering:pixelated]" />
          </div>
          <SecondaryButton onClick={sharePng}>{s('bipe_business_kit_share_png')}</SecondaryButton>
          <SecondaryButton onClick={() => void shareSvg()}>{s('bipe_business_kit_share_svg')}</SecondaryButton>
        </div>
      </Section>
      <p className="px-4 text-footnote text-ink-secondary">{s('bipe_business_kit_qr_footer')}</p>
      <Section header={s('bipe_business_kit_lines_header')}>
        {lines.map((line) => (
          <Row key={line} onClick={() => copy(line)}>
            <span className="text-body text-ink-primary">{line}</span>
          </Row>
        ))}
      </Section>
      <p className="px-4 text-footnote text-ink-secondary">{s('bipe_business_kit_rule')}</p>
    </div>
  );
}

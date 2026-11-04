// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Selos de revenda on the web, read only (spec brand-marks, ADR 0162, ADR 0226 point 2): the marks this account
// holds and the accounts that hold its mark. Accepting a code, making a code and ending a mark stay on the phone.

import { s } from '../ui/i18n';
import { NavBar, Section } from '../ui/List';
import type { StringKey } from '../bipe/generated/strings';
import { customerName, LoadState, useLoad, useNames, type WorkScreenProps } from './common';

/** BrandMarksView.line on iOS: one string key per label id (registry brandMarks.labels). */
function brandMarkLine(label: string, brandName: string): string {
  return s(`bipe_brand_mark_label_${label}` as StringKey, brandName);
}

export function BrandMarksScreen({ client, onBack }: WorkScreenProps) {
  useNames();
  const marks = useLoad(async () => {
    const [held, granted] = await Promise.all([
      client.heldBrandMarks(),
      // Only a brand with a verified seal has this list; any other account shows no section.
      client.grantedBrandMarks().catch(() => ({ marks: [] })),
    ]);
    return { held: held.marks, granted: granted.marks };
  }, [client]);
  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-grouped-screen">
      <NavBar title={s('bipe_brand_marks_title')} onBack={onBack} />
      {!marks.data ? (
        <LoadState loading={marks.loading} error={marks.error} onRetry={() => void marks.reload()} />
      ) : (
        <div className="flex flex-col gap-6 px-4 pb-8">
          <Section header={s('bipe_brand_marks_held_header')} footer={s('bipe_brand_marks_held_footer')}>
            {marks.data.held.length === 0 ? (
              <li className="p-4 text-body text-ink-secondary">{s('bipe_brand_marks_held_empty')}</li>
            ) : (
              marks.data.held.map((mark) => (
                <li key={mark.id} className="p-4 text-body text-ink-primary">
                  {brandMarkLine(mark.label, mark.brand_name)}
                </li>
              ))
            )}
          </Section>
          {marks.data.granted.length > 0 && (
            <Section header={s('bipe_brand_marks_granted_header')}>
              {marks.data.granted.map((mark) => (
                <li key={mark.id} className="p-4 text-body text-ink-primary">
                  {customerName(mark.reseller)}
                </li>
              ))}
            </Section>
          )}
          <p className="px-4 text-footnote text-ink-secondary">{s('bipe_web_work_phone_only')}</p>
        </div>
      )}
    </div>
  );
}

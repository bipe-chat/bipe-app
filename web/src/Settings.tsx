// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Settings for this browser (spec settings-root, SettingsRootView.swift and SettingsGroupView.swift).
// The groups, sections and rows come from registries settings, in its order. The web shows only the
// rows that work in a browser: url rows (pages on bipe.chat) and Sair deste aparelho. A group with no
// such row is left out. The root rows are the iPhone Settings layout: a 29 px accent.fill tile with an
// 18 px ink.onAccent glyph, the title and a chevron.

import { useEffect, useState } from 'react';
import { useClient } from './media';
import { caretRight, chatCircle, bell, info, lockSimple, userCircle } from './phosphor/icons';
import { settings } from './registries';
import { forgetSession } from './sdk';
import type { StringKey } from './bipe/generated/strings';
import { Avatar } from './ui/Avatar';
import { locale, s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { NavBar, Row, Section } from './ui/List';

type Group = (typeof settings.groups)[number];
type SettingsRow = Group['sections'][number]['rows'][number];

// The root group icons of registries settings (Phosphor names).
const ICONS: Record<string, string> = {
  'lock-simple': lockSimple,
  'user-circle': userCircle,
  bell,
  'chat-circle': chatCircle,
  info,
};

const key = (k: string) => k as StringKey;

function webRow(row: SettingsRow): boolean {
  return row.kind === 'url' || row.key === 'signOut';
}

function webSections(group: Group) {
  return group.sections
    .map((section) => ({ ...section, rows: section.rows.filter(webRow) }))
    .filter((section) => section.rows.length > 0);
}

function urlOf(name: string): string | undefined {
  const urls = settings.urls as Record<string, { pt: string; en: string }>;
  return urls[name]?.[locale === 'en' ? 'en' : 'pt'];
}

export function Settings({ onClose }: { onClose: () => void }) {
  const client = useClient();
  const [open, setOpen] = useState<Group>();
  const [me, setMe] = useState<{ name: string; avatarUrl?: string }>({ name: client.userId() });
  useEffect(() => {
    let stopped = false;
    Promise.all([client.displayName().catch(() => undefined), client.avatarUrl().catch(() => undefined)]).then(
      ([name, avatarUrl]) => !stopped && setMe({ name: name ?? client.userId(), avatarUrl }),
    );
    return () => {
      stopped = true;
    };
  }, [client]);

  const groups = settings.root
    .map((k) => settings.groups.find((g) => g.key === k))
    .filter((g): g is Group => g !== undefined && webSections(g).length > 0);

  if (open) return <GroupScreen group={open} onBack={() => setOpen(undefined)} />;
  return (
    <div className="flex h-full flex-col bg-grouped-screen">
      <NavBar title={s('bipe_tab_you')} onBack={onClose} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pb-6">
        <div className="flex flex-col items-center gap-1 py-2 text-center">
          <Avatar name={me.name} size={96} mxcUrl={me.avatarUrl} />
          <h3 className="mt-2 text-title2 text-ink-primary">{me.name}</h3>
        </div>
        <Section header={s('bipe_settings_section')}>
          {groups.map((g) => (
            <li key={g.key}>
              <button
                type="button"
                className="flex w-full items-center gap-3 pl-4 text-left hover:bg-surface-sunken"
                onClick={() => setOpen(g)}
              >
                <span
                  aria-hidden
                  className="grid size-[29px] shrink-0 place-items-center rounded-[7px] bg-accent-fill text-ink-on-accent"
                >
                  {g.icon && ICONS[g.icon] && <Icon path={ICONS[g.icon]} size={18} />}
                </span>
                <span className="flex min-h-11 min-w-0 flex-1 items-center gap-2 pr-4 [li:not(:last-child)_&]:border-b-[0.5px] [li:not(:last-child)_&]:border-line-subtle">
                  <span className="min-w-0 flex-1 truncate text-body text-ink-primary">{s(key(g.title))}</span>
                  <Icon path={caretRight} size={16} className="text-glyph-secondary" />
                </span>
              </button>
            </li>
          ))}
        </Section>
      </div>
    </div>
  );
}

function GroupScreen({ group, onBack }: { group: Group; onBack: () => void }) {
  const client = useClient();
  return (
    <div className="flex h-full flex-col bg-grouped-screen">
      <NavBar title={s(key(group.title))} onBack={onBack} />
      <div className="mx-auto flex w-full max-w-[640px] min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pt-2 pb-6">
        {webSections(group).map((section) => (
          <Section
            key={section.key}
            header={'title' in section && section.title ? s(key(section.title)) : undefined}
            footer={'footer' in section && section.footer ? s(key(section.footer)) : undefined}
          >
            {section.rows.map((row) =>
              row.kind === 'url' ? (
                <Row key={row.key}>
                  <a
                    className="min-w-0 flex-1 py-1 text-body text-ink-primary"
                    href={'url' in row && row.url ? urlOf(row.url) : undefined}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {s(key(row.title))}
                  </a>
                </Row>
              ) : (
                <Row key={row.key}>
                  <button
                    type="button"
                    className="min-w-0 flex-1 py-1 text-left text-body text-trust-danger-ink"
                    onClick={async () => {
                      await client.logout().catch(() => undefined);
                      await forgetSession();
                      window.location.reload();
                    }}
                  >
                    {s(key(row.title))}
                  </button>
                </Row>
              ),
            )}
          </Section>
        ))}
      </div>
    </div>
  );
}

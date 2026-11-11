// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Source-only resource generation, never a compiler. Uses the canonical solver rather than another colour policy.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { REPO_ROOT, isMain } from './lib.mjs';
import { version } from './version.mjs';
import { windowsOAuthConfig } from './windows-oauth.mjs';

export async function desktopCatalog(sharedRoot) {
  const read = (path) => JSON.parse(readFileSync(join(sharedRoot, path), 'utf8'));
  const tokens = read('design/tokens.json');
  const patterns = read('registries/scam-patterns.json');
  const warningKinds = read('product/screens/scam-warning.json').types.ScamWarning.kind.values;
  const settings = read('registries/settings.json');
  const { fruitTile, svgPath } = await import(pathToFileURL(join(sharedRoot, 'design/chat-fruits.mjs')).href);
  const fruit = fruitTile();
  const { resolveThemes } = await import(pathToFileURL(join(sharedRoot, 'tools/lib/gen-tokens.mjs')).href);
  return {
    schema: 1,
    strings: { 'pt-BR': read('strings/pt-BR.json').strings, en: read('strings/en.json').strings },
    themes: resolveThemes(tokens),
    defaultPalette: tokens.appThemes.default,
    backgrounds: tokens.appThemes.chatBackground,
    mark: tokens.appThemes.icon,
    // The default + order come from the same registry as every other client's picker.
    palettes: Object.keys(tokens.appThemes.palettes),
    syncedSettings: read('registries/synced-settings.json'),
    settings,
    appearance: read('product/screens/settings-group.json').strings
      .filter(key => key.startsWith('bipe_chats_appearance_'))
      .map(title => ({ id: title.replace('bipe_chats_appearance_', ''), title: title.endsWith('_system') ? 'bipe_web_appearance_system' : title })),
    fruit: { ...fruit, glyphs: fruit.glyphs.map(glyph => ({ ...glyph,
      bodies: glyph.bodies.map(svgPath), lines: glyph.lines.map(svgPath), dots: glyph.dots.map(svgPath) })) },
    safety: {
      warnings: warningKinds.map(id => ({ id, messageKey: `bipe_scam_warning_${id.replace(/[A-Z]/g, c => `_${c.toLowerCase()}`)}` })),
      moneyPauseKinds: patterns.moneyPauseKinds,
      moneyPauseSeconds: patterns.moneyPauseSeconds,
    },
  };
}

export async function writeDesktopResources(output, sharedRoot = resolve(REPO_ROOT, '../bipe-shared')) {
  const catalog = await desktopCatalog(sharedRoot);
  mkdirSync(output, { recursive: true });
  writeFileSync(join(output, 'bipe-desktop.json'), `${JSON.stringify(catalog)}\n`);
  writeFileSync(join(output, 'bipe-build.json'), `${JSON.stringify(await version(REPO_ROOT))}\n`);
  const registration = windowsOAuthConfig();
  writeFileSync(join(output, 'bipe-config.json'), `${JSON.stringify({ windowsOAuthClientId: registration.clientId })}\n`);
  // Existing generated Bipe icon, copied unchanged; packaging does not draw a new Windows identity.
  const icon = readFileSync(join(REPO_ROOT, 'ios/BipeKit/Sources/BipeKit/Generated/AppIcons.xcassets/BipePalette-acai.imageset/icon.png'));
  writeFileSync(join(output, 'bipe-icon.png'), icon);
  for (const palette of catalog.palettes) {
    const source = join(REPO_ROOT, `ios/BipeKit/Sources/BipeKit/Generated/AppIcons.xcassets/BipePalette-${palette}.imageset/icon.png`);
    writeFileSync(join(output, `bipe-icon-${palette}.png`), readFileSync(source));
  }
  const phosphor = join(REPO_ROOT, 'ios/Phosphor');
  writeFileSync(join(output, 'warning-circle.svg'), readFileSync(join(phosphor, 'Sources/Phosphor/Resources/Phosphor.xcassets/warning-circle.imageset/warning-circle.svg')));
  for (const id of [...new Set([...catalog.settings.groups.map(group => group.icon).filter(Boolean), 'caret-left', 'caret-right', 'check'])]) {
    writeFileSync(join(output, `phosphor-${id}.svg`), readFileSync(join(phosphor, `Sources/Phosphor/Resources/Phosphor.xcassets/${id}.imageset/${id}.svg`)));
  }
  writeFileSync(join(output, 'open-source-licences.json'), readFileSync(join(REPO_ROOT, 'android/app/src/main/assets/open_source_licences.json')));
  writeFileSync(join(output, 'phosphor-LICENSE.txt'), readFileSync(join(phosphor, 'LICENSE')));
}

if (isMain(import.meta.url)) {
  if (!process.argv[2]) throw new Error('Usage: node tools/desktop-resources.mjs <output-directory>');
  await writeDesktopResources(resolve(process.argv[2]), process.env.BIPE_SHARED || undefined);
  console.log('desktop-resources: wrote canonical catalogues, icon and shared version/build');
}

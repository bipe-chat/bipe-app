// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Tests for the settings row handler check (bipe-chat/bipe#676). Run: node --test tools/*.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { kotlinName, missingHandlers, parseRows } from './settings-rows.mjs';

const SWIFT = `
public enum BipeSettingsRow: String {
    case chatsAppearance = "chats.appearance"
    case chatsLanguage = "chats.language"
    case helpDocs = "help.docs"
    case chatsSoon = "chats.soon"

    public var kind: BipeSettingsRowKind {
        switch self {
        case .chatsAppearance: .choice
        case .chatsLanguage: .action
        case .helpDocs: .url
        case .chatsSoon: .link
        }
    }

    public var gate: String? {
        switch self {
        case .chatsAppearance: nil
        case .chatsLanguage: "ios"
        case .helpDocs: nil
        case .chatsSoon: "planned:1"
        }
    }
}
`;

test('the generated rows are read with kind and gate', () => {
  assert.deepEqual(parseRows(SWIFT), [
    { name: 'chatsAppearance', kind: 'choice', gate: null },
    { name: 'chatsLanguage', kind: 'action', gate: 'ios' },
    { name: 'helpDocs', kind: 'url', gate: null },
    { name: 'chatsSoon', kind: 'link', gate: 'planned:1' },
  ]);
});

test('a row without a handler fails on the platform that lacks it', () => {
  const rows = parseRows(SWIFT);
  assert.deepEqual(missingHandlers(rows, 'case .chatsLanguage:', ''), ['ios chatsAppearance', 'android CHATS_APPEARANCE']);
  assert.deepEqual(missingHandlers(rows, 'case .chatsAppearance: x\ncase .chatsLanguage:', 'BipeSettingsRow.CHATS_APPEARANCE -> x'), []);
});

test('url rows, planned rows and the other platform need no handler', () => {
  const rows = parseRows(SWIFT);
  const missing = missingHandlers(rows, '.chatsAppearance .chatsLanguage', 'BipeSettingsRow.CHATS_APPEARANCE');
  assert.deepEqual(missing, []);
  assert.equal(kotlinName('chatsMediaQuality'), 'CHATS_MEDIA_QUALITY');
});

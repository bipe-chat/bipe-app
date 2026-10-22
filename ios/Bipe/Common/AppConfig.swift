// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Build configuration of the tester app (ADR 0147). The user never picks a server (spec account-start).
enum AppConfig {
    /// Bipe's homeserver (bipe/docs/server-migration.md).
    static let homeserver = "https://matrix.bipe.net.br"

    /// The app group of the tester app. The SDK store and caches live there.
    static let appGroup = "group.chat.bipe.next"

    /// The keychain access group in which the fork's tester build keeps the device account (ADR 0147).
    /// The app only reads from it. Team 992N457T8D (bipe/docs/server-migration.md), app ID chat.bipe.app.dev.
    static let forkKeychainGroup = "992N457T8D.chat.bipe.app.dev"

    /// The device name the homeserver shows in the session list.
    static let deviceName = "Bipe"
}

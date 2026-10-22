// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Device-account discovery uses BipeCredentialStore's own-first reads and sign-out exclusions (ADR 0147).
struct DeviceCredential: Sendable, CustomStringConvertible {
    let userId: String
    let password: String
    let created: Date

    var description: String { "DeviceCredential(\(userId))" }

    /// Oldest account first, with the own credential winning over a legacy duplicate (spec account-start).
    static func all() -> [DeviceCredential] {
        BipeCredentialStore().passwordsWithCreationDates()
            .map { DeviceCredential(userId: $0.item.userID, password: $0.item.secret, created: $0.created) }
            .sorted { $0.created < $1.created }
    }
}

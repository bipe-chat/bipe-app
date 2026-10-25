// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Official Bipe accounts (ADR 0117): the Bipe badge shows only for these user IDs.
public enum BipeOfficialAccounts {
    /// An exact match of the full Matrix user ID. A display name, a handle or the same localpart on another server is not proof.
    public static func isOfficial(_ userId: String?) -> Bool {
        guard let userId else { return false }
        return BipeRegistries.officialAccountIDs.contains(userId)
    }
}

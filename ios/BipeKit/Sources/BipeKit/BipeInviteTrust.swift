// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// ADR 0034: the API's invite pair skips Pedidos for that DM only. Blocking still wins.
public nonisolated enum BipeInviteTrust {
    public static func acceptsAutomatically(isDm: Bool, inviterID: String, isIgnored: Bool, pairs: Set<String>) -> Bool {
        isDm && !isIgnored && !inviterID.isEmpty && pairs.contains(inviterID)
    }
}

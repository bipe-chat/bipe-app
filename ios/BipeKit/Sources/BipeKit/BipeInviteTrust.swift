// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// ADR 0034: the API's invite pair skips Pedidos for that DM only. An official account (exact user ID) skips Pedidos
/// and the group invites for any room (bipe-chat/bipe#978). Blocking still wins.
public nonisolated enum BipeInviteTrust {
    public static func acceptsAutomatically(isDm: Bool, inviterID: String, isIgnored: Bool, pairs: Set<String>) -> Bool {
        guard !isIgnored, !inviterID.isEmpty else { return false }
        return BipeOfficialAccounts.isOfficial(inviterID) || (isDm && pairs.contains(inviterID))
    }

    /// Spec chat-requests: a DM invite. RoomInfo.isDm reads m.direct, which the invitee writes only when joining. The
    /// invite's own is_direct and at most two people decide, service members not counted.
    public static func isDirectInvite<Count: BinaryInteger>(isDirect: Bool, activeMembers: Count, serviceMembers: Count) -> Bool {
        isDirect && activeMembers - min(serviceMembers, activeMembers) <= 2
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.invites

import chat.bipe.shared.BipeOfficialAccounts

/**
 * ADR 0034: the API's invite pair skips Pedidos for that DM only. An official account (exact user ID) skips Pedidos
 * and the group invites for any room (bipe-chat/bipe#978). Blocking still wins.
 */
object BipeInviteTrust {
    fun acceptsAutomatically(isDm: Boolean, inviterId: String, isIgnored: Boolean, pairs: Set<String>): Boolean =
        !isIgnored && inviterId.isNotEmpty() && (BipeOfficialAccounts.isOfficial(inviterId) || (isDm && inviterId in pairs))
}

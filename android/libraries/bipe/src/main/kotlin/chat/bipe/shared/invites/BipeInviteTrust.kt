// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.invites

/** ADR 0034: the API's invite pair skips Pedidos for that DM only. Blocking still wins. */
object BipeInviteTrust {
    fun acceptsAutomatically(isDm: Boolean, inviterId: String, isIgnored: Boolean, pairs: Set<String>): Boolean =
        isDm && !isIgnored && inviterId.isNotEmpty() && inviterId in pairs
}

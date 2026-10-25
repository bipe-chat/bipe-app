// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries

/** Official Bipe accounts (ADR 0117): the Bipe badge shows only for these user IDs. */
object BipeOfficialAccounts {
    /** An exact match of the full Matrix user ID. A display name, a handle or the same localpart on another server is not proof. */
    fun isOfficial(userId: String?): Boolean = userId != null && userId in BipeRegistries.officialAccountIds
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Personal accounts on one phone (ADR 0086). Company accounts do not count (#269). The iOS counterpart is `BipeAccountCap`
 * in BipeKit.
 */
object BipeAccountCap {
    private val live = MutableStateFlow(BipeRegistries.MAX_PERSONAL_ACCOUNTS)

    /** The last `max_accounts` any account read from `GET /v1/me/account`, else the shared registry value. */
    val maxPersonalAccounts: StateFlow<Int> = live.asStateFlow()

    /** [maxAccounts] is `null` from an older server: the registry value stays. */
    fun onServerValue(maxAccounts: Int?) {
        if (maxAccounts != null && maxAccounts > 0) live.value = maxAccounts
    }

    fun canAdd(personalAccounts: Int, max: Int = live.value): Boolean = personalAccounts < max
}

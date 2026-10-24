// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import chat.bipe.shared.account.BipeRecoveryTokenSync

/** No Google Play services in this build: the recovery token stays on this phone (BipeAccountVault). */
object RecoveryTokenSyncs {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): BipeRecoveryTokenSync = BipeRecoveryTokenSync.None
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import chat.bipe.shared.account.BipeAgeSignalSource

/** No Google Play services in this build: no OS age signal, so the server treats the account as unknown (ADR 0229). */
object AgeSignalSources {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): BipeAgeSignalSource = BipeAgeSignalSource.None
}

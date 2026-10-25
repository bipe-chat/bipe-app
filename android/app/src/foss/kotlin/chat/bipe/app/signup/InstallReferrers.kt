// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import android.content.Context
import chat.bipe.shared.BipeInstallReferrer

/** No Google Play services in this build: the invitee taps "Colar convite" (ADR 0154). */
object InstallReferrers {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): BipeInstallReferrer = BipeInstallReferrer.None
}

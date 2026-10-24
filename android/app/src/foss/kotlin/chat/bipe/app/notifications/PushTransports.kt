// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import chat.bipe.app.BipeApplication

/** No Google Play services in this build, so no push transport (a later UnifiedPush transport goes here). */
object PushTransports {
    @Suppress("UNUSED_PARAMETER")
    fun create(app: BipeApplication): PushTransport? = null
}

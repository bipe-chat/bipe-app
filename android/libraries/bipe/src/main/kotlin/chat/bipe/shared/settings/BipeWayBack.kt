// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.settings

import chat.bipe.shared.api.BipeAccountStatus

/**
 * The ways back into the account that this phone can see (ADR 0112, Sua conta; paths in bipe/docs/security.md).
 * Linked Apple and Google accounts (#307) and guardians (ADR 0044) are not built on Android, so they never count here.
 */
enum class BipeWayBack {
    /** A confirmed recovery email. A pending address does not count. */
    EMAIL,

    /** The Matrix recovery key is set up. It also brings old messages back (ADR 0073). */
    RECOVERY_KEY,

    /** The server has a recovery token and this phone keeps it in the synced store (Block Store, ADR 0059). */
    SYNCED_TOKEN,
    ;

    companion object {
        fun of(status: BipeAccountStatus, recoveryKeyEnabled: Boolean, syncedTokenPresent: Boolean): List<BipeWayBack> = buildList {
            if (status.email != null) add(EMAIL)
            if (recoveryKeyEnabled) add(RECOVERY_KEY)
            if (status.recoveryTokenSet && syncedTokenPresent) add(SYNCED_TOKEN)
        }

        /** One way back or none: the only warning in Settings, on the "Sua conta" row and the Você tab. `null` is not loaded. */
        fun needsWarning(count: Int?): Boolean = count != null && count <= 1
    }
}

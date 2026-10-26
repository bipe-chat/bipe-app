// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries

/**
 * The "is a Bipe user" rule (ADR 0168, bipe-chat/bipe#680): the user ID's server name is in registries/bipe-servers.json.
 * Every check uses this; never compare a user ID with a server suffix. Cases: bipe-shared test-vectors/bipe-servers.json.
 * Same rule as BipeKit `BipeServers` on iOS.
 */
object BipeServers {
    /**
     * True for "@" + a non-empty localpart + ":" + a server name in [servers], exactly. The server name is everything
     * after the first ":", so a port is part of it.
     */
    fun isBipeUserId(userId: String, servers: Set<String> = BipeRegistries.bipeServers): Boolean {
        if (!userId.startsWith("@")) return false
        val colon = userId.indexOf(':')
        if (colon < 2) return false
        return userId.substring(colon + 1) in servers
    }
}

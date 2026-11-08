// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.userIdServer

/**
 * The "is a Bipe user" rule (ADR 0168, bipe-chat/bipe#680, #645): the user ID's server name is a valid operator of the
 * current signed operator list ([SignedLists]). Every check uses this; never compare a user ID with a server suffix.
 * Cases: bipe-shared test-vectors/bipe-servers.json. Same rule as BipeKit `BipeServers` on iOS.
 */
object BipeServers {
    /**
     * True for "@" + a non-empty localpart + ":" + a server name that the current operator list lists as valid. The
     * server name is everything after the first ":", so a port is part of it. The match is exact: list entries are lower
     * case, so a server name with upper case letters is not a Bipe server (test-vectors/bipe-servers.json).
     */
    fun isBipeUserId(userId: String, lists: SignedLists = SignedLists.shared): Boolean {
        val server = userIdServer(userId) ?: return false
        if (server != server.lowercase()) return false
        return lists.isBipeServer(server)
    }
}

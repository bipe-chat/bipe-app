// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.util.UUID

/** Public registration lifetime codes only. Persistence is supplied by the platform; tests use synthetic maps. */
class BipePushAccountRoutes(private val read: () -> Map<String, String>, private val write: (Map<String, String>) -> Unit) {
    @Synchronized fun routeFor(userId: String): String {
        val routes = read()
        val kept = routes[userId]
        if (kept != null && valid(kept) && routes.values.count { it == kept } == 1) return kept
        val route = UUID.randomUUID().toString()
        write(routes + (userId to route))
        return route
    }

    @Synchronized fun accountFor(route: String): String? {
        if (!valid(route)) return null
        return read().filterValues { it == route }.keys.singleOrNull()
    }

    @Synchronized fun remove(userId: String) {
        write(read() - userId)
    }

    private fun valid(route: String) = runCatching { UUID.fromString(route).toString() == route }.getOrDefault(false)
}

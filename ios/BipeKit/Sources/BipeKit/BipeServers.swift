// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The "is a Bipe user" rule (ADR 0168, bipe-chat/bipe#680): the user ID's server name is in registries/bipe-servers.json.
/// Every check uses this; never compare a user ID with a server suffix. Cases: bipe-shared test-vectors/bipe-servers.json.
public enum BipeServers {
    /// `true` for "@" + a non-empty localpart + ":" + a server name in `servers`, exactly. The server name is everything
    /// after the first ":", so a port is part of it.
    public static func isBipeUserID(_ userID: String, servers: Set<String> = BipeRegistries.bipeServers) -> Bool {
        guard userID.hasPrefix("@"), let colon = userID.firstIndex(of: ":"),
              userID.distance(from: userID.startIndex, to: colon) >= 2 else { return false }
        return servers.contains(String(userID[userID.index(after: colon)...]))
    }
}

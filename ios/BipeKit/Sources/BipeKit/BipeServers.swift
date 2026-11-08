// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// The "is a Bipe user" rule (ADR 0168, bipe-chat/bipe#680, #645): the user ID's server name is a valid operator of the
/// current signed operator list (``SignedLists``). Every check uses this; never compare a user ID with a server suffix.
/// Cases: bipe-shared test-vectors/bipe-servers.json.
public enum BipeServers {
    /// `true` for "@" + a non-empty localpart + ":" + a server name that the current operator list lists as valid. The
    /// server name is everything after the first ":", so a port is part of it. The match is exact: list entries are lower
    /// case, so a server name with upper case letters is not a Bipe server (test-vectors/bipe-servers.json).
    public static func isBipeUserID(_ userID: String, lists: SignedLists = .shared) -> Bool {
        guard let server = userIdServer(userId: userID), server == server.lowercased() else { return false }
        return lists.isBipeServer(server)
    }
}

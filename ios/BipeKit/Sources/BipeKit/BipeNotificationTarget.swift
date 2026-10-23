// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation

/// A notification belongs to one account and one room, even when several accounts join the same room.
public struct BipeNotificationTarget: Hashable, Sendable {
    public let accountUserId: String
    public let roomId: String

    /// Missing, malformed or legacy owners never fall back to a different account.
    public init?(accountUserId: String?, roomId: String?) {
        guard let accountUserId, let roomId,
              accountUserId.hasPrefix("@"), let colon = accountUserId.firstIndex(of: ":"),
              colon > accountUserId.index(after: accountUserId.startIndex),
              accountUserId.index(after: colon) < accountUserId.endIndex,
              roomId.hasPrefix("!"), roomId.count > 1,
              !accountUserId.contains(where: { $0.isWhitespace || $0.isNewline || $0 == "\0" }),
              !roomId.contains(where: { $0.isWhitespace || $0.isNewline || $0 == "\0" }) else { return nil }
        self.accountUserId = accountUserId
        self.roomId = roomId
    }

    /// Opaque platform grouping key; length prefixes keep account/room boundaries unambiguous.
    public var identifier: String {
        let value = "bipe.notification.v1|\(accountUserId.utf8.count):\(accountUserId)|\(roomId.utf8.count):\(roomId)"
        return SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

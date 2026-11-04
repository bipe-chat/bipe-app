// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Contract: `NotificationPrefs`. Which join pushes the account gets (bipe-chat/bipe#967, spec notification-settings).
/// The API reads them before it sends invite_joined or group_link_joined, so they apply on every device of the account.
public struct BipeNotificationPrefs: Codable, Sendable, Equatable {
    public let inviteJoined: Bool
    public let groupLinkJoined: Bool

    public init(inviteJoined: Bool, groupLinkJoined: Bool) {
        self.inviteJoined = inviteJoined
        self.groupLinkJoined = groupLinkJoined
    }
}

public extension BipeAPIClient {
    /// `GET /v1/me/notification-prefs`. No saved prefs: both true.
    func notificationPrefs(accessToken: String) async throws(BipeAPIError) -> BipeNotificationPrefs {
        try await send(method: "GET", path: ["v1", "me", "notification-prefs"], body: Empty?.none, accessToken: accessToken)
    }

    /// `PUT /v1/me/notification-prefs`.
    func setNotificationPrefs(_ prefs: BipeNotificationPrefs, accessToken: String) async throws(BipeAPIError) -> BipeNotificationPrefs {
        try await send(method: "PUT", path: ["v1", "me", "notification-prefs"], body: prefs, accessToken: accessToken)
    }
}

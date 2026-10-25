// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Keeps this phone's copy of a work account's opening hours (spec work-hours notes, bipe-chat/bipe#511), so the
/// notification service extension decides without a request. A personal account answers 404: no copy, it rings.
@MainActor
enum WorkHoursSync {
    @discardableResult
    static func refresh(session: AccountSession, api: BipeAPIClient) async -> BipeWorkHours? {
        do {
            let hours = try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeWorkHours in
                try await api.workHours(accessToken: token)
            }
            NotificationPrefs.setWorkHours(hours, for: session.userId)
            return hours
        } catch {
            if case .server(.notFound, _, _) = error {
                NotificationPrefs.setWorkHours(nil, for: session.userId)
            }
            // A network error keeps the last copy.
            return NotificationPrefs.workHours(for: session.userId)
        }
    }

    /// After a save on this phone.
    static func store(_ hours: BipeWorkHours, userId: String) {
        NotificationPrefs.setWorkHours(hours, for: userId)
    }
}

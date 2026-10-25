// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Theft protection (bipe-chat/bipe#268): every open account sends this phone's random ID once per app start
/// (PUT /v1/me/phone), so a theft mark covers the other accounts on the phone.
enum PhoneReport {
    /// The ID is kept in the app group, so every account and extension sees the same value.
    static var phoneId: String {
        BipePhoneID.current(in: UserDefaults(suiteName: AppConfig.appGroup) ?? .standard)
    }

    /// Failures are ignored: the next start tries again.
    static func report(_ session: AccountSession, api: BipeAPIClient) async {
        let id = phoneId
        _ = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) in
            try await api.reportPhone(phoneID: id, accessToken: token)
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// The server copy of a global account data event, not Client.accountData: the SDK copy waits for the next sync, so a
/// read-merge-write from it could drop what another device just added (bipe-chat/bipe#479). Matrix client-server API
/// GET user account data.
enum ServerAccountData {
    struct Failed: Error {}

    /// The event content as JSON, or nil when the account has no such event.
    static func json(client: Client, eventType: String) async throws -> String? {
        let auth = try client.session()
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/@:"))
        let user = auth.userId.addingPercentEncoding(withAllowedCharacters: allowed) ?? auth.userId
        let type = eventType.addingPercentEncoding(withAllowedCharacters: allowed) ?? eventType
        let base = auth.homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(base)/_matrix/client/v3/user/\(user)/account_data/\(type)") else { throw Failed() }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(auth.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        switch (response as? HTTPURLResponse)?.statusCode {
        case 200: return String(data: data, encoding: .utf8)
        case 404: return nil
        default: throw Failed()
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// The full room state from the server (bipe-chat/bipe#439, spec chat-timeline). The SDK has no call that returns a
/// custom state event such as the assistant reader marker, so the app reads it with the Matrix client-server API
/// GET /_matrix/client/v3/rooms/{roomId}/state.
enum ServerRoomState {
    struct Failed: Error {}

    /// The response body: a JSON array of state events. Throws on any failure.
    static func json(client: Client, roomId: String) async throws -> Data {
        let auth = try client.session()
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/@:!?#"))
        let room = roomId.addingPercentEncoding(withAllowedCharacters: allowed) ?? roomId
        let base = auth.homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(base)/_matrix/client/v3/rooms/\(room)/state") else { throw Failed() }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(auth.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw Failed() }
        return data
    }
}

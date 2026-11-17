// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Matrix client-server GET /_matrix/client/v1/rooms/{roomId}/timestamp_to_event (MSC3030): the first event at or
/// after a time. matrix-sdk-ffi has no call for it, so the app asks the homeserver with the session's token. iOS and
/// the Mac use it.
enum TimestampToEvent {
    struct Failed: Error {}

    private struct Response: Decodable {
        let eventId: String
        let originServerTs: UInt64

        enum CodingKeys: String, CodingKey {
            case eventId = "event_id"
            case originServerTs = "origin_server_ts"
        }
    }

    /// The first event at or after `time` (dir f), or nil when the room has none.
    static func first(client: Client, roomId: String, onOrAfter time: Date) async throws -> (eventId: String, sentAt: Date)? {
        let auth = try client.session()
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/@:!#?&"))
        let room = roomId.addingPercentEncoding(withAllowedCharacters: allowed) ?? roomId
        let base = auth.homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let ts = UInt64(max(0, time.timeIntervalSince1970 * 1000))
        guard let url = URL(string: "\(base)/_matrix/client/v1/rooms/\(room)/timestamp_to_event?ts=\(ts)&dir=f") else {
            throw Failed()
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(auth.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        switch (response as? HTTPURLResponse)?.statusCode {
        case 200:
            let found = try JSONDecoder().decode(Response.self, from: data)
            return (found.eventId, Date(timeIntervalSince1970: Double(found.originServerTs) / 1000))
        case 404:
            return nil
        default:
            throw Failed()
        }
    }
}

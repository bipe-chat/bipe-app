// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Spec dm-delete action delete: the write of chat.bipe.cleared_chats (registries/cleared-chats.json).
/// Also compiled into the Mac app (macos/project.yml): keep it free of app-only types.
enum DmClear {
    enum Failure: Error { case notJoinedDm }

    /// Read-merge-write of the server copy of chat.bipe.cleared_chats for one joined DM. Returns the marker, the
    /// origin_server_ts of the latest remote event. The room is not left, so the other person's next message brings it back.
    static func clear(client: Client, room: Room) async throws -> UInt64 {
        let info = try await room.roomInfo()
        guard info.isDm, info.membership == .joined else { throw Failure.notJoinedDm }
        let ts = await latestTs(room)
        let content = ClearedChats.content(clearing: try await serverContent(client), roomID: room.id(), at: ts)
        let data = try JSONSerialization.data(withJSONObject: content)
        try await client.setAccountData(eventType: ClearedChats.eventType, content: String(decoding: data, as: UTF8.self))
        return ts
    }

    /// The origin_server_ts of the latest remote event (Room.latestEvent).
    static func latestTs(_ room: Room) async -> UInt64 {
        switch await room.latestEvent() {
        case .remote(let timestamp, _, _, _, _), .local(let timestamp, _, _, _, _), .remoteInvite(let timestamp, _, _):
            return timestamp
        case .none:
            return UInt64(Date().timeIntervalSince1970 * 1000)
        }
    }

    /// The server copy of chat.bipe.cleared_chats (ServerAccountData, bipe-chat/bipe#479).
    private static func serverContent(_ client: Client) async throws -> [String: Any]? {
        guard let json = try await ServerAccountData.json(client: client, eventType: ClearedChats.eventType) else { return nil }
        return (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Bipe's self-chat protocol (ADR 0053): an encrypted room with only its owner, saved in global account data.
/// The wire key and content match the Bipe-owned BipeSelfChat in libraries/bipe, without its Element adapters.
@MainActor
final class NotesRoom {
    private struct Content: Codable {
        let room_id: String
    }
    private enum Failure: Error { case unavailable }
    private static let eventType = "chat.bipe.self_chat"
    private let session: AccountSession
    private let pendingKey: String
    private var opening = false
    private(set) var roomId: String?

    init(session: AccountSession) {
        self.session = session
        pendingKey = "bipe.next.notes.pending.\(session.userId)"
        roomId = UserDefaults.standard.string(forKey: pendingKey)
    }

    func refresh() async throws {
        guard let json = try await session.client.accountData(eventType: Self.eventType) else { return }
        let id = try JSONDecoder().decode(Content.self, from: Data(json.utf8)).room_id
        guard id.hasPrefix("!"), !id.contains(where: \.isWhitespace) else { throw Failure.unavailable }
        roomId = id
    }

    func open() async throws -> String {
        guard !opening else { throw Failure.unavailable }
        opening = true
        defer { opening = false }
        // A failed or malformed read must never be interpreted as "no notes yet".
        try await refresh()
        let pending = UserDefaults.standard.string(forKey: pendingKey)
        if let roomId, roomId != pending {
            // A room missing from the local store may still be syncing. Keep its identity; never replace it.
            if (try? session.client.getRoom(roomId: roomId)) == nil {
                _ = try await session.client.awaitRoomRemoteEcho(roomId: roomId)
            }
            guard let room = try? session.client.getRoom(roomId: roomId),
                  try await room.roomInfo().membership == .joined else { throw Failure.unavailable }
            return roomId
        }
        let id: String
        if let pending {
            id = pending
        } else {
            id = try await session.client.createRoom(request: CreateRoomParameters(
                name: BipeStrings.selfChatTitle, isEncrypted: true, isDirect: false,
                visibility: .private, preset: .privateChat, invite: nil))
            // If writing account data fails, the next attempt finishes that write instead of creating another room.
            UserDefaults.standard.set(id, forKey: pendingKey)
        }
        roomId = id
        let content = try JSONEncoder().encode(Content(room_id: id))
        try await session.client.setAccountData(eventType: Self.eventType, content: String(decoding: content, as: UTF8.self))
        UserDefaults.standard.removeObject(forKey: pendingKey)
        _ = try await session.client.awaitRoomRemoteEcho(roomId: id)
        return id
    }
}

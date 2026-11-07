// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// SDK adapter for the generated ADR 0141 rule. Reads only this account's local rooms/account data.
@MainActor
enum NativeDmSelection {
    static func visible(_ chats: [ChatSummary], session: AccountSession) async -> [ChatSummary] {
        let peers = Set(chats.filter(\.isDm).compactMap(\.peerUserId))
        guard !peers.isEmpty else { return chats }
        do {
            let selected = BipeDmSelection.newestRooms(try await candidates(peers: peers, session: session))
            return chats.filter { chat in
                guard chat.isDm, let peer = chat.peerUserId else { return true }
                return selected[peer] == chat.roomId
            }
        } catch {
            // A local read error must not make the list disappear. A later SDK update retries selection.
            return chats
        }
    }

    /// Start chat reuses an existing room even when every DM was cleared; clear markers still hide old events.
    /// Errors propagate so unreadable local state can never be mistaken for permission to create another DM.
    static func roomId(with peer: String, session: AccountSession) async throws -> String? {
        let candidates = try await candidates(peers: [peer], session: session)
        return BipeDmSelection.newestRooms(candidates)[peer]
            ?? BipeDmSelection.newestRooms(candidates, includingCleared: true)[peer]
    }

    private static func candidates(peers: Set<String>, session: AccountSession) async throws -> [BipeDmCandidate] {
        let stored = ClearedChats(json: try await session.client.accountData(eventType: ClearedChats.eventType))
        let cleared = ClearedChatsStore.shared.overlay(stored, userId: session.userId)
        return try await NativeDm.candidates(peers: peers, client: session.client, cleared: cleared)
    }
}

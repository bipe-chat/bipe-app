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
        let cleared = ClearedChats(json: try await session.client.accountData(eventType: ClearedChats.eventType))
        var result: [BipeDmCandidate] = []
        for peer in peers.sorted() {
            // getDmRooms is local and includes every joined DM for the peer, beyond the loaded/unread/search page.
            for room in try session.client.getDmRooms(userId: peer) {
                try Task.checkCancellation()
                let info = try await room.roomInfo()
                guard info.isDm, !info.isSpace, info.membership == .joined else { continue }
                let timestamp: UInt64?
                switch await room.latestEvent() {
                case .remote(let ts, _, _, _, _), .local(let ts, _, _, _, _), .remoteInvite(let ts, _, _): timestamp = ts
                case .none: timestamp = nil
                }
                result.append(BipeDmCandidate(roomId: info.id, peerUserId: peer,
                    latestActivityMs: timestamp.map { Int64(clamping: $0) }, eligible: true,
                    cleared: ClearedChats.isHidden(marker: cleared.marker(for: info.id), latestTs: timestamp)))
            }
        }
        return result
    }
}

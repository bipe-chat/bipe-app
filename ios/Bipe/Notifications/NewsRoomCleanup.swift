// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Novidades do Bipe is a named room, not a DM (bipe-chat/bipe#809, spec chat-list notes). bipe-api made news rooms
/// with is_direct before, so the account's m.direct lists them and the room took the DM name rule (ADR 0141).
/// Once per account on this phone, after the first sync: the news room ID comes from GET /v1/me/news (room_id), and
/// the app takes that room out of m.direct when it is there. A failure tries again at the next start or return to
/// the foreground.
@MainActor
enum NewsRoomCleanup {
    private static func doneKey(_ userId: String) -> String { "bipe.newsRoomNotDm.\(userId)" }
    private static var running: Set<String> = []

    static func applyOnce(session: AccountSession, api: BipeAPIClient) async {
        let defaults = UserDefaults.standard
        let userId = session.userId
        guard !defaults.bool(forKey: doneKey(userId)), running.insert(userId).inserted else { return }
        defer { running.remove(userId) }
        let state: BipeNewsState
        do {
            state = try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeNewsState in
                try await api.newsState(accessToken: token)
            }
        } catch {
            return
        }
        // No news room (news off): a room made later is not a DM.
        guard state.subscribed, let roomId = state.roomId else {
            defaults.set(true, forKey: doneKey(userId))
            return
        }
        // The room arrives with the first sync. An invite from before the fix says is_direct, and joining it adds it
        // to m.direct, so the cleanup runs only on a joined room.
        var joined = false
        for _ in 0..<10 {
            if (try? session.client.getRoom(roomId: roomId))?.membership() == .joined {
                joined = true
                break
            }
            if Task.isCancelled { return }
            try? await Task.sleep(for: .seconds(3))
        }
        guard joined else { return }
        do {
            let current = try await ServerAccountData.json(client: session.client, eventType: BipeNewsDirectCleanup.directEventType)
            if let content = BipeNewsDirectCleanup.json(removing: roomId, from: current) {
                try await session.client.setAccountData(eventType: BipeNewsDirectCleanup.directEventType, content: content)
            }
            defaults.set(true, forKey: doneKey(userId))
        } catch {
            // Tried again at the next start.
        }
    }
}

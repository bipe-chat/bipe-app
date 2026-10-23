// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Combine

/// One instance per signed-in account. No server mutation, history clearing or credential access.
@MainActor
final class ChatArchiveModel: ObservableObject {
    @Published private(set) var markers: [String: Int64] = [:]
    @Published private(set) var failed = false
    private let store: BipeChatArchiveStore
    private enum Failure: Error { case invalidTimestamp }
    private var pending: (ChatSummary, Bool)?

    init(accountId: String) {
        let directory = URL.applicationSupportDirectory.appendingPathComponent("ChatArchives", isDirectory: true)
        store = BipeChatArchiveStore(directory: directory, accountId: accountId)
        reload()
    }

    func contains(_ chat: ChatSummary) -> Bool { markers[chat.roomId] != nil }

    func set(_ chat: ChatSummary, archived: Bool) {
        pending = (chat, archived)
        do {
            var next = try store.load()
            if archived {
                let time = max(0, (chat.lastActivity?.timeIntervalSince1970 ?? 0) * 1000).rounded()
                guard time.isFinite, time <= 9_007_199_254_740_991 else { throw Failure.invalidTimestamp }
                next[chat.roomId] = Int64(time)
            }
            else { next[chat.roomId] = nil }
            try store.save(next)
            markers = next
            pending = nil
            failed = false
        } catch { failed = true }
    }

    func reconcile(_ chats: [ChatSummary]) {
        guard !failed else { return }
        var next = markers
        for chat in chats where next[chat.roomId] != nil {
            if !BipeChatArchive.isArchived(marker: next[chat.roomId], incoming: chat.latestIncomingMs) { next[chat.roomId] = nil }
        }
        guard next != markers else { return }
        do { try store.save(next); markers = next }
        catch { failed = true }
    }

    func retry(_ chats: [ChatSummary]) {
        reload()
        guard !failed else { return }
        if let (chat, archived) = pending { set(chat, archived: archived) }
        reconcile(chats)
    }

    private func reload() {
        do { markers = try store.load(); failed = false }
        catch { failed = true }
    }
}

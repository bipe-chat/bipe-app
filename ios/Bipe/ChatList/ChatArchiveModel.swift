// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Combine
import MatrixRustSDK

/// One instance per account. Uses SDK low-priority semantics (including removing favourites) and local baselines.
@MainActor
final class ChatArchiveModel: ObservableObject {
    @Published private(set) var markers: [String: Int64] = [:]
    @Published private(set) var failed = false
    private let store: BipeChatArchiveStore
    private let session: AccountSession
    private var syncedRooms: Set<String> = []
    private var latestChats: [ChatSummary] = []
    private struct Action { let roomId: String; let archived: Bool; let marker: Int64? }
    private struct Echo { let id: UUID; let archived: Bool }
    private enum Failure: Error { case invalidTimestamp, unavailable }
    private var failedActions: [String: Action] = [:]
    private var followUps: [String: Action] = [:]
    private var storageFailed = false
    private var queuedRooms: Set<String> = []
    private var echoes: [String: Echo] = [:]
    private var tail: Task<Void, Never>?
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var closed = false

    init(session: AccountSession) {
        self.session = session
        let directory = URL.applicationSupportDirectory.appendingPathComponent("ChatArchives", isDirectory: true)
        store = BipeChatArchiveStore(directory: directory, accountId: session.userId)
        reload()
    }

    func contains(_ chat: ChatSummary) -> Bool { markers[chat.roomId] != nil }

    func set(_ chat: ChatSummary, archived: Bool) {
        let raw = max(0, (chat.lastActivity?.timeIntervalSince1970 ?? 0) * 1000).rounded()
        let marker = raw.isFinite && raw <= 9_007_199_254_740_991 ? Int64(raw) : nil
        let action = Action(roomId: chat.roomId, archived: archived, marker: archived ? marker : nil)
        failedActions[chat.roomId] = nil
        if queuedRooms.contains(chat.roomId) { followUps[chat.roomId] = action }
        else { enqueue(action) }
    }

    func reconcile(_ chats: [ChatSummary]) {
        guard !closed else { return }
        latestChats = chats
        guard !failed else { return }
        var next = markers
        var synced = syncedRooms
        for chat in chats {
            guard !queuedRooms.contains(chat.roomId) else { continue }
            if let echo = echoes[chat.roomId] {
                guard echo.archived == chat.isLowPriority else { continue }
                echoes[chat.roomId] = nil
            }
            let raw = (chat.lastActivity?.timeIntervalSince1970 ?? 0) * 1000
            let activity = raw.isFinite && raw >= 0 && raw <= 9_007_199_254_740_991 ? Int64(raw.rounded()) : nil
            let decision = BipeChatArchive.reconcile(marker: next[chat.roomId], synced: synced.contains(chat.roomId),
                serverArchived: chat.isLowPriority, activity: activity, incoming: chat.latestIncomingMs)
            if let archived = decision.tagWrite {
                enqueue(Action(roomId: chat.roomId, archived: archived, marker: decision.marker))
            } else {
                next[chat.roomId] = decision.marker
                if decision.synced { synced.insert(chat.roomId) } else { synced.remove(chat.roomId) }
            }
        }
        guard next != markers || synced != syncedRooms else { return }
        do {
            try store.save(next, syncedRooms: synced)
            markers = next
            syncedRooms = synced
        } catch { storageFailed = true; failed = true }
    }

    func retry(_ chats: [ChatSummary]) {
        reload()
        guard !storageFailed else { return }
        let actions = Array(failedActions.values)
        failedActions.removeAll()
        failed = false
        for action in actions { enqueue(action) }
        reconcile(chats)
    }

    private func enqueue(_ action: Action) {
        guard !closed, queuedRooms.insert(action.roomId).inserted else { return }
        let previous = tail
        let taskId = UUID()
        let task = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            defer { tasks[taskId] = nil }
            guard !closed, !Task.isCancelled else { queuedRooms.remove(action.roomId); return }
            do {
                _ = try store.loadState() // A failed read must never overwrite another marker.
                if action.archived && action.marker == nil { throw Failure.invalidTimestamp }
                guard let room = try? session.client.getRoom(roomId: action.roomId) else { throw Failure.unavailable }
                let info = try await room.roomInfo()
                guard info.membership == .joined, !info.isSpace else { throw Failure.unavailable }
                try await room.setIsLowPriority(isLowPriority: action.archived, tagOrder: nil)
                let current = try store.loadState()
                var next = current.markers
                var synced = current.syncedRooms
                next[action.roomId] = action.archived ? action.marker : nil
                if action.archived { synced.insert(action.roomId) } else { synced.remove(action.roomId) }
                try store.save(next, syncedRooms: synced)
                markers = next
                syncedRooms = synced
                storageFailed = false
                failedActions[action.roomId] = nil
                let echo = Echo(id: UUID(), archived: action.archived)
                echoes[action.roomId] = echo
                Task { [weak self] in
                    try? await Task.sleep(for: .milliseconds(BipeChatArchive.echoGraceMs))
                    guard !Task.isCancelled, let self, echoes[action.roomId]?.id == echo.id else { return }
                    echoes[action.roomId] = nil
                    reconcile(latestChats)
                }
            } catch { failedActions[action.roomId] = action }
            queuedRooms.remove(action.roomId)
            if let followUp = followUps.removeValue(forKey: action.roomId) { enqueue(followUp) }
            failed = storageFailed || !failedActions.isEmpty
            reconcile(latestChats)
        }
        tail = task
        tasks[taskId] = task
    }

    func close() {
        closed = true
        tasks.values.forEach { $0.cancel() }
    }

    private func reload() {
        do {
            let state = try store.loadState()
            markers = state.markers
            syncedRooms = state.syncedRooms
            storageFailed = false
        } catch { storageFailed = true }
        failed = storageFailed || !failedActions.isEmpty
    }
}

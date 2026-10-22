// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Type ChatSummary of spec chat-list.
struct ChatSummary: Identifiable, Equatable {
    var roomId: String
    var name: String
    var avatarUrl: String?
    var lastMessage: String?
    var lastActivity: Date?
    var unreadCount: Int
    var isDirect: Bool
    var isMuted = false

    var id: String { roomId }
}

/// Spec chat-list (bipe-shared product/screens/chat-list.json).
@MainActor
final class ChatListModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case empty
        case loaded
    }

    static let pageSize: UInt32 = 50

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var chats: [ChatSummary] = []

    let session: AccountSession
    let media: MediaLoader
    private var rooms: [Room] = []
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult?
    private var handles: [TaskHandle] = []
    private var summaryTask: Task<Void, Never>?
    private var started = false

    init(session: AccountSession) {
        self.session = session
        media = MediaLoader(client: session.client)
    }

    deinit {
        handles.forEach { $0.cancel() }
        summaryTask?.cancel()
    }

    /// Subscribes to the room list service: joined chats, newest activity first.
    func start() async {
        guard !started else { return }
        started = true
        guard let roomList = try? await session.syncService.roomListService().allRooms() else { return }

        let loading = RoomListLoadingStream()
        if let result = try? roomList.loadingState(listener: loading) {
            handles.append(result.stateStream)
            onLoadingState(result.state)
        }
        let entriesStream = RoomListEntriesStream()
        let result = roomList.entriesWithDynamicAdapters(pageSize: Self.pageSize, listener: entriesStream)
        _ = result.controller().setFilter(kind: .all(filters: [.joined, .nonSpace]))
        handles.append(result.entriesStream())
        entries = result

        Task { [weak self] in
            for await state in loading.updates {
                self?.onLoadingState(state)
            }
        }
        for await updates in entriesStream.updates {
            for update in updates {
                rooms.apply(update.vectorDiff)
            }
            refreshSummaries()
        }
    }

    /// Action loadMore: the next page, when the list scrolls near its end.
    func loadMore() {
        entries?.controller().addOnePage()
    }

    private func onLoadingState(_ state: RoomListLoadingState) {
        if case .loaded = state {
            listLoaded = true
        }
        updatePhase()
    }

    private func updatePhase() {
        if !chats.isEmpty {
            phase = .loaded
        } else {
            phase = listLoaded && rooms.isEmpty ? .empty : .loading
        }
    }

    private func refreshSummaries() {
        let rooms = rooms
        summaryTask?.cancel()
        summaryTask = Task { [weak self] in
            var summaries: [ChatSummary] = []
            for room in rooms {
                if let summary = await Self.summary(of: room) {
                    summaries.append(summary)
                }
            }
            guard !Task.isCancelled, let self else { return }
            chats = summaries
            updatePhase()
        }
    }

    private static func summary(of room: Room) async -> ChatSummary? {
        guard let info = try? await room.roomInfo() else { return nil }
        var lastMessage: String?
        var lastActivity: Date?
        switch await room.latestEvent() {
        case .remote(let timestamp, _, _, _, let content), .local(let timestamp, _, _, let content, _):
            lastMessage = content.previewText
            lastActivity = Date(milliseconds: timestamp)
        case .remoteInvite(let timestamp, _, _):
            lastActivity = Date(milliseconds: timestamp)
        case .none:
            break
        }
        return ChatSummary(roomId: info.id,
                           name: info.displayName ?? info.id,
                           avatarUrl: info.avatarUrl,
                           lastMessage: lastMessage,
                           lastActivity: lastActivity,
                           unreadCount: Int(info.numUnreadMessages),
                           isDirect: info.isDirect,
                           isMuted: info.cachedUserDefinedNotificationMode == .mute)
    }
}

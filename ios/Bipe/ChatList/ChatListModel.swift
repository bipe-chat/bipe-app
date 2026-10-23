// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
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
    /// Joined and invited members. Not in the spec type: it decides the scope.
    var memberCount: Int
    /// Unread mentions and replies, for the Grupos badge (spec app-tabs gruposAttention).
    var mentionCount: Int
    /// The other member of a DM, for the avatar account-type lookup. Groups keep their room avatar.
    var peerUserId: String? = nil
    /// RoomInfo.isDm, the one DM test (ADR 0141).
    var isDm = false
    var isMuted = false

    var id: String { roomId }

    /// ADR 0141: a group of two stays a group; RoomInfo.isDm is the one DM test.
    var scope: ChatScope { isDm ? .conversas : .grupos }
}

/// State scope of spec chat-list.
enum ChatScope: String, CaseIterable, Hashable {
    case conversas
    case grupos
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
    /// Each tab keeps its own Unread filter without changing the all-chat stream used by tab badges.
    @Published private(set) var unreadScopes: Set<ChatScope> = []
    @Published private var unreadChats: [ChatScope: [ChatSummary]] = [:]
    @Published private var unreadLoading: Set<ChatScope> = []
    private var unreadLists: [ChatScope: FilteredRoomList] = [:]
    private var unreadTasks: [ChatScope: Task<Void, Never>] = [:]

    let session: AccountSession
    let media: MediaLoader
    let notes: NotesRoom
    private var rooms: [Room] = []
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult?
    private var handles: [TaskHandle] = []
    private var summaryTask: Task<Void, Never>?
    private var started = false
    private var clearChanges: AnyCancellable?

    init(session: AccountSession) {
        self.session = session
        media = MediaLoader(client: session.client)
        notes = NotesRoom(session: session)
        clearChanges = ClearedChatsStore.shared.$revision.dropFirst().sink { [weak self] _ in
            self?.refreshSummaries()
            self?.unreadLists.values.forEach { $0.refresh() }
        }
    }

    /// The chats of one scope, in room list order.
    func chats(in scope: ChatScope) -> [ChatSummary] {
        (unreadScopes.contains(scope) ? unreadChats[scope, default: []] : chats).filter { $0.scope == scope }
    }

    /// The phase of one scope: empty when the list loaded and the scope has no chat.
    func phase(in scope: ChatScope) -> Phase {
        guard phase != .loading, !(unreadScopes.contains(scope) && unreadLoading.contains(scope)) else { return .loading }
        return !chats(in: scope).isEmpty ? .loaded : (listLoaded ? .empty : .loading)
    }

    deinit {
        handles.forEach { $0.cancel() }
        summaryTask?.cancel()
    }

    /// Subscribes to the room list service: joined chats, newest activity first.
    func start() async {
        guard !started else { return }
        started = true
        await refreshNotes()
        guard let roomList = try? await session.syncService.roomListService().allRooms() else { return }

        let loading = RoomListLoadingStream()
        if let result = try? roomList.loadingState(listener: loading) {
            handles.append(result.stateStream)
            onLoadingState(result.state)
        }
        let entriesStream = RoomListEntriesStream()
        let result = roomList.entriesWithDynamicAdapters(pageSize: Self.pageSize, listener: entriesStream)
        _ = result.controller().setFilter(kind: filter)
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

    /// Requests stay outside the joined list; badges always observe the unfiltered stream.
    private var filter: RoomListEntriesDynamicFilterKind { .all(filters: [.joined, .nonSpace, .deduplicateVersions]) }

    func toggleUnread(in scope: ChatScope) {
        if unreadScopes.remove(scope) != nil { return }
        unreadScopes.insert(scope)
        guard unreadLists[scope] == nil else { return }
        unreadLoading.insert(scope)
        let list = FilteredRoomList { [weak self] rooms in
            guard let self else { return }
            self.unreadTasks[scope]?.cancel()
            self.unreadTasks[scope] = Task { [weak self] in
                guard let self else { return }
                var summaries: [ChatSummary] = []
                for room in rooms where room.id() != self.notes.roomId {
                    if let summary = await Self.summary(of: room, ownUserId: self.session.userId) { summaries.append(summary) }
                }
                guard !Task.isCancelled else { return }
                let visible = await NativeDmSelection.visible(summaries, session: self.session)
                guard !Task.isCancelled else { return }
                self.unreadChats[scope] = visible
                self.unreadLoading.remove(scope)
            }
        }
        unreadLists[scope] = list
        Task { await list.start(session: session, filter: .all(filters: [.joined, .nonSpace, .deduplicateVersions,
            .category(expect: scope == .conversas ? .people : .group), .unread])) }
    }

    /// Action loadMore: the next page, when the list scrolls near its end.
    func loadMore(in scope: ChatScope) {
        if unreadScopes.contains(scope) { unreadLists[scope]?.loadMore() }
        else { entries?.controller().addOnePage() }
    }

    func refreshNotes() async {
        try? await notes.refresh()
        refreshSummaries()
    }

    func openNotes() async throws -> String {
        defer { refreshSummaries() }
        return try await notes.open()
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
            phase = listLoaded ? .empty : .loading
        }
    }

    private func refreshSummaries() {
        let rooms = rooms
        let userId = session.userId
        summaryTask?.cancel()
        summaryTask = Task { [weak self] in
            var summaries: [ChatSummary] = []
            for room in rooms {
                if room.id() != self?.notes.roomId, let summary = await Self.summary(of: room, ownUserId: userId) {
                    summaries.append(summary)
                }
            }
            guard !Task.isCancelled, let self else { return }
            let visible = await NativeDmSelection.visible(summaries, session: session)
            guard !Task.isCancelled else { return }
            chats = visible
            updatePhase()
        }
    }

    static func summary(of room: Room, ownUserId: String) async -> ChatSummary? {
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
        let peer = info.isDm ? info.heroes.first(where: { $0.userId != ownUserId }) : nil
        let peerName = peer?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = peerName.flatMap { $0.isEmpty ? nil : $0 } ?? peer.map { Person.localpart(of: $0.userId) } ?? BipeStrings.dmNoName
        return ChatSummary(roomId: info.id,
                           name: info.isDm ? name : (info.displayName ?? info.id),
                           avatarUrl: info.isDm ? peer?.avatarUrl : info.avatarUrl,
                           lastMessage: lastMessage,
                           lastActivity: lastActivity,
                           unreadCount: Int(info.numUnreadMessages),
                           isDirect: info.isDirect,
                           memberCount: Int(info.activeMembersCount),
                           mentionCount: Int(info.numUnreadMentions),
                           peerUserId: peer?.userId,
                           isDm: info.isDm,
                           isMuted: info.cachedUserDefinedNotificationMode == .mute)
    }
}

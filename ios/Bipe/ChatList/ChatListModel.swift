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
    var isMarkedUnread = false
    var isUnread: Bool { unreadCount > 0 || isMarkedUnread }
    var latestIncomingMs: Int64? = nil

    var id: String { roomId }

    /// ADR 0141: a group of two stays a group; RoomInfo.isDm is the one DM test.
    var scope: ChatScope { isDm ? .conversas : .grupos }
    /// Spec chat-list isOfficial: a DM with an official Bipe account (ADR 0117), by exact user ID only.
    var isOfficial: Bool { isDm && BipeOfficialAccounts.isOfficial(peerUserId) }
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

    let session: AccountSession
    let media: MediaLoader
    let notes: NotesRoom
    let archive: ChatArchiveModel
    private var extraRoomIds: Set<String> = []
    private var rooms: [Room] = []
    private let subscriptionOwner = UUID().uuidString
    /// One model belongs to one account session. Never replace a known time with an absent preview.
    private var knownActivity: [String: Date] = [:]
    /// Rows without an SDK latest event take their date from the local timeline (bipe-chat/bipe#592).
    private var rowActivity: RowActivityScanner?
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult?
    private var handles: [TaskHandle] = []
    private var summaryTask: Task<Void, Never>?
    @Published private(set) var markingRoomId: String?
    @Published private(set) var readActionFailed = false
    private var failedReadIntent: (roomId: String, unread: Bool)?
    private enum ReadFailure: Error { case unavailable }
    private var started = false
    private var clearChanges: AnyCancellable?

    init(session: AccountSession) {
        self.session = session
        archive = ChatArchiveModel(accountId: session.userId)
        media = MediaLoader(client: session.client)
        notes = NotesRoom(session: session)
        clearChanges = ClearedChatsStore.shared.$revision.dropFirst().sink { [weak self] _ in
            self?.refreshSummaries()
        }
        rowActivity = RowActivityScanner(client: session.client) { [weak self] in self?.refreshSummaries() }
    }

    /// The chats of one scope, in room list order.
    func chats(in scope: ChatScope, archived: Bool = false) -> [ChatSummary] {
        chats.filter { $0.scope == scope && archive.contains($0) == archived }
    }

    /// The phase of one scope: empty when the list loaded and the scope has no chat.
    func phase(in scope: ChatScope, archived: Bool = false) -> Phase {
        guard phase != .loading else { return .loading }
        return !chats(in: scope, archived: archived).isEmpty ? .loaded : (listLoaded ? .empty : .loading)
    }

    deinit {
        handles.forEach { $0.cancel() }
        summaryTask?.cancel()
        let subscriptions = session.roomSubscriptions
        let owner = subscriptionOwner
        Task { await subscriptions.remove(owner: owner) }
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
                // A room that moved or changed may have newer events than the ones read for its date.
                switch update {
                case .set(_, let room), .insert(_, let room), .pushFront(let room), .pushBack(let room):
                    rowActivity?.invalidate(roomId: room.id())
                default:
                    break
                }
                rooms.apply(update.vectorDiff)
            }
            refreshSummaries()
            // Cached rows publish first. The SDK hydrates latest events in the background, even offline.
            await session.roomSubscriptions.update(owner: subscriptionOwner,
                roomIDs: rooms.map { $0.id() } + Array(archive.markers.keys))
        }
    }

    /// Requests stay outside the joined list; badges always observe the unfiltered stream.
    private var filter: RoomListEntriesDynamicFilterKind { .all(filters: [.joined, .nonSpace, .deduplicateVersions]) }

    /// Action loadMore: the next page, when the list scrolls near its end.
    func loadMore(in scope: ChatScope) {
        entries?.controller().addOnePage()
    }

    /// Resolve actions in this account's room list, never a global current account.
    func currentChat(_ roomId: String) -> ChatSummary? {
        chats.first { $0.roomId == roomId }
    }

    /// Marking read is an explicit public receipt, followed by clearing the manual unread flag (ADR 0050).
    func setUnread(roomId: String, unread: Bool) async {
        guard markingRoomId == nil else { return }
        markingRoomId = roomId
        readActionFailed = false
        defer { markingRoomId = nil }
        do {
            guard currentChat(roomId) != nil || (failedReadIntent?.roomId == roomId && failedReadIntent?.unread == unread),
                  let room = try? session.client.getRoom(roomId: roomId) else { throw ReadFailure.unavailable }
            let info = try await room.roomInfo()
            guard info.membership == .joined, !info.isSpace else { throw ReadFailure.unavailable }
            if !unread { try await room.markAsRead(receiptType: .read) }
            try await room.setUnreadFlag(newValue: unread)
            failedReadIntent = nil
            refreshSummaries()
        } catch is CancellationError {
            // A discarded view task must not become a retry in a different account.
        } catch {
            failedReadIntent = (roomId, unread)
            readActionFailed = true
        }
    }

    func retryReadAction() async {
        guard let intent = failedReadIntent else { return }
        await setUnread(roomId: intent.roomId, unread: intent.unread)
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
        extraRoomIds.formUnion(archive.markers.keys)
        var rooms = rooms
        let loadedIds = Set(rooms.map { $0.id() })
        for id in extraRoomIds.subtracting(loadedIds).sorted() {
            if let room = try? session.client.getRoom(roomId: id) { rooms.append(room) }
        }
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
            let dated = retainingKnownActivity(visible)
            archive.reconcile(dated)
            // The SDK also knows server recency stamps and local echoes. Optional previews cannot replace its order.
            chats = dated
            updatePhase()
        }
    }

    /// The row date (BipeRowActivity): the SDK latest event, else the newest event of the local timeline, which
    /// includes undecryptable events (bipe-chat/bipe#592), else a time known earlier in this account session.
    private func retainingKnownActivity(_ summaries: [ChatSummary]) -> [ChatSummary] {
        summaries.map { summary in
            var summary = summary
            let sdkLatestMs = summary.lastActivity.map(Self.milliseconds)
            guard BipeRowActivity.needsLocalTimeline(sdkLatestMs: sdkLatestMs) else {
                knownActivity[summary.roomId] = summary.lastActivity
                return summary
            }
            let local = rowActivity?.localEvents(roomId: summary.roomId) ?? []
            let known = knownActivity[summary.roomId].map(Self.milliseconds)
            if let ms = BipeRowActivity.resolve(sdkLatestMs: sdkLatestMs, localEvents: local, knownMs: known) {
                summary.lastActivity = Date(milliseconds: ms)
                knownActivity[summary.roomId] = summary.lastActivity
            }
            return summary
        }
    }

    private static func milliseconds(_ date: Date) -> UInt64 {
        UInt64(max(0, (date.timeIntervalSince1970 * 1000).rounded()))
    }

    static func summary(of room: Room, ownUserId: String) async -> ChatSummary? {
        guard let info = try? await room.roomInfo(), info.membership == .joined, !info.isSpace else { return nil }
        var lastMessage: String?
        var lastActivity: Date?
        var latestIncomingMs: Int64?
        switch await room.latestEvent() {
        case .remote(let timestamp, _, let isOwn, _, let content):
            lastMessage = content.previewText
            lastActivity = Date(milliseconds: timestamp)
            if !isOwn, lastMessage != nil { latestIncomingMs = Int64(clamping: timestamp) }
        case .local(let timestamp, _, _, let content, _):
            lastMessage = content.previewText
            lastActivity = Date(milliseconds: timestamp)
        case .remoteInvite(let timestamp, _, _):
            lastActivity = Date(milliseconds: timestamp)
        case .none:
            break
        }
        let peer = info.isDm ? info.heroes.first(where: { $0.userId != ownUserId }) : nil
        let name = BipeChatRowName.resolve(roomID: info.id, sdkName: info.displayName,
            peerName: peer?.displayName, peerID: peer?.userId, unnamed: BipeStrings.dmNoName,
            dmRoomName: info.isDm ? info.rawName : nil)
        return ChatSummary(roomId: info.id,
                           name: name,
                           // ADR 0141: a DM shows the person's photo, never m.room.avatar.
                           avatarUrl: info.isDm ? peer?.avatarUrl : info.avatarUrl,
                           lastMessage: lastMessage,
                           lastActivity: lastActivity,
                           unreadCount: Int(info.numUnreadMessages),
                           isDirect: info.isDirect,
                           memberCount: Int(info.activeMembersCount),
                           mentionCount: Int(info.numUnreadMentions),
                           peerUserId: peer?.userId,
                           isDm: info.isDm,
                           isMuted: info.cachedUserDefinedNotificationMode == .mute,
                           isMarkedUnread: info.isMarkedUnread,
                           latestIncomingMs: latestIncomingMs)
    }
}

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
    /// Spec chat-list notes, Group avatar: members shown when a group has no photo.
    var avatarMembers: [Person] = []
    /// RoomInfo.isDm, the one DM test (ADR 0141).
    var isDm = false
    var isMuted = false
    var isMarkedUnread = false
    var isUnread: Bool { unreadCount > 0 || isMarkedUnread }
    var isLowPriority = false
    /// RoomInfo.isFavourite (m.favourite): the chat shows in the favourites row (spec chat-list).
    var isFavourite = false
    var latestIncomingMs: Int64? = nil

    var id: String { roomId }

    /// ADR 0141: a group of two stays a group; RoomInfo.isDm is the one DM test.
    var scope: ChatScope { isDm ? .conversas : .grupos }
    /// The account's news room (NewsRoomMark, bipe-chat/bipe#978).
    var isNewsRoom = false

    /// Spec chat-list isOfficial: a DM with an official Bipe account (ADR 0117), by exact user ID only, or the news room.
    var isOfficial: Bool { isNewsRoom || (isDm && BipeOfficialAccounts.isOfficial(peerUserId)) }
}

/// State scope of spec chat-list.
enum ChatScope: String, CaseIterable, Hashable {
    case conversas
    case grupos
    /// The Conversas tab of a company account: DMs and groups together (spec app-tabs).
    case todas
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
    private var archivedRooms: [Room] = []
    private var favouriteRooms: [Room] = []
    private var archiveEntries: RoomListEntriesWithDynamicAdaptersResult?
    private var favouriteEntries: RoomListEntriesWithDynamicAdaptersResult?
    private var archiveTask: Task<Void, Never>?
    private var favouriteTask: Task<Void, Never>?
    private let subscriptionOwner = UUID().uuidString
    /// One model belongs to one account session. Never replace a known time or text with an absent preview.
    private var knownRows: [String: BipeRowActivity.Row] = [:]
    /// Rows take a newer message of the local timeline as their date and text (bipe-chat/bipe#592).
    private var rowActivity: RowActivityScanner?
    /// Spec read-receipts: the delivered acks of this account, sent and received while the list runs.
    let delivered: DeliveredAcks
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult?
    private var handles: [TaskHandle] = []
    private var summaryTask: Task<Void, Never>?
    @Published private(set) var markingRoomId: String?
    @Published private(set) var favouriteFailed = false
    /// The favourites order of spec chat-list (registries/chat-favourites.json): room IDs, first first.
    @Published private(set) var favouritesOrder: [String] = []
    /// A local order that is not on the server yet: a read must not replace it.
    private var favouritesOrderUnsaved = false
    @Published private(set) var readActionFailed = false
    /// A local read found an undecryptable message: feeds encryption-status noteUndecryptable (bipe-chat/bipe#853).
    @Published private(set) var hasUndecryptable = false
    private var failedReadIntent: (roomId: String, unread: Bool)?
    private enum ReadFailure: Error { case unavailable }
    private var started = false
    private var clearChanges: AnyCancellable?
    private var draftsChanges: AnyCancellable?

    init(session: AccountSession) {
        self.session = session
        archive = ChatArchiveModel(session: session)
        media = MediaLoader(client: session.client)
        notes = NotesRoom(session: session)
        delivered = DeliveredAcks(client: session.client, ownUserId: session.userId)
        clearChanges = ClearedChatsStore.shared.$revision.dropFirst().sink { [weak self] _ in
            self?.refreshSummaries()
        }
        // Spec drafts notes: the drafts room never shows in the list, its badges or the share targets.
        draftsChanges = AssistantDraftsStore.shared.$revision.dropFirst().sink { [weak self] _ in
            self?.refreshSummaries()
        }
        rowActivity = RowActivityScanner(client: session.client, ownUserId: session.userId) { [weak self] in self?.refreshSummaries() }
        rowActivity?.onDmIncoming = { [delivered] room, eventId, sender in
            Task { await delivered.send(room: room, eventId: eventId, sender: sender) }
        }
    }

    /// The chats of one scope, in room list order.
    func chats(in scope: ChatScope, archived: Bool = false) -> [ChatSummary] {
        chats.filter { (scope == .todas || $0.scope == scope) && archive.contains($0) == archived }
    }

    /// State favourites of spec chat-list: the favourites of one scope that are not archived, in the favourites order.
    func favourites(in scope: ChatScope) -> [ChatSummary] {
        BipeFavouritesOrder.sorted(chats(in: scope).filter(\.isFavourite), order: favouritesOrder,
                                   roomId: \.roomId, name: \.name)
    }

    /// The phase of one scope: empty when the list loaded and the scope has no chat.
    func phase(in scope: ChatScope, archived: Bool = false) -> Phase {
        guard phase != .loading else { return .loading }
        return !chats(in: scope, archived: archived).isEmpty ? .loaded : (listLoaded ? .empty : .loading)
    }

    deinit {
        handles.forEach { $0.cancel() }
        let delivered = delivered
        Task { await delivered.stop() }
        summaryTask?.cancel()
        archiveTask?.cancel()
        favouriteTask?.cancel()
        let archive = archive
        Task { @MainActor in archive.close() }
        let subscriptions = session.roomSubscriptions
        let owner = subscriptionOwner
        Task { await subscriptions.remove(owner: owner) }
    }

    /// Subscribes to the room list service: joined chats, newest activity first.
    func start() async {
        guard !started else { return }
        started = true
        await refreshNotes()
        await AssistantDraftsStore.shared.learnRoomId(session: session)
        await delivered.start()
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

        // Dedicated tag-filtered streams find archives and favourites outside the active first page.
        let archived = tagStream(roomList, tag: .lowPriority) { $0.archivedRooms.apply($1) }
        archiveEntries = archived.0
        archiveTask = archived.1
        await loadFavouritesOrder()
        let favourites = tagStream(roomList, tag: .favourite, onUpdate: { await $0.loadFavouritesOrder() }) {
            $0.favouriteRooms.apply($1)
        }
        favouriteEntries = favourites.0
        favouriteTask = favourites.1

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
            await updateSubscriptions()
            // A first DM can come after start: the ack receiver binds to it (no work while one runs).
            await delivered.start()
        }
    }

    /// A room list stream of joined rooms with one tag (m.lowpriority or m.favourite).
    private func tagStream(_ roomList: RoomList, tag: RoomListEntriesDynamicFilterKind,
                           onUpdate: (@MainActor (ChatListModel) async -> Void)? = nil,
                           apply: @escaping @MainActor (ChatListModel, VectorDiff<Room>) -> Void)
        -> (RoomListEntriesWithDynamicAdaptersResult, Task<Void, Never>) {
        let stream = RoomListEntriesStream()
        let entries = roomList.entriesWithDynamicAdapters(pageSize: Self.pageSize, listener: stream)
        _ = entries.controller().setFilter(kind: .all(filters: [.joined, .nonSpace, tag, .deduplicateVersions]))
        handles.append(entries.entriesStream())
        let task = Task { [weak self] in
            for await updates in stream.updates {
                guard let self else { return }
                for update in updates {
                    switch update {
                    case .set(_, let room), .insert(_, let room), .pushFront(let room), .pushBack(let room):
                        rowActivity?.invalidate(roomId: room.id())
                    default: break
                    }
                    apply(self, update.vectorDiff)
                }
                await onUpdate?(self)
                refreshSummaries()
                await updateSubscriptions()
            }
        }
        return (entries, task)
    }

    private func updateSubscriptions() async {
        await session.roomSubscriptions.update(owner: subscriptionOwner,
            roomIDs: rooms.map { $0.id() } + archivedRooms.map { $0.id() } + favouriteRooms.map { $0.id() }
                + Array(archive.markers.keys))
    }

    /// Requests stay outside the joined list; badges always observe the unfiltered stream.
    private var filter: RoomListEntriesDynamicFilterKind { .all(filters: [.joined, .nonSpace, .deduplicateVersions]) }

    /// Action loadMore: the next page, when the list scrolls near its end.
    func loadMore(in scope: ChatScope) {
        entries?.controller().addOnePage()
        archiveEntries?.controller().addOnePage()
        favouriteEntries?.controller().addOnePage()
    }

    /// Resolve actions in this account's room list, never a global current account.
    func currentChat(_ roomId: String) -> ChatSummary? {
        chats.first { $0.roomId == roomId }
    }

    /// The local timeline events of a room, for the Mural inputs (spec chat-list). No network, no pagination. nil: not read yet.
    func localEvents(roomId: String) -> [BipeRowActivity.LocalEvent]? {
        rowActivity?.localEvents(roomId: roomId)
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
            if !unread { try await room.markAsRead(receiptType: ReadReceiptSetting.receiptType(isDm: info.isDm)) }
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

    /// Action setFavourite of spec chat-list: the m.favourite tag, so it syncs to the account's other phones.
    func setFavourite(roomId: String, favourite: Bool) async {
        favouriteFailed = false
        do {
            guard currentChat(roomId) != nil, let room = try? session.client.getRoom(roomId: roomId) else {
                throw ReadFailure.unavailable
            }
            let info = try await room.roomInfo()
            guard info.membership == .joined, !info.isSpace else { throw ReadFailure.unavailable }
            try await room.setIsFavourite(isFavourite: favourite, tagOrder: nil)
            // Spec chat-list favourites order: a new favourite goes last, a removed one leaves the order.
            await saveFavouritesOrder(favourite ? BipeFavouritesOrder.adding(roomId, to: favouritesOrder)
                                                : BipeFavouritesOrder.removing(roomId, from: favouritesOrder))
            refreshSummaries()
        } catch is CancellationError {
        } catch {
            favouriteFailed = true
        }
    }

    /// Action moveFavourite of spec chat-list: moves a favourite among the shown favourites of its scope and writes the order.
    func moveFavourite(roomId: String, to index: Int, shown: [String]) async {
        await saveFavouritesOrder(BipeFavouritesOrder.moving(roomId, to: index, shown: shown, order: favouritesOrder))
    }

    /// Reads the favourites order from the account data, unless this phone has an order it could not write yet.
    private func loadFavouritesOrder() async {
        guard !favouritesOrderUnsaved,
              let json = try? await session.client.accountData(eventType: BipeFavouritesOrder.eventType) else { return }
        favouritesOrder = BipeFavouritesOrder.parse(json)
    }

    /// Shows the order at once and writes it. A failed write keeps it on this phone; the next change writes it again.
    private func saveFavouritesOrder(_ order: [String]) async {
        favouritesOrder = order
        favouritesOrderUnsaved = true
        do {
            try await session.client.setAccountData(eventType: BipeFavouritesOrder.eventType,
                                                    content: BipeFavouritesOrder.content(order))
            if favouritesOrder == order { favouritesOrderUnsaved = false }
        } catch {}
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
        if rowActivity?.sawUndecryptable == true, !hasUndecryptable {
            hasUndecryptable = true
        }
        extraRoomIds.formUnion(archive.markers.keys)
        var rooms = rooms
        var loadedIds = Set(rooms.map { $0.id() })
        for room in archivedRooms + favouriteRooms where loadedIds.insert(room.id()).inserted { rooms.append(room) }
        for id in extraRoomIds.subtracting(loadedIds).sorted() {
            if let room = try? session.client.getRoom(roomId: id) { rooms.append(room) }
        }
        let userId = session.userId
        let draftsRoomId = AssistantDraftsStore.shared.roomId(for: userId)
        summaryTask?.cancel()
        summaryTask = Task { [weak self] in
            var summaries: [ChatSummary] = []
            for room in rooms {
                if room.id() != self?.notes.roomId, room.id() != draftsRoomId, let summary = await Self.summary(of: room, ownUserId: userId) {
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

    /// The row date and text (BipeRowActivity): a message of the local timeline newer than the SDK latest event, else
    /// the SDK latest event, else the newest local event, which includes undecryptable events (bipe-chat/bipe#592), else a
    /// time known earlier in this account session. The SDK keeps an older latest event when a message arrived before its
    /// room key; the timeline decrypts it later.
    private func retainingKnownActivity(_ summaries: [ChatSummary]) -> [ChatSummary] {
        summaries.map { summary in
            var summary = summary
            let sdk = summary.lastActivity.map {
                BipeRowActivity.SDKLatest(timestampMs: Self.milliseconds($0), previewText: summary.lastMessage,
                                          isIncoming: summary.latestIncomingMs != nil)
            }
            let local = rowActivity?.localEvents(roomId: summary.roomId) ?? []
            let row = BipeRowActivity.resolve(sdk: sdk, localEvents: local, known: knownRows[summary.roomId])
            if let ms = row.timestampMs {
                summary.lastActivity = Date(milliseconds: ms)
                knownRows[summary.roomId] = row
            }
            summary.lastMessage = row.previewText
            summary.latestIncomingMs = row.latestIncomingMs.map { Int64(clamping: $0) }
            return summary
        }
    }

    private static func milliseconds(_ date: Date) -> UInt64 {
        UInt64(max(0, (date.timeIntervalSince1970 * 1000).rounded()))
    }

    /// The row text of an event (spec chat-list, row text): "missed call" for an incoming call this account missed, a
    /// line for a join, leave or room creation, else the preview, with "You: " before an own one.
    nonisolated static func rowPreview(of content: TimelineItemContent, isOwn: Bool, senderName: String, roomId: String,
                                       isDm: Bool, ownUserId: String) -> String? {
        if case .rtcNotification = content, !isOwn, MissedCalls.isMissed(accountUserId: ownUserId, roomId: roomId) {
            return BipeStrings.callMissed
        }
        if let line = membershipLine(of: content, senderName: senderName, isOwn: isOwn, isDm: isDm, ownUserId: ownUserId) {
            return line
        }
        guard let preview = content.previewText else { return nil }
        return isOwn ? BipeStrings.chatListOwnPreview(preview) : preview
    }

    /// A join (an accepted invite or knock included), a leave or the room creation. nil for other events.
    private nonisolated static func membershipLine(of content: TimelineItemContent, senderName: String, isOwn: Bool,
                                                   isDm: Bool, ownUserId: String) -> String? {
        switch content {
        case .roomMembership(let userId, let displayName, let change?, _):
            let own = userId == ownUserId
            let name = BipeChatRowName.person(userID: userId, displayName: displayName, handle: nil)
            switch change {
            case .joined, .invitationAccepted, .knockAccepted:
                if isDm { return own ? BipeStrings.dmJoinedByYou : BipeStrings.dmJoined(name) }
                return own ? BipeStrings.groupStateEventRoomJoinByYou : BipeStrings.groupStateEventRoomJoin(name)
            case .left:
                if isDm { return own ? BipeStrings.dmLeftByYou : BipeStrings.dmLeft(name) }
                return own ? BipeStrings.groupStateEventRoomLeaveByYou : BipeStrings.groupStateEventRoomLeave(name)
            default:
                return nil
            }
        case .state(_, .roomCreate):
            if isDm { return isOwn ? BipeStrings.dmCreatedByYou : BipeStrings.dmCreated(senderName) }
            return isOwn ? BipeStrings.groupStateEventRoomCreatedByYou : BipeStrings.groupStateEventRoomCreated(senderName)
        default:
            return nil
        }
    }

    static func summary(of room: Room, ownUserId: String) async -> ChatSummary? {
        guard let info = try? await room.roomInfo(), info.membership == .joined, !info.isSpace else { return nil }
        var lastMessage: String?
        var lastActivity: Date?
        var latestIncomingMs: Int64?
        switch await room.latestEvent() {
        case .remote(let timestamp, let sender, let isOwn, let profile, let content):
            lastMessage = rowPreview(of: content, isOwn: isOwn, senderName: profile.displayName(or: sender), roomId: info.id,
                                     isDm: info.isDm, ownUserId: ownUserId)
            lastActivity = Date(milliseconds: timestamp)
            if !isOwn, lastMessage != nil { latestIncomingMs = Int64(clamping: timestamp) }
        case .local(let timestamp, let sender, let profile, let content, _):
            lastMessage = rowPreview(of: content, isOwn: true, senderName: profile.displayName(or: sender), roomId: info.id,
                                     isDm: info.isDm, ownUserId: ownUserId)
            lastActivity = Date(milliseconds: timestamp)
        case .remoteInvite(let timestamp, _, _):
            lastActivity = Date(milliseconds: timestamp)
        case .none:
            // A sticker pack room has no messages (spec sticker-picker notes, Pack room): the chat list hides it.
            if info.isPublic == true, case .worldReadable = info.historyVisibility { return nil }
        }
        let peer = info.isDm ? info.heroes.first(where: { $0.userId != ownUserId }) : nil
        // Spec chat-list news room mark: the app-language name, not the room's Portuguese m.room.name.
        let isNewsRoom = NewsRoomMark.roomId(for: ownUserId) == info.id
        let name = isNewsRoom ? BipeStrings.newsRoomName : BipeChatRowName.resolve(roomID: info.id, sdkName: info.displayName,
            peerName: peer?.displayName, peerID: peer?.userId, unnamed: BipeStrings.dmNoName,
            dmRoomName: info.isDm ? info.rawName : nil,
            groupHeroes: ChatTimelineModel.groupHeroes(of: info, ownUserId: ownUserId))
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
                           avatarMembers: Person.groupAvatarMembers(of: info, ownUserId: ownUserId),
                           isDm: info.isDm,
                           isMuted: info.cachedUserDefinedNotificationMode == .mute,
                           isMarkedUnread: info.isMarkedUnread,
                           isLowPriority: info.isLowPriority,
                           isFavourite: info.isFavourite,
                           latestIncomingMs: latestIncomingMs,
                           isNewsRoom: isNewsRoom)
    }
}

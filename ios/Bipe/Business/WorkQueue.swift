// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Spec work-chats filter: Comigo, Sem ninguém, Todas.
enum WorkChatFilter: String, CaseIterable, Identifiable {
    case mine
    case pool
    case all

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mine: BipeStrings.chatsFilterMine
        case .pool: BipeStrings.chatsFilterPool
        case .all: BipeStrings.chatsFilterAll
        }
    }
}

/// Specs work-chats and work-chat-bar (bipe ADR 0284 phase A): who has each customer chat of a work account with two
/// or more people. One store for the app, for the account that loaded last. With one person nothing shows.
@MainActor
final class WorkQueueStore: ObservableObject {
    static let shared = WorkQueueStore()

    /// The account whose seats and records are loaded.
    @Published private(set) var userId: String?
    /// State people: GET /v1/me/business/seats. nil: not a work account, not read, or the read failed.
    @Published private(set) var seats: BipeSeatList?
    /// GET /v1/me/business/chats, by room ID. A room with no record is open and in the pool.
    @Published private(set) var records: [String: BipeChatAssignment] = [:]
    /// State filter of work-chats. Default all.
    @Published var filter: WorkChatFilter = .all
    /// Rooms whose update runs (phase updating).
    @Published private(set) var updating: Set<String> = []
    /// Rooms whose last update failed (phase failed).
    @Published private(set) var failed: Set<String> = []
    /// Rooms whose last take met another person (phase taken, after 409 chat_taken).
    @Published private(set) var taken: Set<String> = []

    private var api: BipeBusinessAPI?

    private init() {}

    /// The filters and assignee lines show only for this account with 2 or more active seats.
    func isActive(userId: String) -> Bool {
        self.userId == userId && (seats?.activeSeats.count ?? 0) >= 2
    }

    /// State mySeat: SeatList.me.
    var mySeatId: String? { seats?.me.seatId }

    /// Every seat ID of the account, for replyByFromEvent.
    var seatIds: Set<String> { Set(seats?.seats.map(\.id) ?? []) }

    /// The active seats, for Passar para.
    var activeSeats: [BipeSeat] { seats?.activeSeats ?? [] }

    func seatName(_ seatId: String?) -> String? {
        guard let seatId else { return nil }
        return seats?.seats.first { $0.id == seatId }?.name
    }

    /// State assignee: nil is the pool.
    func assignee(roomId: String) -> String? {
        records[roomId]?.assigneeSeatId
    }

    /// State status: open when there is no record.
    func status(roomId: String) -> BipeChatAssignmentStatus {
        records[roomId]?.status ?? .open
    }

    /// Another person has the chat.
    func isTakenByOther(roomId: String) -> Bool {
        guard let assignee = assignee(roomId: roomId) else { return false }
        return assignee != mySeatId
    }

    /// The row line of work-chats: bipe_chat_assignee_me, bipe_chat_assignee_other with the seat name, or nil for the pool.
    func rowLine(roomId: String) -> String? {
        guard let assignee = assignee(roomId: roomId) else { return nil }
        if assignee == mySeatId { return BipeStrings.chatAssigneeMe }
        return seatName(assignee).map(BipeStrings.chatAssigneeOther)
    }

    /// The line of work-chat-bar: as the row, and bipe_chat_assignee_none for the pool.
    func barLine(roomId: String) -> String {
        rowLine(roomId: roomId) ?? BipeStrings.chatAssigneeNone
    }

    /// Action selectFilter: on the device. Only customer chats match Comigo and Sem ninguém.
    func matches(roomId: String, isCustomerChat: Bool) -> Bool {
        switch filter {
        case .all: return true
        case .mine: return isCustomerChat && mySeatId != nil && assignee(roomId: roomId) == mySeatId
        case .pool: return isCustomerChat && assignee(roomId: roomId) == nil
        }
    }

    /// Action load of work-chats: the seats, then the records when the account has two or more people. A personal
    /// account calls nothing. A failure keeps the last records; a failed seat read hides everything.
    func load(session: AccountSession, api: BipeAPIClient, isCompany: Bool) async {
        if userId != session.userId {
            userId = session.userId
            seats = nil
            records = [:]
            filter = .all
            updating = []
            failed = []
            taken = []
        }
        guard isCompany else {
            seats = nil
            records = [:]
            self.api = nil
            return
        }
        let business = session.business(api)
        self.api = business
        guard let read = try? await business.seats(), userId == session.userId else {
            if userId == session.userId { seats = nil }
            return
        }
        seats = read
        guard isActive(userId: session.userId) else {
            records = [:]
            filter = .all
            return
        }
        await reloadRecords()
    }

    /// GET /v1/me/business/chats again.
    func reloadRecords() async {
        guard let api, let userId else { return }
        guard let chats = try? await api.chatAssignments(), self.userId == userId else { return }
        records = Dictionary(chats.map { ($0.roomId, $0) }, uniquingKeysWith: { _, last in last })
    }

    /// Action take: assignee my seat, expected the assignee last seen.
    @discardableResult
    func take(roomId: String) async -> Bool {
        guard let mySeatId else { return false }
        return await update(roomId: roomId, assignee: mySeatId, status: nil)
    }

    /// Action give.
    func give(roomId: String, to seatId: String) async {
        await update(roomId: roomId, assignee: seatId, status: nil)
    }

    /// Action release: back to the pool.
    func release(roomId: String) async {
        await update(roomId: roomId, assignee: nil, status: nil)
    }

    /// Action setStatus: the current assignee in both fields.
    func setStatus(roomId: String, _ status: BipeChatAssignmentStatus) async {
        await update(roomId: roomId, assignee: assignee(roomId: roomId), status: status)
    }

    /// PUT /v1/me/business/chats/{roomId}. 409 chat_taken reads the chats again and marks the chat taken.
    @discardableResult
    private func update(roomId: String, assignee: String?, status: BipeChatAssignmentStatus?) async -> Bool {
        guard let api, !updating.contains(roomId) else { return false }
        updating.insert(roomId)
        failed.remove(roomId)
        taken.remove(roomId)
        defer { updating.remove(roomId) }
        do throws(BipeAPIError) {
            let record = try await api.updateChatAssignment(roomId: roomId, assignee: assignee,
                                                            expected: self.assignee(roomId: roomId), status: status)
            records[roomId] = record
            return true
        } catch {
            if case .server(.chatTaken, _, _) = error {
                await reloadRecords()
                taken.insert(roomId)
            } else {
                failed.insert(roomId)
            }
            return false
        }
    }
}

/// The private rooms of an account that no chat list, search, share or forward shows: the drafts room and the notes
/// room of a work account.
@MainActor
enum AccountPrivateRooms {
    static func ids(userId: String) -> Set<String> {
        Set([AssistantDraftsStore.shared.roomId(for: userId), WorkNotesStore.shared.roomId(for: userId)].compactMap { $0 })
    }

    static func contains(_ roomId: String, userId: String) -> Bool {
        ids(userId: userId).contains(roomId)
    }
}

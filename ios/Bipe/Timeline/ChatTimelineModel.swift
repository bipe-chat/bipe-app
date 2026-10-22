// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UIKit

/// Type TimelineMessage of spec chat-timeline, with the cause of spec message-undecryptable.
struct TimelineMessage: Identifiable, Equatable {
    enum Kind: Equatable {
        case text
        /// Image, video, file or sticker (spec timeline-media).
        case media
        /// Voice message or audio file (spec voice-message).
        case voice
        case undecryptable
        case poll
        case unsupported
    }

    enum SendState: Equatable {
        case sent
        case sending
        case failed
    }

    /// Bipe's grouping of the SDK UtdCause (spec message-undecryptable).
    enum UndecryptableCause: Equatable {
        case waiting
        case beforeJoined
        case historyNotOnThisPhone
        case notSharedWithThisPhone
        case unapprovedSenderDevice
    }

    var id: String
    var kind: Kind
    var body: String?
    var senderId: String
    var senderName: String
    var isOwn: Bool
    var sentAt: Date
    var sendState: SendState
    var cause: UndecryptableCause?
    /// Kind poll: the data of spec poll-detail.
    var poll: PollData?
    /// Kind media.
    var media: MediaMessage? = nil
    /// Kind voice.
    var voice: VoiceMessage? = nil
}

/// Type MessageAction of spec message-actions, in the order of the sheet.
enum MessageAction: CaseIterable, Identifiable {
    case reply
    case edit
    case copy
    case retry
    case delete
    case report

    var id: Self { self }
}

/// Type MessageEdit of spec message-edit.
struct MessageEdit: Equatable {
    var rowId: String
    var originalText: String
}

/// State of spec message-report.
struct MessageReport: Equatable {
    var rowId: String
    var eventId: String
    var senderId: String
    var senderName: String
    var reason = ""
    var alsoBlock = false
    var isSending = false
    var error = false
}

/// One-time notices of the timeline specs.
enum TimelineNotice: Equatable {
    case copied
    case reactionFailed
    case editFailed
    case deleteFailed
    case reported

    var text: String {
        switch self {
        case .copied: BipeStrings.appCopied
        case .reactionFailed: BipeStrings.appReactionFailed
        case .editFailed: BipeStrings.appMessageEditFailed
        case .deleteFailed: BipeStrings.appMessageDeleteFailed
        case .reported: BipeStrings.appMessageReported
        }
    }
}

/// Specs chat-timeline, timeline-rows, message-actions, message-reply, message-reactions, message-edit,
/// message-delete, message-report, read-receipts and typing-indicator (bipe-shared product/screens).
@MainActor
final class ChatTimelineModel: ObservableObject {
    static let historyPage: UInt16 = 20
    /// Spec message-reactions notes: the quick reactions, in this order.
    static let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
    /// Spec message-reply openOriginal: at most 5 pages back.
    static let originalSearchPages = 5
    /// Spec typing-indicator: typing on at most once every 4 seconds.
    static let typingInterval: TimeInterval = 4

    // chat-timeline and timeline-rows
    @Published private(set) var roomName = ""
    @Published private(set) var rows: [TimelineRow] = []
    @Published private(set) var isLoadingHistory = false
    @Published private(set) var reachedStart = false
    @Published private(set) var isDm = false
    @Published private(set) var isEncrypted = true
    /// A DM or a group that needs an invite, for the show-media setting (spec timeline-media).
    @Published private(set) var isPrivateRoom = true
    @Published var draft = ""
    /// signup-done: the inviter while this chat has no first message (ADR 0034).
    @Published private(set) var inviterHint: BipeInviterGreeting?
    // message-actions
    @Published var actionsRowId: String?
    // message-reply
    @Published private(set) var replyingTo: MessageReply?
    @Published private(set) var replyingToRowId: String?
    @Published private(set) var scrollTarget: String?
    @Published private(set) var highlightedRowId: String?
    // message-reactions
    @Published var pickerOpenFor: String?
    @Published var reactionSheet: MessageReaction?
    // message-edit
    @Published private(set) var editing: MessageEdit?
    @Published private(set) var isSavingEdit = false
    // message-delete
    @Published var confirmDeleteRowId: String?
    // message-report
    @Published var report: MessageReport?
    // read-receipts
    @Published var readersSheet: [ReceiptReader]?
    // typing-indicator
    @Published private(set) var typingNames: [String] = []
    // One-time notices (copied, failures, reported).
    @Published private(set) var notice: TimelineNotice?

    let roomId: String
    private let session: AccountSession
    private var room: Room?
    private(set) var timeline: Timeline?
    private var items: [TimelineItem] = []
    private var handle: TaskHandle?
    private var typingHandle: TaskHandle?
    private var started = false
    private var facts: RoomFacts
    private var membersLoading = Set<String>()
    private var keptDraft = ""
    private var typingSentAt: Date?
    private var typingUserIds: [String] = []
    private var lastReadEventId: String?
    private var noticeTask: Task<Void, Never>?

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
        facts = RoomFacts(ownUserId: session.userId, isDm: false, isEncrypted: true, joinedMembers: 2,
                          canRedactOwn: true, canRedactOther: false, members: [:], replyLoadsTried: [])
    }

    deinit {
        handle?.cancel()
        typingHandle?.cancel()
    }

    /// Spec typing-indicator sharesTyping: the presence setting (bipe_privacy_presence). On unless turned off.
    var sharesTyping: Bool {
        UserDefaults.standard.object(forKey: SettingsKeys.sharePresence) as? Bool ?? true
    }

    var canSend: Bool {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        if let editing {
            return !isSavingEdit && text != editing.originalText
        }
        return true
    }

    /// The messages of the rows, oldest first (polls read their data here).
    var messages: [TimelineMessage] {
        rows.compactMap(\.message)
    }

    func row(_ id: String?) -> TimelineRow? {
        guard let id else { return nil }
        return rows.first { $0.id == id }
    }

    func start() async {
        guard !started else { return }
        started = true
        guard let room = try? session.client.getRoom(roomId: roomId) else { return }
        self.room = room
        roomName = room.displayName() ?? roomId
        let configuration = TimelineConfiguration(focus: .live(hideThreadedEvents: false),
                                                  filter: .all,
                                                  internalIdPrefix: nil,
                                                  dateDividerMode: .daily,
                                                  trackReadReceipts: .messageLikeEvents,
                                                  reportUtds: false)
        guard let timeline = try? await room.timelineWithConfiguration(configuration: configuration) else { return }
        self.timeline = timeline
        if let greeting = BipeInviteStore(userID: session.userId).inviterGreeting(),
           (try? session.client.getDmRoom(userId: greeting.userID))?.id() == roomId {
            inviterHint = greeting
        }
        await loadRoomFacts(room)
        let typing = TypingStream()
        typingHandle = room.subscribeToTypingNotifications(listener: typing)
        Task { [weak self] in
            for await userIds in typing.updates {
                self?.typingChanged(userIds)
            }
        }
        let stream = TimelineDiffStream()
        handle = await timeline.addListener(listener: stream)
        Task { [weak self] in await self?.loadHistory() }
        for await diffs in stream.updates {
            for diff in diffs {
                items.apply(diff.vectorDiff)
            }
            rebuild()
        }
    }

    /// Called when the screen goes away: typing off (spec typing-indicator draftChanged).
    func close() {
        sendTyping(false)
    }

    private func loadRoomFacts(_ room: Room) async {
        if let info = try? await room.roomInfo() {
            facts.isDm = info.isDm
            facts.joinedMembers = Int(info.joinedMembersCount)
        }
        if let state = try? await room.latestEncryptionState() {
            facts.isEncrypted = state != .notEncrypted
        }
        if let powerLevels = try? await room.getPowerLevels() {
            facts.canRedactOwn = powerLevels.canOwnUserRedactOwn()
            facts.canRedactOther = powerLevels.canOwnUserRedactOther()
        }
        isDm = facts.isDm
        isEncrypted = facts.isEncrypted
        isPrivateRoom = room.isPublic() != true
    }

    // MARK: chat-timeline

    /// Action loadHistory: back-paginates 20 events. Does nothing at the start or while a load runs.
    func loadHistory() async {
        guard let timeline, !reachedStart, !isLoadingHistory else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        if let hitStart = try? await timeline.paginateBackwards(numEvents: Self.historyPage), hitStart {
            reachedStart = true
        }
    }

    /// Action editDraft, with typing-indicator draftChanged.
    func editDraft(_ text: String) {
        draft = text
        guard editing == nil else { return }
        let isBlank = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if isBlank {
            sendTyping(false)
        } else if typingSentAt.map({ Date().timeIntervalSince($0) >= Self.typingInterval }) ?? true {
            sendTyping(true)
        }
    }

    /// Action send (chat-timeline), sendReply (message-reply) or saveEdit (message-edit).
    func send() async {
        if editing != nil {
            await saveEdit()
            return
        }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let timeline, !text.isEmpty,
              let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: text, formatted: nil))) else { return }
        draft = ""
        sendTyping(false)
        BipeHaptics.play(.tap)
        if let replyingTo {
            self.replyingTo = nil
            replyingToRowId = nil
            try? await timeline.sendReply(msg: content, eventId: replyingTo.eventId)
        } else {
            _ = try? await timeline.send(msg: content)
        }
    }

    /// The action of the inviter hint (spec signup-done notes): sends bipe_inviter_greeting.
    func sendGreeting() async {
        guard let timeline,
              let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: BipeStrings.inviterGreeting, formatted: nil)))
        else { return }
        _ = try? await timeline.send(msg: content)
    }

    /// Action retrySend: sends a failed own message again.
    func retrySend(id: String) async {
        guard let handle = item(id)?.asEvent()?.lazyProvider.getSendHandle() else { return }
        try? await handle.tryResend()
    }

    /// Action cancelUpload of spec timeline-media: cancels the send of own media and removes the row.
    func cancelUpload(id: String) async {
        guard let handle = item(id)?.asEvent()?.lazyProvider.getSendHandle() else { return }
        _ = try? await handle.abort()
    }

    // MARK: timeline-rows

    /// Action markAsRead: a public read receipt (ADR 0050) for the newest event, while the newest rows are on screen.
    func markAsRead() async {
        guard let timeline, UIApplication.shared.applicationState == .active,
              let newest = await timeline.latestEventId(), newest != lastReadEventId else { return }
        lastReadEventId = newest
        try? await timeline.markAsRead(receiptType: .read)
    }

    // MARK: message-actions

    /// Action open: date, notice and deleted rows have no sheet.
    func openActions(rowId: String) {
        guard let row = self.row(rowId), row.kind == .message || row.kind == .undecryptable,
              showsReactions(row) || !actions(for: row).isEmpty else { return }
        BipeHaptics.play(.hold)
        actionsRowId = rowId
    }

    /// The actions that apply to a row (spec message-actions notes).
    func actions(for row: TimelineRow) -> [MessageAction] {
        guard let message = row.message else { return [] }
        let isSent = message.sendState == .sent && row.details?.eventId != nil
        if row.kind == .undecryptable {
            return !message.isOwn && isSent ? [.report] : []
        }
        guard row.kind == .message, let details = row.details else { return [] }
        return MessageAction.allCases.filter { action in
            switch action {
            case .reply: details.canReply && isSent
            case .edit: details.canEdit
            // Media copies its caption, voice its transcript (specs timeline-media, voice-message).
            case .copy: message.kind == .text || message.media?.caption?.nilIfBlank != nil || message.voice?.transcript != nil
            case .retry: message.isOwn && message.sendState == .failed
            case .delete: details.canDelete || (message.isOwn && !isSent)
            case .report: !message.isOwn && isSent
            }
        }
    }

    /// Spec message-actions showsReactions: messages that take reactions (message-reactions notes).
    func showsReactions(_ row: TimelineRow) -> Bool {
        row.kind == .message && row.details?.eventId != nil && row.message?.sendState == .sent
    }

    /// Action choose.
    func choose(_ action: MessageAction, rowId: String) {
        actionsRowId = nil
        switch action {
        case .reply: startReply(rowId: rowId)
        case .edit: startEdit(rowId: rowId)
        case .copy:
            UIPasteboard.general.string = row(rowId)?.message?.body
            BipeHaptics.play(.tap)
            show(.copied)
        case .retry: Task { await retrySend(id: rowId) }
        case .delete: askDelete(rowId: rowId)
        case .report: openReport(rowId: rowId)
        }
    }

    // MARK: message-reply

    /// Action startReply: also runs from a swipe on the message. Keeps the draft.
    func startReply(rowId: String) {
        guard let row = self.row(rowId), let details = row.details, details.canReply,
              let eventId = details.eventId, let message = row.message else { return }
        if editing != nil { cancelEdit() }
        replyingTo = MessageReply(eventId: eventId, state: .ready,
                                  senderName: message.isOwn ? BipeStrings.appYou : message.senderName,
                                  preview: message.body)
        replyingToRowId = rowId
    }

    /// Action cancelReply: the draft stays.
    func cancelReply() {
        replyingTo = nil
        replyingToRowId = nil
    }

    /// Action openOriginal: scrolls to the original, back-paginating at most 5 pages to find it.
    func openOriginal(eventId: String) async {
        for page in 0...Self.originalSearchPages {
            if let target = rows.first(where: { $0.details?.eventId == eventId }) {
                scrollTarget = target.id
                highlightedRowId = target.id
                try? await Task.sleep(for: .seconds(1.5))
                if highlightedRowId == target.id { highlightedRowId = nil }
                return
            }
            guard page < Self.originalSearchPages, !reachedStart else { return }
            await loadHistory()
            // The new items reach rows through the timeline listener.
            try? await Task.sleep(for: .milliseconds(300))
        }
    }

    func scrolledToTarget() {
        scrollTarget = nil
    }

    /// Action loadReply: asks the SDK once for the original of a quote that came on screen without details.
    func loadReply(rowId: String) async {
        guard let timeline, let details = row(rowId)?.details, let reply = details.reply,
              reply.state == .loading, let eventId = details.eventId,
              !facts.replyLoadsTried.contains(reply.eventId) else { return }
        facts.replyLoadsTried.insert(reply.eventId)
        try? await timeline.fetchDetailsForEvent(eventId: eventId)
        rebuild()
    }

    // MARK: message-reactions

    /// Action toggle: adds or removes this account's reaction with that key.
    func toggleReaction(rowId: String, key: String) async {
        guard let timeline, let event = item(rowId)?.asEvent() else { return }
        BipeHaptics.play(.tick)
        do {
            _ = try await timeline.toggleReaction(itemId: event.eventOrTransactionId, key: key)
        } catch {
            show(.reactionFailed)
        }
    }

    /// Action react of message-actions: toggle, then the sheet closes.
    func react(rowId: String, key: String) {
        actionsRowId = nil
        Task { await toggleReaction(rowId: rowId, key: key) }
    }

    /// Action moreReactions of message-actions.
    func moreReactions(rowId: String) {
        actionsRowId = nil
        pickerOpenFor = rowId
    }

    /// A chosen emoji of the picker runs toggle.
    func pickEmoji(_ key: String) {
        guard let rowId = pickerOpenFor else { return }
        pickerOpenFor = nil
        Task { await toggleReaction(rowId: rowId, key: key) }
    }

    /// Action openSenders: a long press on a pill.
    func openSenders(rowId: String, key: String) {
        reactionSheet = row(rowId)?.details?.reactions.first { $0.key == key }
    }

    // MARK: message-edit

    /// Action startEdit: the message text goes into the composer, the unsent draft is kept aside. Ends a reply first.
    func startEdit(rowId: String) {
        guard let row = self.row(rowId), row.details?.canEdit == true, let body = row.message?.body else { return }
        cancelReply()
        if editing == nil { keptDraft = draft }
        sendTyping(false)
        editing = MessageEdit(rowId: rowId, originalText: body)
        draft = body
    }

    /// Action cancelEdit: puts the kept draft back.
    func cancelEdit() {
        editing = nil
        draft = keptDraft
        keptDraft = ""
    }

    /// Action saveEdit.
    func saveEdit() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let editing, let timeline, canSend, let event = item(editing.rowId)?.asEvent(),
              let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: text, formatted: nil))) else { return }
        isSavingEdit = true
        defer { isSavingEdit = false }
        do {
            try await timeline.edit(eventOrTransactionId: event.eventOrTransactionId, newContent: .roomMessage(content: content))
            cancelEdit()
        } catch {
            show(.editFailed)
        }
    }

    // MARK: message-delete

    /// Action askDelete: an own message that is not sent yet is dropped without the question.
    func askDelete(rowId: String) {
        guard let message = row(rowId)?.message else { return }
        if message.isOwn, message.sendState != .sent {
            Task {
                if let handle = item(rowId)?.asEvent()?.lazyProvider.getSendHandle() {
                    _ = try? await handle.abort()
                }
            }
            return
        }
        confirmDeleteRowId = rowId
    }

    /// Action confirmDelete: deletes for everyone, without a reason.
    func confirmDelete() async {
        guard let rowId = confirmDeleteRowId, let timeline, let event = item(rowId)?.asEvent() else { return }
        confirmDeleteRowId = nil
        do {
            try await timeline.redactEvent(eventOrTransactionId: event.eventOrTransactionId, reason: nil)
        } catch {
            show(.deleteFailed)
        }
    }

    // MARK: message-report

    private func openReport(rowId: String) {
        guard let message = row(rowId)?.message, let event = item(rowId)?.asEvent(),
              let eventId = TimelineRowBuilder.eventId(event) else { return }
        report = MessageReport(rowId: rowId, eventId: eventId, senderId: message.senderId, senderName: message.senderName)
    }

    /// Action send of message-report: the report, then the block when alsoBlock is on.
    func sendReport() async {
        guard let room, var report = self.report, !report.isSending else { return }
        report.isSending = true
        report.error = false
        self.report = report
        let reason = report.reason.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try await room.reportContent(eventId: report.eventId, reason: reason.isEmpty ? nil : reason)
        } catch {
            report.isSending = false
            report.error = true
            self.report = report
            return
        }
        if report.alsoBlock {
            // A failed block still counts the report as sent (spec message-report notes).
            try? await session.client.ignoreUser(userId: report.senderId)
        }
        self.report = nil
        show(.reported)
    }

    // MARK: read-receipts

    /// Action openReaders: group only.
    func openReaders(rowId: String) {
        guard !isDm, let receipt = row(rowId)?.details?.receipt, !receipt.readersUpToHere.isEmpty else { return }
        readersSheet = receipt.readersUpToHere
    }

    // MARK: typing-indicator

    private func typingChanged(_ userIds: [String]) {
        guard sharesTyping else {
            typingNames = []
            return
        }
        typingUserIds = userIds.filter { $0 != session.userId }
        typingNames = typingUserIds.map { facts.members[$0]?.name ?? $0 }
        loadMembers(Set(typingUserIds).subtracting(facts.members.keys))
    }

    private func sendTyping(_ isTyping: Bool) {
        guard let room, sharesTyping else { return }
        if isTyping {
            typingSentAt = Date()
        } else {
            guard typingSentAt != nil else { return }
            typingSentAt = nil
        }
        Task { try? await room.typingNotice(isTyping: isTyping) }
    }

    // MARK: Rows

    private func item(_ rowId: String) -> TimelineItem? {
        items.first { $0.uniqueId().id == rowId }
    }

    private func rebuild() {
        let built = TimelineRowBuilder.rows(items: items, facts: facts)
        rows = built.rows
        if built.hitStart {
            reachedStart = true
        }
        if let hint = inviterHint {
            let messages = built.rows.compactMap(\.message)
            if messages.contains(where: { $0.isOwn && $0.kind != .unsupported }) {
                BipeInviteStore(userID: session.userId).setInviterGreeting(nil)
                inviterHint = nil
            } else if messages.contains(where: { $0.senderId == hint.userID && $0.kind != .unsupported }) {
                inviterHint = nil
            }
        }
        loadMembers(built.unknownUsers)
    }

    /// Display names and avatars of members for receipts, reactions and typing.
    private func loadMembers(_ userIds: Set<String>) {
        let missing = userIds.subtracting(membersLoading)
        guard let room, !missing.isEmpty else { return }
        membersLoading.formUnion(missing)
        Task {
            for userId in missing {
                let name = (try? await room.memberDisplayName(userId: userId)) ?? nil
                let avatarUrl = (try? await room.memberAvatarUrl(userId: userId)) ?? nil
                facts.members[userId] = (name.flatMap { $0.isEmpty ? nil : $0 } ?? userId, avatarUrl)
            }
            typingNames = typingUserIds.map { facts.members[$0]?.name ?? $0 }
            rebuild()
        }
    }

    private func show(_ notice: TimelineNotice) {
        self.notice = notice
        noticeTask?.cancel()
        noticeTask = Task {
            try? await Task.sleep(for: .seconds(2))
            if !Task.isCancelled { self.notice = nil }
        }
    }
}

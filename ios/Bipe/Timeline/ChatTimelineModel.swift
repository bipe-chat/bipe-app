// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
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
        /// m.location (spec location-view).
        case location
        /// MSC3489 live location share (spec location-view, live row).
        case liveLocation
        case unsupported
    }

    enum SendState: Equatable {
        case sent
        case sending
        case failed
    }

    /// Spec identity-change sendAnyway: the send queue held the message because of a member's identity.
    enum SendFailure: Equatable {
        /// QueueWedgeError identityViolations: users that were verified and are not any more.
        case identity(users: [String])
        /// QueueWedgeError insecureDevices: user ID to device IDs.
        case devices([String: [String]])
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
    /// sendState failed only.
    var sendFailure: SendFailure? = nil
    var cause: UndecryptableCause?
    /// Kind poll: the data of spec poll-detail.
    var poll: PollData?
    /// Kind media.
    var media: MediaMessage? = nil
    /// Kind voice.
    var voice: VoiceMessage? = nil
    /// Kind location.
    var location: LocationMessage? = nil
    /// Kind liveLocation.
    var live: LiveLocationMessage? = nil
    /// m.emote has a display prefix; body stays unchanged for edits and copying.
    var isEmote = false
    /// Native, sanitized text runs. The original body stays available to edit and copy.
    var richText: BipeMessageText? = nil
}

/// Type MessageAction of spec message-actions, in the order of the sheet.
enum MessageAction: CaseIterable, Identifiable {
    case reply
    case replyInThread
    case edit
    case removeCaption
    case copy
    case pin
    case unpin
    case forward
    /// Spec message-forward startSelection (bipe#860).
    case select
    case saveSticker
    case keepAudio
    case unkeepAudio
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
    case pinFailed
    case deleteFailed
    case reported
    case inviteAgainFailed
    case keepLimit
    case keepFailed
    case forwarded
    case forwardFailed(String)
    case selectionForwarded
    case selectionLimit
    case selectionDeleteFailed
    case stickerSaved
    case stickerSaveFailed

    var text: String {
        switch self {
        case .forwarded: BipeStrings.forwardDone
        case .forwardFailed(let names): BipeStrings.forwardFailed(names)
        case .selectionForwarded: BipeStrings.selectForwardDone
        case .selectionLimit: BipeStrings.selectLimit(String(ChatTimelineModel.selectionLimit))
        case .selectionDeleteFailed: BipeStrings.selectDeleteFailed
        case .stickerSaved: BipeStrings.stickerSaved
        case .stickerSaveFailed: BipeStrings.stickerSaveFailed
        case .copied: BipeStrings.appCopied
        case .keepLimit: BipeStrings.voiceKeepLimit(String(BipeRegistries.voiceMediaMaxKeptPerAccount))
        case .keepFailed: BipeStrings.errorGeneric
        case .reactionFailed: BipeStrings.appReactionFailed
        case .editFailed: BipeStrings.appMessageEditFailed
        case .pinFailed: BipeStrings.errorGeneric
        case .deleteFailed: BipeStrings.appMessageDeleteFailed
        case .reported: BipeStrings.appMessageReported
        case .inviteAgainFailed: BipeStrings.dmInviteAgainFailed
        }
    }
}

/// Specs chat-timeline, timeline-rows, message-actions, message-reply, message-reactions, message-edit,
/// message-delete, message-report, read-receipts and typing-indicator (bipe-shared product/screens).
@MainActor
final class ChatTimelineModel: ObservableObject {
    enum PeerAway: Equatable {
        case none
        case left
        case accountGone
    }

    static let historyPage: UInt16 = 20
    /// One loadHistory pages on until it adds this many message rows (state events give no rows).
    static let historyFill = 10
    /// The most pages one loadHistory requests.
    static let historyPagesPerLoad = 5
    /// The tries of one page before the load stops: a fresh login can fail the first pages during the first sync.
    static let historyTries = 3
    /// Spec message-reactions notes: the quick reactions, in this order.
    static let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
    /// Spec message-reply openOriginal: at most 5 pages back.
    static let originalSearchPages = 5
    /// Spec typing-indicator: typing on at most once every 4 seconds.
    static let typingInterval: TimeInterval = 4
    /// Spec message-forward selection: at most this many messages.
    nonisolated static let selectionLimit = 10

    // chat-timeline and timeline-rows
    @Published private(set) var roomName = BipeStrings.dmNoName
    @Published private(set) var roomAvatarUrl: String?
    @Published private(set) var peerUserId: String?
    /// Spec chat-timeline peerAway (ADR 0141): the other person of a DM left, or the account is gone.
    @Published private(set) var peerAway: PeerAway = .none
    @Published private(set) var invitingAgain = false
    /// Spec chat-timeline theftMark: a valid theft statement for the other person of a DM, else nil.
    @Published private(set) var theftMark: BipeTheftStatement?
    private var theftTask: Task<Void, Never>?
    /// The person whose profile was checked for accountGone, so the lookup runs once per leave.
    private var accountGoneChecked: (userId: String, gone: Bool)?
    @Published private(set) var isGroup = false
    @Published private(set) var rows: [TimelineRow] = []
    @Published private(set) var isLoadingHistory = false
    @Published private(set) var reachedStart = false
    /// Goes up after each load that did not fail, so the view asks for the next page while the top is on screen.
    @Published private(set) var historyLoads = 0
    @Published private(set) var isDm = false
    @Published private(set) var isEncrypted = true
    /// Spec call: a call runs in the room (RoomInfo.hasRoomCall).
    @Published private(set) var hasCall = false
    /// Spec chat-timeline notes, upgraded groups (bipe#773): RoomInfo.successorRoom.
    @Published private(set) var successorRoomId: String?
    /// RoomInfo.predecessorRoom, with the old group's name.
    @Published private(set) var predecessor: (roomId: String, name: String)?
    /// A DM or a group that needs an invite, for the show-media setting (spec timeline-media).
    @Published private(set) var isPrivateRoom = true
    @Published var draft = ""
    // composer
    /// The pills in the draft, in order.
    @Published private(set) var mentions: [ComposerMention] = []
    /// @ suggestions in a group; empty hides the list.
    @Published private(set) var suggestions: [MentionSuggestion] = []
    /// mode recording: voice-recording replaces the field.
    @Published var recording: VoiceRecordingModel?
    /// false: the composer is off, with the reason bar instead.
    @Published private(set) var canPost = true
    /// The poll row of attachment-picker. Never in a DM (ADR 0192).
    @Published private(set) var canPoll = true
    /// Spec message-pins (bipe#665): RoomPowerLevels.canOwnUserPinUnpin, groups only.
    @Published private(set) var canPin = false
    /// RoomInfo.pinnedEventIds.
    @Published private(set) var pinnedEventIds: Set<String> = []
    private let mentionDirectory = MentionDirectory()
    /// signup-done: the inviter while this chat has no first message (ADR 0034).
    @Published private(set) var inviterHint: BipeInviterGreeting?
    // message-actions
    @Published var actionsRowId: String?
    /// Spec message-forward: the messages the picker is open for.
    @Published var forwardingRowIds: [String]?
    /// Spec message-forward selection (bipe#860): the picked rows in tap order. nil while the mode is off.
    @Published private(set) var selection: [String]?
    /// Spec message-forward deleteSelection: the dialog is open.
    @Published var confirmsDeleteSelection = false
    /// Spec scam-warning money pause: one per message while this chat is open.
    @Published private(set) var moneyPause = BipeMoneyPause()
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
    private var acceptsMenuActions = true
    private var facts: RoomFacts
    private var membersLoading = Set<String>()
    private var keptDraft = ""
    private var typingSentAt: Date?
    private var typingUserIds: [String] = []
    private var handleChangesTask: Task<Void, Never>?
    private var lastReadEventId: String?
    private var noticeTask: Task<Void, Never>?

    /// Spec threads (bipe#667): the root event of the thread view; nil for the main timeline.
    let threadRoot: String?
    /// The thread the main timeline asks the view to open (spec threads openThread).
    @Published var openThreadRoot: String?

    init(session: AccountSession, roomId: String, threadRoot: String? = nil) {
        self.session = session
        self.roomId = roomId
        self.threadRoot = threadRoot
        facts = RoomFacts(ownUserId: session.userId, isDm: false, isEncrypted: true, joinedMembers: 2,
                          canRedactOwn: true, canRedactOther: false, members: [:], replyLoadsTried: [])
    }

    deinit {
        handle?.cancel()
        typingHandle?.cancel()
        handleChangesTask?.cancel()
        theftTask?.cancel()
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
        // Spec threads notes: a group's main timeline hides thread replies; a DM shows them as plain messages.
        let isDmRoom = (try? await room.roomInfo())?.isDm ?? false
        let focus: TimelineFocus = threadRoot.map { .thread(rootEventId: $0) } ?? .live(hideThreadedEvents: !isDmRoom)
        let configuration = TimelineConfiguration(focus: focus,
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
        // Action appear of spec composer: a saved newMessage draft fills the field.
        if draft.isEmpty, let saved = await room.newMessageDraft(threadRoot: threadRoot) {
            draft = saved
        }
        if !facts.isDm {
            Task { [weak self] in
                guard let self else { return }
                await mentionDirectory.load(room: room, ownUserId: session.userId)
            }
        }
        await loadHandleChanges(room)
        watchTheftMark()
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
            await loadRoomFacts(room)
            rebuild()
        }
    }

    /// Called when the screen goes away: typing off (spec typing-indicator draftChanged).
    func resumeMenuActions() { acceptsMenuActions = true }

    func close() {
        acceptsMenuActions = false
        actionsRowId = nil
        pickerOpenFor = nil
        cancelSelection()
        sendTyping(false)
        leave()
    }

    /// Action leave of spec composer: keeps the draft on this device, or clears it.
    func leave() {
        guard let room, editing == nil else { return }
        let text = draft
        Task { [threadRoot] in await room.keepDraft(text, threadRoot: threadRoot) }
    }

    /// The room, for spec call.
    var matrixRoom: Room? { room }

    /// The client, for upload-preview and sticker-picker.
    var client: Client { session.client }
    /// Expiry and keep state of this account's voice messages (ADR 0164).
    var voiceMedia: VoiceMediaStore { VoiceMediaStore.store(for: session) }

    /// The reply target for a media, voice or sticker send; the reply bar closes.
    func takeReplyEventId() -> String? {
        let eventId = replyingTo?.eventId
        replyingTo = nil
        replyingToRowId = nil
        return eventId
    }

    private func loadRoomFacts(_ room: Room) async {
        if let info = try? await room.roomInfo() {
            facts.isDm = info.isDm
            facts.joinedMembers = Int(info.joinedMembersCount)
            isGroup = !info.isDm && !info.isSpace
            let peer = info.isDm ? info.heroes.first(where: { $0.userId != session.userId }) : nil
            let peerName = peer?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = peerName.flatMap { $0.isEmpty ? nil : $0 } ?? peer.map { Person.localpart(of: $0.userId) } ?? BipeStrings.dmNoName
            roomName = info.isDm ? name : (info.displayName ?? info.id)
            roomAvatarUrl = info.isDm ? peer?.avatarUrl : info.avatarUrl
            peerUserId = peer?.userId
            hasCall = info.hasRoomCall
            successorRoomId = info.successorRoom?.roomId
            if let old = room.predecessorRoom()?.roomId, predecessor?.roomId != old {
                let name = (try? session.client.getRoom(roomId: old))?.displayName()?.nilIfBlank
                predecessor = (old, name ?? BipeStrings.groupCommonNoRoomName)
            }
            pinnedEventIds = Set(info.pinnedEventIds)
            await loadPeerAway(room, peer: peer?.userId)
        }
        if let state = try? await room.latestEncryptionState() {
            facts.isEncrypted = state != .notEncrypted
        }
        if let powerLevels = try? await room.getPowerLevels() {
            facts.canRedactOwn = powerLevels.canOwnUserRedactOwn()
            facts.canRedactOther = powerLevels.canOwnUserRedactOther()
            canPost = powerLevels.canOwnUserSendMessage(message: .roomMessage)
            canPoll = !facts.isDm && powerLevels.canOwnUserSendMessage(message: .pollStart)
            canPin = !facts.isDm && powerLevels.canOwnUserPinUnpin()
        }
        isDm = facts.isDm
        isEncrypted = facts.isEncrypted
        isPrivateRoom = room.isPublic() != true
    }

    // MARK: chat-timeline

    /// Action loadHistory: back-paginates 20 events at a time until the rows have historyFill more messages,
    /// at most maxPages pages. A failed page is tried again. Does nothing at the start or while a load runs.
    func loadHistory(maxPages: Int = ChatTimelineModel.historyPagesPerLoad) async {
        guard let timeline, !reachedStart, !isLoadingHistory else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        let before = messages.count
        for _ in 0..<maxPages {
            guard let hitStart = await paginateBackwards(timeline) else { return }
            if hitStart {
                reachedStart = true
                break
            }
            // The new items reach rows through the timeline listener, so this count can lag one page.
            if messages.count - before >= Self.historyFill { break }
        }
        historyLoads += 1
    }

    /// One page back, tried historyTries times. nil when every try failed.
    private func paginateBackwards(_ timeline: Timeline) async -> Bool? {
        for attempt in 1...Self.historyTries {
            if let hitStart = try? await timeline.paginateBackwards(numEvents: Self.historyPage) {
                return hitStart
            }
            if attempt < Self.historyTries {
                try? await Task.sleep(for: .seconds(attempt))
            }
        }
        return nil
    }

    /// Action editDraft, with typing-indicator draftChanged.
    func editDraft(_ text: String) {
        draft = text
        guard editing == nil else { return }
        // Spec composer editText: pills that left the text go; an @word in a group filters the members.
        mentions = ComposerMarkdown.mentions(mentions, in: text)
        if !isDm, let query = ComposerMarkdown.mentionQuery(text) {
            suggestions = mentionDirectory.suggestions(query, everyoneLabel: BipeStrings.appComposerMentionEveryone)
        } else {
            suggestions = []
        }
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
        guard let timeline, !text.isEmpty else { return }
        // Spec composer send: Markdown with the pills as matrix.to links, then the mentions.
        let content = ComposerMarkdown.content(text: text, mentions: mentions)
        draft = ""
        mentions = []
        suggestions = []
        if let room { Task { [threadRoot] in try? await room.clearComposerDraft(threadRoot: threadRoot) } }
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

    /// Action pickSuggestion of spec composer: the @word becomes a pill.
    func pickSuggestion(_ suggestion: MentionSuggestion) {
        let inserted = ComposerMarkdown.insert(suggestion, into: draft, everyoneLabel: BipeStrings.appComposerMentionEveryone)
        mentions.append(inserted.mention)
        draft = inserted.text
        suggestions = []
    }

    /// Action startRecording of spec composer.
    func startRecording() {
        guard let timeline, recording == nil, let model = try? VoiceRecordingModel(timeline: timeline, replyToEventId: takeReplyEventId()) else { return }
        recording = model
    }

    /// The action of the inviter hint (spec signup-done notes): sends bipe_inviter_greeting.
    func sendGreeting() async {
        guard let timeline,
              let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: BipeStrings.inviterGreeting, formatted: nil)))
        else { return }
        _ = try? await timeline.send(msg: content)
    }

    /// Action retrySend: sends a failed own message again. A message held for an identity change is sent anyway
    /// (spec identity-change sendAnyway).
    func retrySend(id: String) async {
        guard let handle = item(id)?.asEvent()?.lazyProvider.getSendHandle() else { return }
        switch row(id)?.message?.sendFailure {
        case .identity(let users)?:
            try? await room?.withdrawVerificationAndResend(userIds: users, sendHandle: handle)
        case .devices(let devices)?:
            try? await room?.ignoreDeviceTrustAndResend(devices: devices, sendHandle: handle)
        case nil:
            try? await handle.tryResend()
        }
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
        guard acceptsMenuActions, let row = self.row(rowId), row.kind == .message || row.kind == .undecryptable,
              showsReactions(row) || !actions(for: row).isEmpty else { return }
        BipeHaptics.play(.hold)
        actionsRowId = rowId
    }

    /// The actions that apply to a row (spec message-actions notes).
    func actions(for row: TimelineRow) -> [MessageAction] {
        guard let message = row.message else { return [] }
        if row.kind == .undecryptable {
            // Undecryptable rows have no MessageDetails. Resolve the original SDK event instead.
            let eventId = item(row.id)?.asEvent().flatMap { TimelineRowBuilder.eventId($0) }
            let isSent = message.sendState == .sent && eventId != nil
            return !message.isOwn && isSent ? [.report] : []
        }
        let isSent = message.sendState == .sent && row.details?.eventId != nil
        guard row.kind == .message, let details = row.details else { return [] }
        return MessageAction.allCases.filter { action in
            switch action {
            case .reply: details.canReply && isSent
            // Spec threads: groups only, not inside a thread view, not a reply that is in a thread already.
            case .replyInThread: !isDm && threadRoot == nil && isSent && !details.inThread
            case .edit: details.canEdit
            // Spec message-edit removeCaption (bipe#668).
            case .removeCaption: details.canEdit && message.media?.caption?.nilIfBlank != nil
            // Media copies its caption, voice its transcript (specs timeline-media, voice-message).
            case .copy: message.kind == .text || message.media?.caption?.nilIfBlank != nil || message.voice?.transcript != nil
            // Spec message-pins: groups only, sent messages.
            case .pin: canPin && isSent && !pinnedEventIds.contains(details.eventId ?? "")
            case .unpin: canPin && isSent && pinnedEventIds.contains(details.eventId ?? "")
            // Spec message-forward notes: sent messages of kind message; no stickers or polls.
            case .forward: isSent && forwardContent(rowId: row.id) != nil
            case .select: canSelect(row)
            // Spec message-actions notes (bipe#670): every sent sticker.
            case .saveSticker: isSent && message.media?.kind == .sticker
            // Spec message-actions notes (ADR 0164): sent voice messages whose audio is still on the server.
            case .keepAudio: keepable(message, isSent: isSent) && voiceMedia.kept[message.voice!.source.url] == nil
            case .unkeepAudio: message.voice.map { voiceMedia.kept[$0.source.url] != nil } ?? false
            case .retry: message.isOwn && message.sendState == .failed
            case .delete: details.canDelete || (message.isOwn && !isSent)
            case .report: !message.isOwn && isSent
            }
        }
    }

    private func keepable(_ message: TimelineMessage, isSent: Bool) -> Bool {
        guard let voice = message.voice, voice.isVoice, isSent else { return false }
        return !VoiceMediaExpiry.shared.isExpired(voice)
    }

    /// Spec message-actions showsReactions: messages that take reactions (message-reactions notes).
    func showsReactions(_ row: TimelineRow) -> Bool {
        row.kind == .message && row.details?.eventId != nil && row.message?.sendState == .sent
    }

    /// Spec scam-warning startPause: the first time the card of a money kind shows.
    func startMoneyPause(rowId: String, kind: ScamWarningKind) {
        guard BipeMoneyPause.applies(to: kind), moneyPause.start(id: rowId, kind: kind) else { return }
        // Screen readers hear the countdown once, at the start (spec scam-warning notes).
        UIAccessibility.post(notification: .announcement,
                             argument: BipeStrings.pauseCountdown(String(BipeRegistries.scamMoneyPauseSeconds)))
    }

    /// Seconds before Copiar works again on this row (0: no pause).
    func pauseSecondsLeft(rowId: String, now: Date = .now) -> Int {
        moneyPause.secondsLeft(id: rowId, now: now)
    }

    /// The menu title of an action: the countdown for a paused copy.
    func menuTitle(_ action: MessageAction, rowId: String) -> String {
        if action == .edit, let media = row(rowId)?.message?.media {
            return media.caption?.nilIfBlank == nil ? BipeStrings.captionAdd : BipeStrings.captionEdit
        }
        if action == .retry, row(rowId)?.message?.sendFailure != nil { return BipeStrings.identityChangeSendAnyway }
        let left = action == .copy ? pauseSecondsLeft(rowId: rowId) : 0
        return left > 0 ? BipeStrings.pauseCountdown(String(left)) : action.menuTitle
    }

    /// Action choose.
    func choose(_ action: MessageAction, rowId: String) {
        actionsRowId = nil
        guard acceptsMenuActions, let current = row(rowId), actions(for: current).contains(action) else { return }
        switch action {
        case .reply: startReply(rowId: rowId)
        case .replyInThread: openThreadRoot = current.details?.eventId
        case .edit: startEdit(rowId: rowId)
        case .removeCaption: Task { await removeCaption(rowId: rowId) }
        case .copy:
            guard pauseSecondsLeft(rowId: rowId) == 0 else { return }
            UIPasteboard.general.string = row(rowId)?.message?.body
            BipeHaptics.play(.tap)
            show(.copied)
        case .keepAudio, .unkeepAudio:
            guard let url = current.message?.voice?.source.url else { return }
            let store = voiceMedia
            Task {
                if action == .unkeepAudio {
                    if await !store.unkeep(url: url) { self.show(.keepFailed) }
                    return
                }
                switch await store.keep(url: url, reason: .kept) {
                case .done: BipeHaptics.play(.tap)
                case .limit: self.show(.keepLimit)
                case .failed: self.show(.keepFailed)
                }
            }
        case .forward: forwardingRowIds = [rowId]
        case .select: startSelection(rowId: rowId)
        case .pin, .unpin:
            guard let timeline, let eventId = current.details?.eventId else { return }
            Task {
                do {
                    if action == .pin {
                        _ = try await timeline.pinEvent(eventId: eventId)
                    } else {
                        _ = try await timeline.unpinEvent(eventId: eventId)
                    }
                    BipeHaptics.play(.tap)
                } catch {
                    show(.pinFailed)
                }
            }
        case .saveSticker:
            guard let sticker = current.message?.media, let packClient = StickerPickerModel.packClient(client) else { return }
            Task {
                do {
                    _ = try await packClient.saveToUserPack(url: sticker.source.url, width: sticker.width, height: sticker.height,
                                                            mimeType: sticker.mimeType, size: nil)
                    BipeHaptics.play(.tap)
                    show(.stickerSaved)
                } catch {
                    show(.stickerSaveFailed)
                }
            }
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

    /// Spec drafts edit: an assistant draft becomes the composer text, with message-reply for its replyTo.
    func takeAssistantDraft(body: String, replyTo: String?) async {
        if editing != nil { cancelEdit() }
        editDraft(body)
        guard let replyTo else { return }
        if let row = rows.first(where: { $0.details?.eventId == replyTo }), let message = row.message {
            replyingTo = MessageReply(eventId: replyTo, state: .ready,
                                      senderName: message.isOwn ? BipeStrings.appYou : message.senderName,
                                      preview: message.body)
            replyingToRowId = row.id
        } else if let event = try? await timeline?.getEventTimelineItemByEventId(eventId: replyTo) {
            var preview: String?
            if case .msgLike(let content) = event.content, case .message(let message) = content.kind { preview = message.body }
            replyingTo = MessageReply(eventId: replyTo, state: .ready,
                                      senderName: event.isOwn ? BipeStrings.appYou : event.senderProfile.displayName(or: event.sender),
                                      preview: preview)
            replyingToRowId = nil
        } else {
            replyingTo = MessageReply(eventId: replyTo, state: .unavailable)
            replyingToRowId = nil
        }
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
            await loadHistory(maxPages: 1)
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
        guard acceptsMenuActions, let current = row(rowId), showsReactions(current) else { return }
        actionsRowId = nil
        Task { await toggleReaction(rowId: rowId, key: key) }
    }

    /// Action moreReactions of message-actions.
    func moreReactions(rowId: String) {
        guard acceptsMenuActions, let current = row(rowId), showsReactions(current) else { return }
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
        guard let row = self.row(rowId), row.details?.canEdit == true, let message = row.message,
              let body = message.media.map({ $0.caption ?? "" }) ?? message.body else { return }
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
        guard let editing, let timeline, canSend, let event = item(editing.rowId)?.asEvent() else { return }
        let newContent: EditedContent
        if row(editing.rowId)?.message?.media != nil {
            // A caption (bipe#668).
            newContent = .mediaCaption(caption: text, formattedCaption: nil, mentions: nil)
        } else {
            guard let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: text, formatted: nil))) else { return }
            newContent = .roomMessage(content: content)
        }
        isSavingEdit = true
        defer { isSavingEdit = false }
        do {
            try await timeline.edit(eventOrTransactionId: event.eventOrTransactionId, newContent: newContent)
            cancelEdit()
        } catch {
            show(.editFailed)
        }
    }

    /// Action removeCaption of spec message-edit: no question.
    func removeCaption(rowId: String) async {
        guard let timeline, let event = item(rowId)?.asEvent() else { return }
        do {
            try await timeline.edit(eventOrTransactionId: event.eventOrTransactionId,
                                    newContent: .mediaCaption(caption: nil, formattedCaption: nil, mentions: nil))
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

    // MARK: message-forward

    /// The original message type of a row, sent again as it is (spec message-forward send).
    func forwardContent(rowId: String) -> MessageType? {
        guard case .msgLike(let content)? = item(rowId)?.asEvent()?.content,
              case .message(let message) = content.kind else { return nil }
        return message.msgType
    }

    /// The message types of rows, in timeline order (spec message-forward send).
    func forwardContents(rowIds: [String]) -> [MessageType] {
        let wanted = Set(rowIds)
        return rows.filter { wanted.contains($0.id) }.compactMap { forwardContent(rowId: $0.id) }
    }

    /// The picker closed: nil when cancelled, else the names of the chats that failed.
    func forwardClosed(failed: [String]?) {
        let count = forwardingRowIds?.count ?? 0
        forwardingRowIds = nil
        guard let failed else { return }
        // A sent forward ends selection mode; a cancelled picker keeps it.
        cancelSelection()
        if failed.isEmpty {
            BipeHaptics.play(.tap)
            show(count > 1 ? .selectionForwarded : .forwarded)
        } else {
            show(.forwardFailed(failed.joined(separator: ", ")))
        }
    }

    // MARK: message-forward selection (bipe#860)

    /// A selectable row: a sent message that can be forwarded, or an own message the account can delete.
    func canSelect(_ row: TimelineRow) -> Bool {
        guard row.kind == .message, let message = row.message, message.sendState == .sent,
              row.details?.eventId != nil else { return false }
        return forwardContent(rowId: row.id) != nil || deletesInSelection(row)
    }

    private func deletesInSelection(_ row: TimelineRow) -> Bool {
        row.message?.isOwn == true && row.message?.sendState == .sent && row.details?.canDelete == true
    }

    /// The picked rows that are still in the timeline and selectable, in timeline order.
    private var selectedRows: [TimelineRow] {
        guard let selection else { return [] }
        let picked = Set(selection)
        return rows.filter { picked.contains($0.id) && canSelect($0) }
    }

    /// Action startSelection: the mode starts with the long-pressed message picked.
    func startSelection(rowId: String) {
        guard acceptsMenuActions, let row = row(rowId), canSelect(row) else { return }
        actionsRowId = nil
        selection = [rowId]
    }

    /// Action toggleSelection: an 11th message shows the limit; removing the last one ends the mode.
    func toggleSelection(rowId: String) {
        guard var picked = selection else { return }
        if let index = picked.firstIndex(of: rowId) {
            picked.remove(at: index)
        } else if picked.count >= Self.selectionLimit {
            show(.selectionLimit)
            return
        } else if let row = row(rowId), canSelect(row) {
            picked.append(rowId)
        } else {
            return
        }
        BipeHaptics.play(.tick)
        selection = picked.isEmpty ? nil : picked
    }

    /// Action cancelSelection.
    func cancelSelection() {
        selection = nil
        confirmsDeleteSelection = false
    }

    /// bipe_forward_action of the bar: on only when every picked message can be forwarded.
    var canForwardSelection: Bool {
        let picked = selectedRows
        return !picked.isEmpty && picked.allSatisfy { forwardContent(rowId: $0.id) != nil }
    }

    /// bipe_app_message_action_delete of the bar: on when one picked message is own and can be deleted.
    var canDeleteSelection: Bool {
        selectedRows.contains { deletesInSelection($0) }
    }

    /// Action forwardSelection: the picker opens with the picked messages.
    func forwardSelection() {
        guard canForwardSelection else { return }
        forwardingRowIds = selectedRows.map(\.id)
    }

    /// Action deleteSelection: one dialog for all own picked messages.
    func askDeleteSelection() {
        guard canDeleteSelection else { return }
        confirmsDeleteSelection = true
    }

    /// The dialog of deleteSelection confirmed: deletes each own picked message for everyone, without a reason.
    func confirmDeleteSelection() async {
        let targets = selectedRows.filter { deletesInSelection($0) }.compactMap { item($0.id)?.asEvent()?.eventOrTransactionId }
        cancelSelection()
        guard let timeline, !targets.isEmpty else { return }
        var failed = false
        for target in targets {
            do {
                try await timeline.redactEvent(eventOrTransactionId: target, reason: nil)
            } catch {
                failed = true
            }
        }
        if failed { show(.selectionDeleteFailed) }
    }

    // MARK: theftMark

    /// Asks for the DM partner's theft mark when the chat opens, then every checkMinutes while it stays open.
    private func watchTheftMark() {
        theftTask?.cancel()
        theftTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.checkTheftMark()
                try? await Task.sleep(for: .seconds(TheftMarkChecks.interval))
            }
        }
    }

    private func checkTheftMark() async {
        guard isDm, let peer = peerUserId else {
            theftMark = nil
            return
        }
        let statement = await TheftMarkChecks.statement(for: peer, session: session)
        // The chat may have changed partner while the call ran.
        if peerUserId == peer {
            theftMark = statement
        }
    }

    /// bipe_theft_message_marker: a message of the marked partner sent from stolen_at minus markedMessageMinutes on.
    func showsTheftMarker(_ message: TimelineMessage) -> Bool {
        guard let theftMark, !message.isOwn, message.senderId == theftMark.sub else { return false }
        return message.sentAt >= theftMark.markerStart
    }

    // MARK: handle-change-line

    /// Spec handle-change-line: the cached changes of the room's members now, then a fetch of new ones.
    /// A failed fetch keeps the cache and tries again on the next open.
    private func loadHandleChanges(_ room: Room) async {
        let store = HandleChangeStores.store(for: session)
        guard let members = await GroupPeople.members(of: room, cachedOnly: true) else { return }
        let joined = members.filter { $0.membership == .join }
        facts.roomMembers = Set(joined.map(\.userId))
        for member in joined where facts.members[member.userId] == nil {
            facts.members[member.userId] = (member.displayName.flatMap { $0.isEmpty ? nil : $0 } ?? member.userId, member.avatarUrl)
        }
        facts.handleChanges = store.changes(for: facts.roomMembers)
        handleChangesTask = Task { [weak self] in
            for await _ in store.changesPublisher.values {
                guard let self else { return }
                self.facts.handleChanges = store.changes(for: self.facts.roomMembers)
                self.rebuild()
            }
        }
        let userIds = facts.roomMembers
        Task { await store.refresh(userIDs: userIds) }
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

    /// peerAway: Room.member of the DM's other person; a leave or ban is left, and a profile that is not found
    /// (a deactivated account) is accountGone. The profile lookup runs once per leave.
    private func loadPeerAway(_ room: Room, peer: String?) async {
        guard let peer, let member = try? await room.member(userId: peer) else {
            peerAway = .none
            return
        }
        switch member.membership {
        case .leave, .ban:
            if accountGoneChecked?.userId != peer {
                accountGoneChecked = (peer, await Self.accountIsGone(peer, client: session.client))
            }
            peerAway = accountGoneChecked?.gone == true ? .accountGone : .left
        default:
            accountGoneChecked = nil
            peerAway = .none
        }
    }

    private static func accountIsGone(_ userId: String, client: Client) async -> Bool {
        do {
            _ = try await client.getProfile(userId: userId)
            return false
        } catch let error as ClientError {
            if case .MatrixApi(kind: .notFound, code: _, msg: _, details: _) = error { return true }
            return false
        } catch {
            return false
        }
    }

    /// Action inviteAgain of spec chat-timeline: Room.inviteUserById when the user may invite. In a DM created with
    /// the DM power levels nobody may, so `createDM` makes a new DM; its room id comes back for the screen to open.
    func inviteAgain(createDM: (String) async throws -> String) async -> String? {
        guard peerAway == .left, !invitingAgain, let room, let peer = peerUserId else { return nil }
        invitingAgain = true
        defer { invitingAgain = false }
        do {
            if let powerLevels = try? await room.getPowerLevels(), powerLevels.canOwnUserInvite() {
                try await room.inviteUserById(userId: peer)
                await loadPeerAway(room, peer: peer)
                return nil
            }
            return try await createDM(peer)
        } catch {
            show(.inviteAgainFailed)
            return nil
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

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type TimelineRow of spec timeline-rows.
struct TimelineRow: Identifiable, Equatable {
    enum Kind: Equatable {
        case message
        case undecryptable
        case deleted
        /// A poll (spec poll-detail, groups lane).
        case poll
        case dateSeparator
        case readMarker
        /// A local handle-change line (spec handle-change-line, ADR 0066).
        case handleChange
        case notEncrypted
        case chatStart
    }

    var id: String
    var kind: Kind
    /// kind message, undecryptable and deleted.
    var message: TimelineMessage?
    /// kind message only.
    var details: MessageDetails?
    /// kind dateSeparator only: a time on that day.
    var day: Date?
    /// kind handleChange only.
    var handleChange: HandleChangeLine?
}

/// Type HandleChangeLine of spec handle-change-line.
struct HandleChangeLine: Equatable {
    var change: BipeHandleChange
    /// The person's current display name in this room.
    var name: String
}

/// Type MessageDetails of spec timeline-rows.
struct MessageDetails: Equatable {
    var eventId: String?
    var reply: MessageReply?
    var reactions: [MessageReaction]
    var isEdited: Bool
    var receipt: MessageReceipt?
    /// Messages from other people only (spec scam-warning).
    var scamWarning: ScamWarningKind?
    var canReply: Bool
    var canEdit: Bool
    var canDelete: Bool
}

/// Type MessageReply of spec message-reply.
struct MessageReply: Equatable {
    enum State: Equatable {
        case loading
        case ready
        case unavailable
    }

    var eventId: String
    var state: State
    var senderName: String?
    var preview: String?
}

/// Type MessageReaction of spec message-reactions.
struct MessageReaction: Equatable, Identifiable {
    var key: String
    var count: Int
    var includesOwn: Bool
    var isSending: Bool
    /// The people who reacted with this key, newest first (for the who-reacted sheet).
    var senders: [ReactionSender]

    var id: String { key }
}

/// Type ReactionSender of spec message-reactions.
struct ReactionSender: Equatable, Identifiable {
    var userId: String
    var name: String
    var reactedAt: Date

    var id: String { userId }
}

/// Type MessageReceipt of spec read-receipts.
struct MessageReceipt: Equatable {
    enum Tick: Equatable {
        case sending
        case sent
        case read
        case failed
    }

    var tick: Tick
    /// Group only: whose newest receipt is on this message, newest first.
    var readers: [ReceiptReader]
    /// Group only: everyone who read this message or a newer one, newest first (the read-by sheet).
    var readersUpToHere: [ReceiptReader]
    /// Only the newest own message shows its tick label as text.
    var showsLabel: Bool
}

/// Type ReceiptReader of spec read-receipts.
struct ReceiptReader: Equatable, Identifiable {
    var userId: String
    var name: String
    var avatarUrl: String?
    var readAt: Date?

    var id: String { userId }
}

/// What the row builder needs from the room besides the timeline items.
struct RoomFacts {
    var ownUserId: String
    var isDm: Bool
    var isEncrypted: Bool
    var joinedMembers: Int
    var canRedactOwn: Bool
    var canRedactOther: Bool
    /// Display names and avatars of members, by user ID. Missing names fall back to the user ID.
    var members: [String: (name: String, avatarUrl: String?)]
    /// Event IDs whose reply details this screen asked for once (spec message-reply loadReply).
    var replyLoadsTried: Set<String>
    /// Members of the room now, by user ID (spec handle-change-line: no line for anyone else).
    var roomMembers: Set<String> = []
    /// Cached handle changes of the room members (BipeHandleChangeStore), oldest first.
    var handleChanges: [BipeHandleChange] = []
}

/// Builds the rows of spec timeline-rows from the SDK timeline items, oldest first.
enum TimelineRowBuilder {
    static func rows(items: [TimelineItem], facts: RoomFacts) -> (rows: [TimelineRow], hitStart: Bool, unknownUsers: Set<String>) {
        var rows: [TimelineRow] = []
        var hitStart = false
        var unknown = Set<String>()

        func name(_ userId: String) -> String {
            if userId == facts.ownUserId { return BipeStrings.appYou }
            if let member = facts.members[userId] { return member.name }
            unknown.insert(userId)
            return userId
        }

        // Read receipts, newest first: who has read this event or a newer one (spec read-receipts notes).
        var readUpToHere: [String: Receipt] = [:]
        var receipts: [Int: MessageReceipt] = [:]
        var newestOwnIndex: Int?
        for (index, item) in items.enumerated().reversed() {
            guard let event = item.asEvent() else { continue }
            for (userId, receipt) in event.readReceipts where userId != facts.ownUserId && readUpToHere[userId] == nil {
                readUpToHere[userId] = receipt
            }
            guard event.isOwn, isShownMessage(event) else { continue }
            if newestOwnIndex == nil { newestOwnIndex = index }
            let tick: MessageReceipt.Tick
            switch event.localSendState {
            case .some(.notSentYet): tick = .sending
            case .some(.sendingFailed): tick = .failed
            default:
                let othersInRoom = max(1, facts.joinedMembers - 1)
                let isRead = facts.isDm ? !readUpToHere.isEmpty : readUpToHere.count >= othersInRoom
                tick = isRead ? .read : .sent
            }
            func readers(_ receipts: [String: Receipt]) -> [ReceiptReader] {
                guard !facts.isDm else { return [] }
                return receipts
                    .filter { $0.key != facts.ownUserId }
                    .map { userId, receipt in
                        ReceiptReader(userId: userId,
                                      name: name(userId),
                                      avatarUrl: facts.members[userId]?.avatarUrl,
                                      readAt: receipt.timestamp.map { Date(milliseconds: $0) })
                    }
                    .sorted { ($0.readAt ?? .distantPast) > ($1.readAt ?? .distantPast) }
            }
            receipts[index] = MessageReceipt(tick: tick,
                                             readers: readers(event.readReceipts),
                                             readersUpToHere: readers(readUpToHere),
                                             showsLabel: index == newestOwnIndex)
        }

        for (index, item) in items.enumerated() {
            let id = item.uniqueId().id
            if let virtual = item.asVirtual() {
                switch virtual {
                case .dateDivider(let ts):
                    rows.append(TimelineRow(id: id, kind: .dateSeparator, day: Date(milliseconds: ts)))
                case .readMarker:
                    rows.append(TimelineRow(id: id, kind: .readMarker))
                case .timelineStart:
                    hitStart = true
                    rows.append(TimelineRow(id: id, kind: .chatStart))
                }
                continue
            }
            guard let event = item.asEvent(), case .msgLike(let content) = event.content else { continue }
            let base = message(id: id, event: event, senderName: name(event.sender))
            // Media and voice rows (specs timeline-media, voice-message): message rows with their own content.
            var mediaContent = MediaContent(content.kind)
            if case .media(var media) = mediaContent {
                media.uploadProgress = event.isOwn ? MediaFormat.uploadPercent(event.localSendState) : nil
                // The SDK types have no field for the image description; it comes from the event JSON (bipe-chat/bipe#501).
                if media.kind == .image {
                    media.description = BipeImageDescription.read(eventJson: event.lazyProvider.debugInfo().originalJson)
                }
                mediaContent = .media(media)
            }
            switch content.kind {
            case .message(let messageContent):
                var message = base
                switch mediaContent {
                case .media(let media):
                    message.kind = .media
                    message.media = media
                    message.body = media.caption?.nilIfBlank ?? media.kindLabel
                case .voice(let voice):
                    message.kind = .voice
                    message.voice = voice
                    message.body = voice.transcript ?? (voice.isVoice ? BipeStrings.appMediaVoice : voice.filename)
                case nil:
                    switch messageContent.msgType {
                    case .text:
                        message.kind = .text
                        message.body = messageContent.body
                    case .location(let locationContent):
                        // Spec location-view: a geo URI the app cannot read stays unsupported.
                        guard let location = LocationMessage(locationContent) else { continue }
                        message.kind = .location
                        message.location = location
                        message.body = location.label
                    default:
                        continue
                    }
                }
                let isSent: Bool
                switch event.localSendState {
                case .none, .some(.sent): isSent = true
                default: isSent = false
                }
                let details = MessageDetails(
                    eventId: eventId(event),
                    reply: content.inReplyTo.map { reply(from: $0, facts: facts, name: name) },
                    reactions: reactions(content.reactions, facts: facts, name: name),
                    isEdited: messageContent.isEdited,
                    receipt: event.isOwn ? receipts[index] : nil,
                    // The detector runs on the device; the cache keys on the event and the body, so an edit runs it again.
                    scamWarning: event.isOwn ? nil : ScamDetector.warningKind(for: messageContent.body, eventID: eventId(event) ?? id),
                    canReply: event.canBeRepliedTo && isSent,
                    canEdit: event.isEditable && message.kind == .text,
                    canDelete: event.isOwn ? (facts.canRedactOwn || !isSent) : (!facts.isDm && facts.canRedactOther)
                )
                rows.append(TimelineRow(id: id, kind: .message, message: message, details: details))
            case .unableToDecrypt(let encrypted):
                var message = base
                message.kind = .undecryptable
                message.cause = undecryptableCause(encrypted)
                rows.append(TimelineRow(id: id, kind: .undecryptable, message: message))
            case .redacted:
                rows.append(TimelineRow(id: id, kind: .deleted, message: base))
            case .sticker:
                guard case .media(let media) = mediaContent else { continue }
                var message = base
                message.kind = .media
                message.media = media
                message.body = media.kindLabel
                let details = MessageDetails(
                    eventId: eventId(event),
                    reply: content.inReplyTo.map { reply(from: $0, facts: facts, name: name) },
                    reactions: reactions(content.reactions, facts: facts, name: name),
                    isEdited: false,
                    receipt: event.isOwn ? receipts[index] : nil,
                    scamWarning: nil,
                    canReply: event.canBeRepliedTo && message.sendState == .sent,
                    canEdit: false,
                    canDelete: event.isOwn ? (facts.canRedactOwn || message.sendState != .sent) : (!facts.isDm && facts.canRedactOther)
                )
                rows.append(TimelineRow(id: id, kind: .message, message: message, details: details))
            case .poll(let question, let pollKind, let maxSelections, let answers, let votes, let endTime, _):
                guard let eventId = eventId(event) else { continue }
                var message = base
                message.kind = .poll
                message.poll = PollData(eventId: eventId,
                                        question: question,
                                        kind: pollKind,
                                        maxSelections: maxSelections,
                                        answers: answers,
                                        votes: votes,
                                        endTime: endTime,
                                        isOwnPoll: event.isOwn,
                                        ownUserId: facts.ownUserId)
                rows.append(TimelineRow(id: id, kind: .poll, message: message))
            default:
                // Unsupported events are not rows yet (spec timeline-rows kind unsupported).
                continue
            }
        }

        rows = placeHandleChanges(rows, items: items, facts: facts, name: name)

        if !facts.isEncrypted {
            let at = rows.first?.kind == .chatStart ? 1 : 0
            rows.insert(TimelineRow(id: "bipe-not-encrypted", kind: .notEncrypted), at: at)
        }
        return (dropEmptyDays(rows), hitStart, unknown)
    }

    /// Spec handle-change-line: local lines before the first row newer than the change, inside the loaded range.
    /// The timeline is live, so a change newer than the last row shows at the end.
    private static func placeHandleChanges(_ rows: [TimelineRow], items: [TimelineItem], facts: RoomFacts,
                                           name: (String) -> String) -> [TimelineRow] {
        guard !facts.handleChanges.isEmpty else { return rows }
        // Join times seen in the loaded items: a change before the person joined gets no line.
        var joinedAt: [String: Date] = [:]
        for item in items {
            guard let event = item.asEvent(), case .roomMembership(let userId, _, let change, _) = event.content else { continue }
            if change == .joined || change == .invitationAccepted {
                joinedAt[userId] = Date(milliseconds: event.timestamp)
            }
        }
        let anchors = rows.map { BipeHandleChangePlacement.Anchor(timestamp: $0.message?.sentAt) }
        let placements = BipeHandleChangePlacement.placements(of: facts.handleChanges, in: anchors,
                                                              members: facts.roomMembers, joinedAt: joinedAt, includesEnd: true)
        var result = rows
        // Back to front, so earlier indices stay valid; changes at one index keep their time order.
        for placement in placements.reversed() {
            let change = placement.change
            guard change.line(name: name(change.userId)) != nil else { continue }
            result.insert(TimelineRow(id: change.lineID, kind: .handleChange,
                                      handleChange: HandleChangeLine(change: change, name: name(change.userId))),
                          at: placement.index)
        }
        return result
    }

    /// A date separator whose day has no row left (only hidden events) goes away.
    private static func dropEmptyDays(_ rows: [TimelineRow]) -> [TimelineRow] {
        var result: [TimelineRow] = []
        for row in rows {
            if row.kind == .dateSeparator, result.last?.kind == .dateSeparator {
                result.removeLast()
            }
            result.append(row)
        }
        if result.last?.kind == .dateSeparator {
            result.removeLast()
        }
        return result
    }

    static func isShownMessage(_ event: EventTimelineItem) -> Bool {
        guard case .msgLike(let content) = event.content else { return false }
        if MediaContent(content.kind) != nil { return true }
        guard case .message(let message) = content.kind else { return false }
        switch message.msgType {
        case .text, .location: return true
        default: return false
        }
    }

    static func eventId(_ event: EventTimelineItem) -> String? {
        if case .eventId(let eventId) = event.eventOrTransactionId { return eventId }
        return nil
    }

    private static func message(id: String, event: EventTimelineItem, senderName: String) -> TimelineMessage {
        let sendState: TimelineMessage.SendState
        switch event.localSendState {
        case .some(.notSentYet): sendState = .sending
        case .some(.sendingFailed): sendState = .failed
        case .some(.sent), .none: sendState = .sent
        }
        return TimelineMessage(id: id,
                               kind: .unsupported,
                               body: nil,
                               senderId: event.sender,
                               senderName: event.isOwn ? senderName : event.senderProfile.displayName(or: senderName),
                               isOwn: event.isOwn,
                               sentAt: Date(milliseconds: event.timestamp),
                               sendState: event.isOwn ? sendState : .sent,
                               cause: nil,
                               poll: nil)
    }

    /// Spec message-reply notes: Unavailable and Pending are loading until loadReply ran once; Error is unavailable.
    private static func reply(from details: InReplyToDetails, facts: RoomFacts, name: (String) -> String) -> MessageReply {
        let eventId = details.eventId()
        switch details.event() {
        case .ready(let content, let sender, let senderProfile, _, _):
            let senderName = sender == facts.ownUserId ? BipeStrings.appYou : senderProfile.displayName(or: name(sender))
            return MessageReply(eventId: eventId, state: .ready, senderName: senderName,
                                preview: replyPreview(content, isOwn: sender == facts.ownUserId))
        case .error:
            return MessageReply(eventId: eventId, state: .unavailable)
        case .unavailable:
            return MessageReply(eventId: eventId, state: facts.replyLoadsTried.contains(eventId) ? .unavailable : .loading)
        case .pending:
            return MessageReply(eventId: eventId, state: .loading)
        }
    }

    private static func replyPreview(_ content: TimelineItemContent, isOwn: Bool) -> String {
        guard case .msgLike(let msgLike) = content else { return "" }
        switch msgLike.kind {
        case .message(let message):
            switch MediaContent(message.msgType) {
            case .media(let media): return media.caption?.nilIfBlank ?? media.kindLabel
            case .voice(let voice): return voice.isVoice ? BipeStrings.appMediaVoice : voice.filename
            case nil:
                if case .location = message.msgType { return BipeStrings.appLocationTitle }
                return message.body
            }
        case .sticker: return BipeStrings.appMediaSticker
        case .redacted: return isOwn ? BipeStrings.appMessageDeletedOwn : BipeStrings.appMessageDeleted
        case .unableToDecrypt: return BipeStrings.appTimelineUndecryptable
        default: return ""
        }
    }

    private static func reactions(_ reactions: [Reaction], facts: RoomFacts, name: (String) -> String) -> [MessageReaction] {
        reactions.map { reaction in
            MessageReaction(key: reaction.key,
                            count: reaction.senders.count,
                            includesOwn: reaction.senders.contains { $0.senderId == facts.ownUserId },
                            isSending: false,
                            senders: reaction.senders
                                .map { ReactionSender(userId: $0.senderId, name: name($0.senderId), reactedAt: Date(milliseconds: $0.timestamp)) }
                                .sorted { $0.reactedAt > $1.reactedAt })
        }
    }

    /// Cause map of spec message-undecryptable (notes).
    private static func undecryptableCause(_ encrypted: EncryptedMessage) -> TimelineMessage.UndecryptableCause {
        guard case .megolmV1AesSha2(_, let cause) = encrypted else { return .waiting }
        switch cause {
        case .sentBeforeWeJoined:
            return .beforeJoined
        case .historicalMessageAndBackupIsDisabled, .historicalMessageAndDeviceIsUnverified:
            return .historyNotOnThisPhone
        case .withheldBySender, .withheldForUnverifiedOrInsecureDevice:
            return .notSharedWithThisPhone
        case .verificationViolation, .unsignedDevice:
            return .unapprovedSenderDevice
        default:
            return .waiting
        }
    }
}

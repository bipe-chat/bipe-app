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
            switch content.kind {
            case .message(let messageContent):
                guard case .text = messageContent.msgType else { continue }
                var message = base
                message.kind = .text
                message.body = messageContent.body
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
                    canEdit: event.isEditable,
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
            case .poll(let question, let pollKind, _, let answers, let votes, let endTime, _):
                guard let eventId = eventId(event) else { continue }
                var message = base
                message.kind = .poll
                message.poll = PollData(eventId: eventId,
                                        question: question,
                                        kind: pollKind,
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

        if !facts.isEncrypted {
            let at = rows.first?.kind == .chatStart ? 1 : 0
            rows.insert(TimelineRow(id: "bipe-not-encrypted", kind: .notEncrypted), at: at)
        }
        return (dropEmptyDays(rows), hitStart, unknown)
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
        guard case .msgLike(let content) = event.content, case .message(let message) = content.kind, case .text = message.msgType else { return false }
        return true
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
        case .message(let message): return message.body
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

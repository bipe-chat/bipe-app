// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type TimelineMessage of spec chat-timeline, with the cause of spec message-undecryptable.
struct TimelineMessage: Identifiable, Equatable {
    enum Kind: Equatable {
        case text
        case undecryptable
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
}

/// Spec chat-timeline (bipe-shared product/screens/chat-timeline.json).
@MainActor
final class ChatTimelineModel: ObservableObject {
    static let historyPage: UInt16 = 20

    @Published private(set) var roomName = ""
    @Published private(set) var messages: [TimelineMessage] = []
    @Published private(set) var isLoadingHistory = false
    @Published private(set) var reachedStart = false
    @Published var draft = ""
    /// signup-done: the inviter of this account while this chat with them has no first message (ADR 0034).
    @Published private(set) var inviterHint: BipeInviterGreeting?

    let roomId: String
    private let session: AccountSession
    private var timeline: Timeline?
    private var items: [TimelineItem] = []
    private var handle: TaskHandle?
    private var started = false

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func start() async {
        guard !started else { return }
        started = true
        guard let room = try? session.client.getRoom(roomId: roomId),
              let timeline = try? await room.timeline() else { return }
        roomName = room.displayName() ?? roomId
        self.timeline = timeline
        if let greeting = BipeInviteStore(userID: session.userId).inviterGreeting(),
           (try? session.client.getDmRoom(userId: greeting.userID))?.id() == roomId {
            inviterHint = greeting
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

    /// Action loadHistory: back-paginates 20 events. Does nothing at the start or while a load runs.
    func loadHistory() async {
        guard let timeline, !reachedStart, !isLoadingHistory else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        if let hitStart = try? await timeline.paginateBackwards(numEvents: Self.historyPage), hitStart {
            reachedStart = true
        }
    }

    /// Action editDraft.
    func editDraft(_ text: String) {
        draft = text
    }

    /// Action send: plain text through the timeline, then clears the draft. The local echo shows as sending.
    func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let timeline, !text.isEmpty else { return }
        draft = ""
        guard let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: text, formatted: nil))) else { return }
        _ = try? await timeline.send(msg: content)
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
        guard let item = items.first(where: { $0.uniqueId().id == id }),
              let handle = item.asEvent()?.lazyProvider.getSendHandle() else { return }
        try? await handle.tryResend()
    }

    private func rebuild() {
        var hitStart = false
        messages = items.compactMap { item in
            if case .some(.timelineStart) = item.asVirtual() {
                hitStart = true
            }
            return item.asEvent().map { Self.message(id: item.uniqueId().id, event: $0) }
        }
        if hitStart {
            reachedStart = true
        }
        if let hint = inviterHint {
            if messages.contains(where: { $0.isOwn && $0.kind != .unsupported }) {
                // The first message to the inviter ends the greeting for this account.
                BipeInviteStore(userID: session.userId).setInviterGreeting(nil)
                inviterHint = nil
            } else if messages.contains(where: { $0.senderId == hint.userID && $0.kind != .unsupported }) {
                inviterHint = nil
            }
        }
    }

    private static func message(id: String, event: EventTimelineItem) -> TimelineMessage {
        var kind = TimelineMessage.Kind.unsupported
        var body: String?
        var cause: TimelineMessage.UndecryptableCause?
        if case .msgLike(let content) = event.content {
            switch content.kind {
            case .message(let message):
                if case .text = message.msgType {
                    kind = .text
                    body = message.body
                }
            case .unableToDecrypt(let encrypted):
                kind = .undecryptable
                cause = undecryptableCause(encrypted)
            default:
                break
            }
        }
        let sendState: TimelineMessage.SendState
        switch event.localSendState {
        case .some(.notSentYet): sendState = .sending
        case .some(.sendingFailed): sendState = .failed
        case .some(.sent), .none: sendState = .sent
        }
        return TimelineMessage(id: id,
                               kind: kind,
                               body: body,
                               senderId: event.sender,
                               senderName: event.senderProfile.displayName(or: event.sender),
                               isOwn: event.isOwn,
                               sentAt: Date(milliseconds: event.timestamp),
                               sendState: event.isOwn ? sendState : .sent,
                               cause: cause)
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

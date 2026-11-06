// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Combine
import Foundation
import MatrixRustSDK

struct MacMessage: Identifiable {
    let id: String
    let sender: String
    let body: String
    let timestamp: Date
    let isOwn: Bool
    let pending: Bool
    let failed: Bool
    let warning: ScamWarningKind?
    let eventID: String?
    let canReply: Bool
}

/// The spike renders text and undecryptable placeholders. Rich media and other product features follow in #1018.
@MainActor
final class MacTimeline: ObservableObject {
    @Published private(set) var messages: [MacMessage] = []
    @Published private(set) var failed = false
    @Published private(set) var sending = false
    @Published private(set) var loadingHistory = false
    @Published private(set) var ready = false
    @Published private(set) var isEncrypted = true
    @Published var draft = "" { didSet { persistDraft() } }
    @Published private(set) var replyID: String? { didSet { persistDraft() } }
    var replyTarget: MacMessage? { messages.first { $0.eventID == replyID } }
    private var timeline: Timeline?
    private var room: Room?
    private var draftRevision = 0
    private let drafts: MacDrafts
    private var lastReadID: String?
    private var listener: TaskHandle?
    private var items: [TimelineItem] = []

    init(drafts: MacDrafts) { self.drafts = drafts }

    func run(room: Room) async {
        do {
            let revision = draftRevision
            let saved = try await drafts.load(room: room)
            try Task.checkCancellation()
            let editedWhileLoading = revision != draftRevision
            if !editedWhileLoading, let saved {
                switch saved.draftType {
                case .newMessage: draft = saved.plainText
                case .reply(let eventID): draft = saved.plainText; replyID = eventID
                case .edit: break
                }
            }
            self.room = room
            if editedWhileLoading { persistDraft() }
            isEncrypted = await room.isEncrypted()
            let isDm = (try? await room.roomInfo())?.isDm ?? true
            let configuration = TimelineConfiguration(focus: .live(hideThreadedEvents: !isDm), filter: .all,
                                                       internalIdPrefix: nil, dateDividerMode: .daily,
                                                       trackReadReceipts: .messageLikeEvents, reportUtds: false)
            let timeline = try await room.timelineWithConfiguration(configuration: configuration)
            self.timeline = timeline
            let stream = TimelineDiffStream()
            listener = await timeline.addListener(listener: stream)
            ready = true
            defer { listener?.cancel(); listener = nil; self.timeline = nil; self.room = nil; ready = false }
            for await updates in stream.updates {
                guard !Task.isCancelled else { return }
                for update in updates { items.apply(update.vectorDiff) }
                rebuild()
                await markRead()
            }
        } catch { if !Task.isCancelled { failed = true } }
    }

    func loadHistory() async {
        guard let timeline, !loadingHistory else { return }
        loadingHistory = true
        defer { loadingHistory = false }
        do { _ = try await timeline.paginateBackwards(numEvents: 30) } catch { failed = true }
    }

    func send() async {
        guard let timeline, !sending, !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let text = draft
        let reply = replyID
        sending = true
        failed = false
        defer { sending = false }
        do {
            if let reply {
                try await timeline.sendReply(msg: messageEventContentFromMarkdown(md: text), eventId: reply)
            } else {
                _ = try await timeline.send(msg: messageEventContentFromMarkdown(md: text))
            }
            if draft == text, replyID == reply { draft = ""; replyID = nil }
        } catch { failed = true }
    }

    func startReply(_ message: MacMessage) {
        guard message.canReply, let eventID = message.eventID else { return }
        replyID = eventID
    }

    func cancelReply() { replyID = nil }

    private func persistDraft() {
        draftRevision += 1
        guard let room else { return }
        drafts.save(room: room, text: draft, reply: replyID) { [weak self] in self?.failed = true }
    }

    func markRead() async {
        guard NSApplication.shared.isActive, let room, let timeline,
              let latest = messages.last?.id, latest != lastReadID else { return }
        let isDm = (try? await room.roomInfo())?.isDm ?? true
        guard NSApplication.shared.isActive else { return }
        do {
            try await timeline.markAsRead(receiptType: MacReceiptPolicy.type(isDm: isDm))
            lastReadID = latest
        } catch { /* A later timeline update or foreground event retries without interrupting the composer. */ }
    }

    private func rebuild() {
        messages = items.compactMap { item in
            guard let event = item.asEvent(), case .msgLike(let content) = event.content else { return nil }
            let body: String
            switch content.kind {
            case .message(let message): body = message.body
            case .unableToDecrypt: body = BipeStrings.webTimelineUndecryptable
            default: return nil
            }
            var displayName: String?
            if case .ready(let name, _, _, _, _) = event.senderProfile { displayName = name }
            let sender = BipeChatRowName.person(userID: event.sender, displayName: displayName, handle: nil)
            let pending: Bool
            let failed: Bool
            switch event.localSendState {
            case .notSentYet: pending = true; failed = false
            case .sendingFailed: pending = false; failed = true
            default: pending = false; failed = false
            }
            let eventID: String?
            if case .eventId(let id) = event.eventOrTransactionId { eventID = id } else { eventID = nil }
            return MacMessage(id: item.uniqueId().id, sender: sender, body: body,
                              timestamp: Date(timeIntervalSince1970: Double(event.timestamp) / 1000),
                              isOwn: event.isOwn, pending: pending, failed: failed,
                              warning: event.isOwn ? nil : ScamDetector.warningKind(for: body, eventID: item.uniqueId().id),
                              eventID: eventID, canReply: event.canBeRepliedTo)
        }
    }
}

enum MacReceiptPolicy {
    static let key = "bipe.settings.readReceipts"
    static func type(isDm: Bool, defaults: UserDefaults = .standard) -> ReceiptType {
        let enabled = defaults.object(forKey: key) as? Bool ?? true
        return isDm && !enabled ? .readPrivate : .read
    }
}

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
    let richText: BipeMessageText
    let photo: MacPhoto?
    let isEmote: Bool
    let timestamp: Date
    let isOwn: Bool
    let pending: Bool
    let failed: Bool
    let warning: ScamWarningKind?
    let eventID: String?
    let canReply: Bool
}

/// Native timeline using the same rich-text parser, image event and encrypted send queue as the phone clients.
@MainActor
final class MacTimeline: ObservableObject {
    @Published private(set) var messages: [MacMessage] = []
    @Published private(set) var failed = false
    @Published private(set) var sending = false
    @Published private(set) var loadingHistory = false
    @Published private(set) var ready = false
    @Published private(set) var isEncrypted = true
    @Published private(set) var isPrivateRoom = false
    @Published private(set) var preparing = false
    @Published private(set) var attachment: MacPreparedImage?
    @Published var imageCaption = ""
    @Published var imageDescription = ""
    @Published private(set) var uploadError: String?
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
    private var moneyPause = BipeMoneyPause()

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
            let info = try? await room.roomInfo()
            let isDm = info?.isDm ?? true
            isPrivateRoom = info?.isDm == true || info?.isPublic == false
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

    func prepareImage(_ url: URL) async {
        guard !preparing, !sending else { return }
        preparing = true
        uploadError = nil
        defer { preparing = false }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let highQuality = UserDefaults.standard.string(forKey: BipeSettingsRow.chatsMediaQuality.rawValue) == "high"
            let image = try await Task.detached(priority: .userInitiated) { try MacImagePreparation.prepare(url, highQuality: highQuality) }.value
            attachment?.remove()
            attachment = image
            imageCaption = ""
            imageDescription = ""
        } catch { uploadError = BipeStrings.appUploadFailed }
    }

    func cancelAttachment() {
        guard !sending else { return }
        attachment?.remove(); attachment = nil
        imageCaption = ""; imageDescription = ""; uploadError = nil
    }

    func sendImage(maxUploadSize: UInt64?) async {
        guard let timeline, let image = attachment, !sending else { return }
        if let maxUploadSize, (image.info.size ?? 0) > maxUploadSize {
            uploadError = BipeStrings.appUploadTooLarge(ByteCountFormatter.string(fromByteCount: Int64(clamping: maxUploadSize), countStyle: .file))
            return
        }
        sending = true
        uploadError = nil
        let reply = replyID
        defer { sending = false }
        do {
            let caption = imageCaption.trimmingCharacters(in: .whitespacesAndNewlines)
            let params = UploadParameters(source: .file(filename: image.file.path), caption: caption.isEmpty ? nil : caption,
                                          formattedCaption: nil, mentions: nil, inReplyTo: reply,
                                          extraContentJson: BipeImageDescription.extraContentJson(imageDescription))
            let handle = try timeline.sendImage(params: params, thumbnailSource: .file(filename: image.thumbnail.path), imageInfo: image.info)
            try await handle.join()
            image.remove()
            attachment = nil
            imageCaption = ""; imageDescription = ""
            if replyID == reply { replyID = nil }
        } catch { uploadError = BipeStrings.appUploadFailed }
    }

    func startMoneyPause(_ message: MacMessage) {
        if let warning = message.warning { moneyPause.start(id: message.id, kind: warning) }
    }

    func pauseSecondsLeft(_ id: String, now: Date = .now) -> Int { moneyPause.secondsLeft(id: id, now: now) }

    deinit { attachment?.remove() }

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
            let payload: MacMessagePayload
            switch content.kind {
            case .message(let message):
                payload = MacMessagePayload(type: message.msgType, fallback: message.body,
                                            eventJson: event.lazyProvider.debugInfo().originalJson)
            case .unableToDecrypt: payload = MacMessagePayload(body: BipeStrings.webTimelineUndecryptable)
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
            return MacMessage(id: item.uniqueId().id, sender: sender, body: payload.body,
                              richText: payload.richText, photo: payload.photo, isEmote: payload.isEmote,
                              timestamp: Date(timeIntervalSince1970: Double(event.timestamp) / 1000),
                              isOwn: event.isOwn, pending: pending, failed: failed,
                              warning: event.isOwn ? nil : ScamDetector.warningKind(for: payload.richText.safetyText, eventID: item.uniqueId().id),
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

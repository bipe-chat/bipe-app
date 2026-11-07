// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Combine
import Foundation
import MatrixRustSDK
import UniformTypeIdentifiers

struct MacMessage: Identifiable {
    let id: String
    let sender: String
    let senderID: String
    /// Spec timeline-rows nameAmbiguous: another member of the group shows the same name.
    let senderNameAmbiguous: Bool
    /// Spec chat-timeline senderRole: admin or moderator, groups only.
    let senderRole: GroupRole?
    let body: String
    let richText: BipeMessageText
    let photo: MacPhoto?
    let isEmote: Bool
    let timestamp: Date
    let isOwn: Bool
    /// Spec timeline-rows: false only for the one other person of a DM. A room flagged direct can hold messages from people who left.
    let showsSender: Bool
    let pending: Bool
    let failed: Bool
    let warning: ScamWarningKind?
    let eventID: String?
    let canReply: Bool
    let canReact: Bool
    let reactions: [MacReaction]
    var canEdit = false
    var canDelete = false
    var isUndecryptable = false
    var isImage = false
    var forwardJson: String? = nil
    var isSent: Bool { eventID != nil && !pending && !failed }
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
    var readingLatest = true
    @Published private(set) var isDm = false
    @Published var reactionFailed = false
    @Published private(set) var pinned: Set<String> = []
    @Published private(set) var editingID: String?
    @Published var deleting: MacMessage?
    @Published var reporting: MacMessage?
    @Published var forwarding: MacMessage?
    @Published var copied = false
    private var canPin = false
    private var canRedactOwn = true
    private var canRedactOther = false
    private var draftBeforeEdit = ""
    @Published private(set) var reacting: Set<String> = []
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
    private var dmPeerID: String?
    private var listener: TaskHandle?
    private var items: [TimelineItem] = []
    private var moneyPause = BipeMoneyPause()
    private var attachmentSend: SendAttachmentJoinHandle?
    private var attachmentCancelled = false
    private var lookalikeSenders: Set<String> = []
    private var senderRoles: [String: GroupRole] = [:]
    private var knownMemberCount: UInt64?

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
            await readRoomKind(room)
            let info = try? await room.roomInfo()
            pinned = Set(info?.pinnedEventIds ?? [])
            if let levels = try? await room.getPowerLevels() {
                canRedactOwn = levels.canOwnUserRedactOwn()
                canRedactOther = levels.canOwnUserRedactOther()
                canPin = !isDm && levels.canOwnUserPinUnpin()
            }
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
                await readRoomKind(room)
                rebuild()
                await markRead()
            }
        } catch { if !Task.isCancelled { failed = true } }
    }

    /// A DM turns into a group when a third person joins, and the sender line follows. Unknown stays a group, as on the phones.
    private func readRoomKind(_ room: Room) async {
        guard let info = try? await room.roomInfo() else { return }
        isDm = info.isDm
        dmPeerID = info.isDm ? info.heroes.first(where: { $0.userId != room.ownUserId() })?.userId : nil
        isPrivateRoom = info.isDm || info.isPublic == false
        await readSenderFacts(room, info: info)
    }

    /// The roles (spec chat-timeline senderRole) and the look-alike names (spec timeline-rows nameAmbiguous) of a group.
    /// The roles follow each update. The names change with the members, so a read of the list waits for a new count.
    private func readSenderFacts(_ room: Room, info: RoomInfo) async {
        guard !info.isDm else {
            senderRoles = [:]
            lookalikeSenders = []
            knownMemberCount = nil
            return
        }
        if let levels = try? await room.getPowerLevels() {
            senderRoles = GroupRole.staff(levels: levels.userPowerLevels(), creators: info.creators ?? [])
        }
        guard knownMemberCount != info.joinedMembersCount, let iterator = try? await room.membersNoSync() else { return }
        var names: [String: String] = [:]
        while let chunk = iterator.nextChunk(chunkSize: 200), !chunk.isEmpty {
            for member in chunk where member.membership == .join {
                names[member.userId] = BipeChatRowName.person(userID: member.userId, displayName: member.displayName, handle: nil)
            }
        }
        lookalikeSenders = MemberNames.ambiguous(names)
        knownMemberCount = info.joinedMembersCount
    }

    func loadHistory() async {
        guard let timeline, !loadingHistory else { return }
        loadingHistory = true
        defer { loadingHistory = false }
        do { _ = try await timeline.paginateBackwards(numEvents: 30) } catch { failed = true }
    }

    func send() async {
        guard let timeline, !sending, !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if editingID != nil { await saveEdit(); return }
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

    // MARK: Spec message-actions

    /// The commands that apply to this message and that the Mac has, in the shared order. See the spec notes.
    func menuItems(for message: MacMessage) -> [BipeMessageMenuItem] {
        if message.isUndecryptable { return !message.isOwn && message.isSent ? [.report] : [] }
        let eventID = message.eventID ?? ""
        return BipeMessageMenuItem.allCases.filter { item in
            switch item {
            case .reply: message.canReply && message.isSent
            case .edit: message.canEdit
            case .copy: !message.body.isEmpty
            case .copyLink: !isDm && message.isSent
            case .pin: canPin && message.isSent && !pinned.contains(eventID)
            case .unpin: canPin && message.isSent && pinned.contains(eventID)
            case .forward: message.isSent && message.forwardJson != nil
            case .retry: message.isOwn && message.failed
            case .delete: message.canDelete
            case .report: !message.isOwn && message.isSent
            default: false
            }
        }
    }

    func choose(_ item: BipeMessageMenuItem, _ message: MacMessage) {
        switch item {
        case .reply: startReply(message)
        case .edit: startEdit(message)
        case .copy:
            guard pauseSecondsLeft(message.id) == 0 else { return }
            copy(message.body)
        case .copyLink:
            guard let room, let eventID = message.eventID else { return }
            Task { if let link = try? await room.matrixToEventPermalink(eventId: eventID) { copy(link) } }
        case .pin, .unpin:
            guard let timeline, let eventID = message.eventID else { return }
            Task {
                do {
                    if item == .pin { _ = try await timeline.pinEvent(eventId: eventID); pinned.insert(eventID) }
                    else { _ = try await timeline.unpinEvent(eventId: eventID); pinned.remove(eventID) }
                } catch { failed = true }
            }
        case .forward: forwarding = message
        case .retry:
            guard let handle = items.first(where: { $0.uniqueId().id == message.id })?.asEvent()?.lazyProvider.getSendHandle() else { return }
            Task { try? await handle.tryResend() }
        case .delete: deleting = message
        case .report: reporting = message
        default: break
        }
    }

    private func copy(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    private func startEdit(_ message: MacMessage) {
        if editingID == nil { draftBeforeEdit = draft }
        replyID = nil
        editingID = message.id
        draft = message.body
    }

    func cancelEdit() {
        guard editingID != nil else { return }
        editingID = nil
        draft = draftBeforeEdit
        draftBeforeEdit = ""
    }

    private func saveEdit() async {
        guard let timeline, let id = editingID, let message = messages.first(where: { $0.id == id }),
              let event = items.first(where: { $0.uniqueId().id == id })?.asEvent() else { cancelEdit(); return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text != message.body else { cancelEdit(); return }
        sending = true
        defer { sending = false }
        do {
            let content: EditedContent = message.isImage
                ? .mediaCaption(caption: text, formattedCaption: nil, mentions: nil)
                : .roomMessage(content: try messageEventContentNew(msgtype: .text(content: TextMessageContent(body: text, formatted: nil))))
            try await timeline.edit(eventOrTransactionId: event.eventOrTransactionId, newContent: content)
            cancelEdit()
        } catch { failed = true }
    }

    func confirmDelete(_ message: MacMessage) async {
        guard let timeline, let event = items.first(where: { $0.uniqueId().id == message.id })?.asEvent() else { return }
        do { try await timeline.redactEvent(eventOrTransactionId: event.eventOrTransactionId, reason: nil) }
        catch { failed = true }
    }

    func sendReport(_ message: MacMessage, reason: String) async -> Bool {
        guard let room, let eventID = message.eventID else { return false }
        let text = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        return (try? await room.reportContent(eventId: eventID, reason: text.isEmpty ? nil : text)) != nil
    }

    func reactionPending(rowID: String, key: String) -> Bool { reacting.contains(rowID + "|" + key) }

    func react(rowID: String, key: String) async {
        guard let timeline, let message = messages.first(where: { $0.id == rowID }), message.canReact,
              let event = items.first(where: { $0.uniqueId().id == rowID })?.asEvent(),
              !reactionPending(rowID: rowID, key: key), !key.isEmpty else { return }
        let pendingID = rowID + "|" + key
        reacting.insert(pendingID); reactionFailed = false
        defer { reacting.remove(pendingID) }
        do { _ = try await timeline.toggleReaction(itemId: event.eventOrTransactionId, key: key) }
        catch { if !Task.isCancelled { reactionFailed = true } }
    }

    func pasteImage(_ provider: NSItemProvider) {
        guard !preparing, !sending, let type = provider.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .image) == true
        }) else { return }
        provider.loadDataRepresentation(forTypeIdentifier: type) { [weak self] data, _ in
            Task { @MainActor in
                guard let self else { return }
                guard let data else { self.uploadError = BipeStrings.appUploadFailed; return }
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                do {
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                                                           attributes: [.posixPermissions: 0o700])
                    defer { try? FileManager.default.removeItem(at: folder) }
                    let url = folder.appendingPathComponent("image")
                    try data.write(to: url, options: .atomic)
                    await self.prepareImage(url)
                } catch { self.uploadError = BipeStrings.appUploadFailed }
            }
        }
    }

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
        if sending {
            guard let attachmentSend else { return }
            attachmentCancelled = true
            attachmentSend.cancel()
            return
        }
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
        attachmentCancelled = false
        uploadError = nil
        let reply = replyID
        defer { sending = false; attachmentSend = nil }
        do {
            let caption = imageCaption.trimmingCharacters(in: .whitespacesAndNewlines)
            let params = UploadParameters(source: .file(filename: image.file.path), caption: caption.isEmpty ? nil : caption,
                                          formattedCaption: nil, mentions: nil, inReplyTo: reply,
                                          extraContentJson: BipeImageDescription.extraContentJson(imageDescription))
            let handle = try timeline.sendImage(params: params, thumbnailSource: .file(filename: image.thumbnail.path), imageInfo: image.info)
            attachmentSend = handle
            try await handle.join()
            image.remove()
            attachment = nil
            imageCaption = ""; imageDescription = ""
            if !attachmentCancelled, replyID == reply { replyID = nil }
        } catch {
            if attachmentCancelled {
                image.remove(); attachment = nil; imageCaption = ""; imageDescription = ""
            } else { uploadError = BipeStrings.appUploadFailed }
        }
    }

    func startMoneyPause(_ message: MacMessage) {
        if let warning = message.warning { moneyPause.start(id: message.id, kind: warning) }
    }

    func pauseSecondsLeft(_ id: String, now: Date = .now) -> Int { moneyPause.secondsLeft(id: id, now: now) }

    private func persistDraft() {
        draftRevision += 1
        guard let room else { return }
        drafts.save(room: room, text: draft, reply: replyID) { [weak self] in self?.failed = true }
    }

    func markRead() async {
        // The latest message is on screen: the window has focus and the person is at the bottom of the chat.
        guard NSApplication.shared.isActive, NSApplication.shared.keyWindow != nil, readingLatest, let room, let timeline,
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
            let decryptable: Bool
            var forwardJson: String?
            switch content.kind {
            case .message(let message):
                decryptable = true
                let debugInfo = event.lazyProvider.debugInfo()
                forwardJson = BipeForwardMark.forwardedContentJson(originalJson: debugInfo.originalJson, latestEditJson: debugInfo.latestEditJson)
                payload = MacMessagePayload(type: message.msgType, fallback: message.body,
                                            eventJson: event.lazyProvider.debugInfo().originalJson)
            case .unableToDecrypt: payload = MacMessagePayload(body: BipeStrings.webTimelineUndecryptable); decryptable = false
            default: return nil
            }
            let sender = MacRoomSummary.personName(event.senderProfile, userID: event.sender)
            let pending: Bool
            let failed: Bool
            switch event.localSendState {
            case .notSentYet: pending = true; failed = false
            case .sendingFailed: pending = false; failed = true
            default: pending = false; failed = false
            }
            let eventID: String?
            if case .eventId(let id) = event.eventOrTransactionId { eventID = id } else { eventID = nil }
            let inGroup = !event.isOwn && !isDm
            var sdkAmbiguous = false
            if case .ready(_, let ambiguous, _, _, _) = event.senderProfile { sdkAmbiguous = ambiguous }
            return MacMessage(id: item.uniqueId().id, sender: sender, senderID: event.sender,
                              senderNameAmbiguous: inGroup && (sdkAmbiguous || lookalikeSenders.contains(event.sender)),
                              senderRole: inGroup ? senderRoles[event.sender] : nil, body: payload.body,
                              richText: payload.richText, photo: payload.photo, isEmote: payload.isEmote,
                              timestamp: Date(timeIntervalSince1970: Double(event.timestamp) / 1000),
                              isOwn: event.isOwn, showsSender: !isDm || dmPeerID == nil || event.sender != dmPeerID,
                              pending: pending, failed: failed,
                              warning: event.isOwn ? nil : ScamDetector.warningKind(for: payload.safetyText, eventID: item.uniqueId().id),
                              eventID: eventID, canReply: event.canBeRepliedTo,
                              canReact: decryptable && eventID != nil && !pending && !failed,
                              reactions: decryptable ? MacReaction.summaries(content.reactions, ownUserID: room?.ownUserId() ?? "") : [],
                              canEdit: decryptable && event.isEditable,
                              canDelete: event.isOwn ? (canRedactOwn || eventID == nil) : (!isDm && canRedactOther),
                              isUndecryptable: !decryptable,
                              isImage: payload.photo != nil,
                              forwardJson: forwardJson)
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

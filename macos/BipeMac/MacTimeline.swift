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
    var reply: MessageReply? = nil
    var isEdited = false
    var isDeleted = false
    var file: MacFile? = nil
    var isSent: Bool { eventID != nil && !pending && !failed && !isDeleted }
    var hasMediaCaption: Bool { isImage || file != nil }
    var composerPreview: String {
        if !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return body }
        if photo != nil { return BipeStrings.appMediaImage }
        if file != nil { return BipeStrings.appMediaFile }
        return BipeStrings.appMessageReplyUnavailable
    }

    func editedContent(_ text: String) throws -> EditedContent {
        if hasMediaCaption { return .mediaCaption(caption: text, formattedCaption: nil, mentions: nil) }
        return .roomMessage(content: try messageEventContentNew(msgtype: .text(content: TextMessageContent(body: text, formatted: nil))))
    }
}

struct MacTimelineScroll: Equatable {
    let id = UUID()
    let rowID: String
}

/// A row of spec timeline-rows on the Mac: a message, or a pill between the messages.
enum MacTimelineRow: Identifiable {
    case message(MacMessage)
    case day(id: String, date: Date)
    case line(id: String, text: String, kind: TimelinePillKind)
    case readMarker(id: String)

    var id: String {
        switch self {
        case .message(let message): message.id
        case .day(let id, _), .line(let id, _, _), .readMarker(let id): id
        }
    }
}

/// Native timeline using the same rich-text parser, image event and encrypted send queue as the phone clients.
@MainActor
final class MacTimeline: ObservableObject {
    @Published private(set) var rows: [MacTimelineRow] = []
    @Published private(set) var messages: [MacMessage] = []
    @Published private(set) var failed = false
    @Published private(set) var sending = false
    @Published private(set) var loadingHistory = false
    @Published private(set) var reachedStart = false
    @Published private(set) var scrollTarget: MacTimelineScroll?
    @Published private(set) var highlightedRowID: String?
    @Published private(set) var ready = false
    @Published private(set) var isEncrypted = true
    @Published private(set) var isPrivateRoom = false
    var readingLatest = true
    @Published private(set) var isDm = false
    @Published var reactionFailed = false
    @Published var editFailed = false
    @Published var deleteFailed = false
    @Published private(set) var pinned: Set<String> = []
    @Published private(set) var editingID: String?
    @Published private(set) var editingPreview: String?
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
    @Published private(set) var fileAttachment: MacPreparedFile?
    @Published var imageCaption = ""
    @Published var imageDescription = ""
    @Published private(set) var uploadError: String?
    @Published var draft = "" { didSet { persistDraft() } }
    @Published private(set) var replyID: String? { didSet { persistDraft() } }
    var lastRowIsOwnMessage: Bool {
        if case .message(let message)? = rows.last { return message.isOwn }
        return false
    }
    var replyTarget: MacMessage? { messages.first { $0.eventID == replyID } }
    var canSubmitDraft: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            messages.first(where: { $0.id == editingID })?.hasMediaCaption == true
    }
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
    private var attachmentRevision = UUID()
    private var lookalikeSenders: Set<String> = []
    private var senderRoles: [String: GroupRole] = [:]
    private var memberNames: [String: String] = [:]
    private var knownMemberCount: UInt64?
    private var replyLoadsTried: Set<String> = []
    private var openingOriginal = false
    private var highlightTask: Task<Void, Never>?

    init(drafts: MacDrafts) { self.drafts = drafts }

    func run(room: Room) async {
        replyLoadsTried = []
        reachedStart = false
        loadingHistory = false
        highlightedRowID = nil
        scrollTarget = nil
        highlightTask?.cancel()
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
                                                       trackReadReceipts: .messageLikeEvents, reportUtds: true)
            let timeline = try await room.timelineWithConfiguration(configuration: configuration)
            self.timeline = timeline
            let stream = TimelineDiffStream()
            listener = await timeline.addListener(listener: stream)
            ready = true
            defer {
                if self.timeline === timeline {
                    listener?.cancel(); listener = nil; self.timeline = nil; self.room = nil; ready = false
                    highlightTask?.cancel(); highlightedRowID = nil; scrollTarget = nil
                }
            }
            for await updates in stream.updates {
                guard !Task.isCancelled, self.timeline === timeline else { return }
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
            memberNames = [:]
            knownMemberCount = nil
            return
        }
        if let levels = try? await room.getPowerLevels() {
            senderRoles = GroupRole.staff(levels: levels.userPowerLevels(), creators: info.creators ?? [])
        }
        guard knownMemberCount != info.joinedMembersCount, let iterator = try? await room.membersNoSync() else { return }
        var names: [String: String] = [:]
        var joinedNames: [String: String] = [:]
        while let chunk = iterator.nextChunk(chunkSize: 200), !chunk.isEmpty {
            for member in chunk {
                let name = BipeChatRowName.person(userID: member.userId, displayName: member.displayName, handle: nil)
                names[member.userId] = name
                if member.membership == .join { joinedNames[member.userId] = name }
            }
        }
        memberNames = names
        lookalikeSenders = MemberNames.ambiguous(joinedNames)
        knownMemberCount = info.joinedMembersCount
    }

    func loadHistory() async {
        guard let timeline, !loadingHistory, !reachedStart else { return }
        do { _ = try await paginateHistory(timeline) }
        catch { if !Task.isCancelled { failed = true } }
    }

    private func paginateHistory(_ timeline: Timeline) async throws -> Bool {
        while loadingHistory {
            try await Task.sleep(for: .milliseconds(100))
            guard self.timeline === timeline else { throw CancellationError() }
        }
        guard self.timeline === timeline else { throw CancellationError() }
        if reachedStart { return true }
        loadingHistory = true
        defer { if self.timeline === timeline { loadingHistory = false } }
        let hitStart = try await timeline.paginateBackwards(numEvents: 30)
        guard self.timeline === timeline else { throw CancellationError() }
        reachedStart = hitStart
        return hitStart
    }

    func loadReply(rowID: String) async {
        guard let timeline, let message = messages.first(where: { $0.id == rowID }),
              let reply = message.reply, reply.state == .loading, let eventID = message.eventID,
              !replyLoadsTried.contains(reply.eventId) else { return }
        replyLoadsTried.insert(reply.eventId)
        try? await timeline.fetchDetailsForEvent(eventId: eventID)
        guard self.timeline === timeline, !Task.isCancelled else { return }
        rebuild()
    }

    func openOriginal(eventID: String) async {
        guard let timeline, !openingOriginal else { return }
        openingOriginal = true
        defer { openingOriginal = false }
        do {
            let target = try await MacOriginalLookup.find(loaded: {
                self.messages.first { $0.eventID == eventID }?.id
            }, paginate: {
                let hitStart = try await self.paginateHistory(timeline)
                // The SDK's new rows arrive through its listener, as on iOS.
                try await Task.sleep(for: .milliseconds(300))
                guard self.timeline === timeline else { throw CancellationError() }
                return hitStart
            })
            guard self.timeline === timeline, let target else { return }
            readingLatest = false
            let request = MacTimelineScroll(rowID: target)
            scrollTarget = request
            highlightedRowID = target
            highlightTask?.cancel()
            highlightTask = Task { @MainActor [weak self] in
                do { try await Task.sleep(for: .seconds(1.5)) } catch { return }
                if self?.highlightedRowID == target { self?.highlightedRowID = nil }
            }
        } catch { if !Task.isCancelled, self.timeline === timeline { failed = true } }
    }

    func scrolledToTarget(_ target: MacTimelineScroll) {
        if scrollTarget == target { scrollTarget = nil }
    }

    func send() async {
        guard let timeline, !sending else { return }
        if editingID != nil { await saveEdit(); return }
        guard !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
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
        if editingID != nil { cancelEdit() }
        replyID = eventID
    }

    func cancelReply() { replyID = nil }

    // MARK: Spec message-actions

    /// The commands that apply to this message and that the Mac has, in the shared order. See the spec notes.
    func menuItems(for message: MacMessage) -> [BipeMessageMenuItem] {
        if message.isDeleted { return [] }
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
        guard !message.isDeleted else { return }
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
        case .delete:
            if message.isOwn, !message.isSent {
                guard let handle = items.first(where: { $0.uniqueId().id == message.id })?.asEvent()?.lazyProvider.getSendHandle() else { return }
                Task {
                    do { _ = try await handle.abort() }
                    catch { deleteFailed = true }
                }
            } else { deleting = message }
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
        editingPreview = message.body
        draft = message.body
    }

    func cancelEdit() {
        guard editingID != nil else { return }
        editingID = nil
        editingPreview = nil
        draft = draftBeforeEdit
        draftBeforeEdit = ""
    }

    private func saveEdit() async {
        guard let timeline, let id = editingID, let message = messages.first(where: { $0.id == id }),
              let event = items.first(where: { $0.uniqueId().id == id })?.asEvent() else { cancelEdit(); return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isDeleted else { cancelEdit(); return }
        guard !text.isEmpty || message.hasMediaCaption else { return }
        guard text != message.body else { cancelEdit(); return }
        sending = true
        defer { sending = false }
        do {
            let content = try message.editedContent(text)
            try await timeline.edit(eventOrTransactionId: event.eventOrTransactionId, newContent: content)
            cancelEdit()
        } catch { editFailed = true }
    }

    func confirmDelete(_ message: MacMessage) async {
        guard let timeline, let event = items.first(where: { $0.uniqueId().id == message.id })?.asEvent() else { return }
        do { try await timeline.redactEvent(eventOrTransactionId: event.eventOrTransactionId, reason: nil) }
        catch { deleteFailed = true }
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
                do {
                    let folder = try MacTransferFiles.folder()
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
        let revision = attachmentRevision
        preparing = true
        uploadError = nil
        defer { preparing = false }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let highQuality = UserDefaults.standard.string(forKey: BipeSettingsRow.chatsMediaQuality.rawValue) == "high"
            let image = try await Task.detached(priority: .userInitiated) { try MacImagePreparation.prepare(url, highQuality: highQuality) }.value
            guard !Task.isCancelled, attachmentRevision == revision else { image.remove(); return }
            attachment?.remove()
            fileAttachment?.remove(); fileAttachment = nil
            attachment = image
            imageCaption = ""
            imageDescription = ""
        } catch { if !Task.isCancelled, attachmentRevision == revision { uploadError = BipeStrings.appUploadFailed } }
    }

    func preparePickedURL(_ url: URL, maxUploadSize: UInt64?) async {
        if UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true { await prepareImage(url) }
        else { await prepareFile(url, maxUploadSize: maxUploadSize) }
    }

    func pasteFile(_ provider: NSItemProvider, maxUploadSize: UInt64?) {
        provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { [weak self] item, _ in
            let url = (item as? URL) ?? (item as? Data).flatMap { URL(dataRepresentation: $0, relativeTo: nil) }
            Task { @MainActor in
                guard let self, self.ready else { return }
                guard let url, url.isFileURL else { self.uploadError = BipeStrings.appUploadFailed; return }
                await self.preparePickedURL(url, maxUploadSize: maxUploadSize)
            }
        }
    }

    func prepareFile(_ url: URL, maxUploadSize: UInt64?) async {
        guard !preparing, !sending else { return }
        let revision = attachmentRevision
        preparing = true; uploadError = nil
        defer { preparing = false }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let file = try await Task.detached(priority: .userInitiated) {
                try MacTransferFiles.prepare(url, maxUploadSize: maxUploadSize)
            }.value
            guard !Task.isCancelled, attachmentRevision == revision else { file.remove(); return }
            attachment?.remove(); attachment = nil
            fileAttachment?.remove(); fileAttachment = file
            imageCaption = ""; imageDescription = ""
        } catch {
            guard !Task.isCancelled, attachmentRevision == revision else { return }
            if case MacTransferFiles.Failure.tooLarge(let limit) = error {
                uploadError = BipeStrings.appUploadTooLarge(ByteCountFormatter.string(fromByteCount: Int64(clamping: limit), countStyle: .file))
            } else { uploadError = BipeStrings.appUploadFailed }
        }
    }

    func cancelAttachment() {
        attachmentRevision = UUID()
        if sending {
            guard let attachmentSend else { return }
            attachmentCancelled = true
            attachmentSend.cancel()
            return
        }
        attachment?.remove(); attachment = nil
        fileAttachment?.remove(); fileAttachment = nil
        imageCaption = ""; imageDescription = ""; uploadError = nil
    }

    func sendFile(maxUploadSize: UInt64?) async {
        guard let timeline, let file = fileAttachment, !sending else { return }
        if let maxUploadSize, (file.info.size ?? 0) > maxUploadSize {
            uploadError = BipeStrings.appUploadTooLarge(ByteCountFormatter.string(fromByteCount: Int64(clamping: maxUploadSize), countStyle: .file))
            return
        }
        sending = true; attachmentCancelled = false; uploadError = nil
        let reply = replyID
        defer { sending = false; attachmentSend = nil }
        do {
            let caption = imageCaption.trimmingCharacters(in: .whitespacesAndNewlines)
            let params = UploadParameters(source: .file(filename: file.file.path), caption: caption.isEmpty ? nil : caption,
                formattedCaption: nil, mentions: nil, inReplyTo: reply, extraContentJson: nil)
            let handle = try timeline.sendFile(params: params, fileInfo: file.info)
            attachmentSend = handle
            try await handle.join()
            file.remove(); fileAttachment = nil; imageCaption = ""; imageDescription = ""
            if !attachmentCancelled, replyID == reply { replyID = nil }
        } catch {
            if attachmentCancelled {
                file.remove(); fileAttachment = nil; imageCaption = ""; imageDescription = ""
            } else { uploadError = BipeStrings.appUploadFailed }
        }
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
        var built: [MacTimelineRow] = []
        var stateLine: StateLine?
        let ownUserID = room?.ownUserId()
        let name: (String) -> String = { [memberNames] userID in
            if userID == ownUserID { return BipeStrings.appYou }
            return memberNames[userID] ?? BipeChatRowName.person(userID: userID, displayName: nil, handle: nil)
        }
        for (index, item) in items.enumerated() {
            let id = item.uniqueId().id
            if let virtual = item.asVirtual() {
                switch virtual {
                case .dateDivider(let ts): built.append(.day(id: id, date: Date(timeIntervalSince1970: Double(ts) / 1000)))
                case .timelineStart: built.append(.line(id: id, text: BipeStrings.appTimelineStart, kind: .info))
                case .readMarker: built.append(.readMarker(id: id))
                }
                stateLine = nil
                continue
            }
            guard let event = item.asEvent() else { continue }
            // Spec timeline-rows stateLine: group lines only; consecutive membership lines fold into one row.
            if !isDm, let line = StateLines.line(event) {
                if let previous = stateLine, let folded = StateLines.folded(line, into: previous), case .line(let rowID, _, _)? = built.last {
                    stateLine = folded
                    built[built.count - 1] = .line(id: rowID, text: StateLines.text(folded, name: name), kind: .action)
                } else {
                    stateLine = line
                    built.append(.line(id: id, text: StateLines.text(line, name: name), kind: .action))
                }
                continue
            }
            if case .rtcNotification(let intent, let declinedBy, let activeMembers, _, _) = event.content {
                let lines = CallLines.lines(id: id, event: event, video: intent == "video", declinedBy: declinedBy,
                                            active: !activeMembers.isEmpty, items: items, from: index, isDm: isDm, name: name)
                built += lines.map { MacTimelineRow.line(id: $0.id, text: $0.text, kind: .action) }
                stateLine = nil
                continue
            }
            if let message = messageRow(item) {
                built.append(.message(message))
                stateLine = nil
            }
        }
        rows = Self.withoutEmptyDays(built)
        messages = rows.compactMap { row in
            if case .message(let message) = row { return message }
            return nil
        }
    }

    /// A date separator whose day has no row left (only hidden events) goes away.
    private static func withoutEmptyDays(_ rows: [MacTimelineRow]) -> [MacTimelineRow] {
        var result: [MacTimelineRow] = []
        for row in rows {
            if case .day = row, case .day? = result.last { result.removeLast() }
            result.append(row)
        }
        if case .day? = result.last { result.removeLast() }
        return result
    }

    private func messageRow(_ item: TimelineItem) -> MacMessage? {
        guard let event = item.asEvent(), case .msgLike(let content) = event.content else { return nil }
        let debugInfo = event.lazyProvider.debugInfo()
        guard let payload = MacMessagePayload(kind: content.kind, eventJson: debugInfo.originalJson) else { return nil }
        let decryptable = !payload.isUndecryptable && !payload.isDeleted
        var forwardJson: String?
        if decryptable {
            forwardJson = BipeForwardMark.forwardedContentJson(originalJson: debugInfo.originalJson, latestEditJson: debugInfo.latestEditJson)
        }
        let reply = decryptable ? content.inReplyTo.map {
            MessageReply.make($0, ownUserID: room?.ownUserId() ?? "",
                              loadTried: replyLoadsTried.contains($0.eventId()),
                              name: { memberNames[$0] ?? BipeChatRowName.person(userID: $0, displayName: nil, handle: nil) },
                              preview: { MacReplyPreview.text($0, isOwn: $1) }, sourceText: MacReplyPreview.safetyText)
        } : nil
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
                          warning: payload.warning(including: reply, isOwn: event.isOwn, eventID: item.uniqueId().id),
                          eventID: eventID, canReply: decryptable && event.canBeRepliedTo && !pending && !failed,
                          canReact: decryptable && eventID != nil && !pending && !failed,
                          reactions: decryptable ? MacReaction.summaries(content.reactions, ownUserID: room?.ownUserId() ?? "") : [],
                          canEdit: decryptable && event.isEditable,
                          canDelete: !payload.isDeleted && (event.isOwn ? (canRedactOwn || eventID == nil) : (!isDm && canRedactOther)),
                          isUndecryptable: payload.isUndecryptable,
                          isImage: payload.photo != nil,
                          forwardJson: forwardJson, reply: reply, isEdited: payload.isEdited, isDeleted: payload.isDeleted,
                          file: payload.file)
    }
}

enum MacReceiptPolicy {
    static let key = "bipe.settings.readReceipts"
    static func type(isDm: Bool, defaults: UserDefaults = .standard) -> ReceiptType {
        let enabled = defaults.object(forKey: key) as? Bool ?? true
        return isDm && !enabled ? .readPrivate : .read
    }
}

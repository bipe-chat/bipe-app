// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI
import UIKit

/// Spec chat-timeline, with the rows of timeline-rows and the interactions of the message specs.
struct ChatTimelineView: View {
    @StateObject private var model: ChatTimelineModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var deletingDm = false
    @ObservedObject private var cleared = ClearedChatsStore.shared
    @Environment(\.closeChat) private var closeChat
    @State private var creatingPoll = false
    @State private var openPollId: String?
    @StateObject private var voicePlayer = VoicePlayer()
    @State private var viewerRowId: String?
    // composer
    @State private var attaching = false
    @State private var upload: PickedUpload?
    @State private var showsStickers = false
    private let media: MediaLoader
    /// Opens the chat's info: person-profile for a DM.
    private let openInfo: (() -> Void)?
    private let session: AccountSession
    private let roomId: String
    /// The account's people service, for the blocked bar of a DM (block-person). nil: no bar.
    private let people: PeopleService?

    init(session: AccountSession, media: MediaLoader, roomId: String, people: PeopleService? = nil, openInfo: (() -> Void)? = nil) {
        _model = StateObject(wrappedValue: ChatTimelineModel(session: session, roomId: roomId))
        self.media = media
        self.openInfo = openInfo
        self.session = session
        self.roomId = roomId
        self.people = people
    }

    var body: some View {
        VStack(spacing: 0) {
            rowList
            if let hint = model.inviterHint {
                InviterHintRow(hint: hint, inviterName: model.roomName) {
                    Task { await model.sendGreeting() }
                }
            }
            TypingLine(names: model.typingNames)
            if model.isDm, let people, let peer = model.peerUserId {
                DmComposerSlot(people: people, peerUserId: peer, name: model.roomName) { composerBar }
            } else {
                composerBar
            }
        }
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .attachmentPicker(isPresented: $attaching,
                          onPicked: { urls in upload = PickedUpload(urls: urls, replyToEventId: model.takeReplyEventId()) },
                          onStickers: { showsStickers = true },
                          onPoll: model.canPoll ? { creatingPoll = true } : nil)
        .fullScreenCover(item: $upload) { upload in
            if let timeline = model.timeline {
                UploadPreviewView(model: UploadPreviewModel(urls: upload.urls, client: model.client, timeline: timeline,
                                                            replyToEventId: upload.replyToEventId),
                                  close: { self.upload = nil })
            }
        }
        .sheet(isPresented: $showsStickers) {
            StickerPickerView(model: StickerPickerModel(client: model.client, roomId: roomId), loader: media,
                              close: { showsStickers = false })
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .overlay(alignment: .bottom) { noticeView }
        .navigationTitle(model.roomName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                if model.isDm, let openInfo { Button(action: openInfo) { chatTitle } }
                else { chatTitle }
            }
            if model.isGroup {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        GroupDetailsView(session: session, media: media, roomId: roomId)
                    } label: {
                        Label {
                            Text(BipeStrings.appGroupDetailsTitle)
                        } icon: {
                            PhosphorIcon.info.image.resizable().frame(width: 22, height: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                }
            }
            if model.isDm {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(BipeStrings.dmDeleteAction, role: .destructive) {
                            deletingDm = true
                        }
                    } label: {
                        Label {
                            Text(BipeStrings.dmDetailsTitle)
                        } icon: {
                            PhosphorIcon.info.image.resizable().frame(width: 22, height: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                }
            }
        }
        .sheet(isPresented: $deletingDm) {
            DmDeleteView(session: session, roomId: roomId) { closeChat() }
        }
        .sheet(isPresented: $creatingPoll) {
            PollCreateView(timeline: model)
        }
        .sheet(item: Binding(get: { openPollId.map(PollSheetId.init) }, set: { openPollId = $0?.id })) { poll in
            PollDetailView(timeline: model, session: session, eventId: poll.id)
        }
        .accessibilityIdentifier(BipeScreen.chatTimeline.rawValue)
        .task { await model.start() }
        .onDisappear {
            voicePlayer.stop()
            model.close()
        }
        .fullScreenCover(isPresented: Binding(get: { viewerRowId != nil }, set: { if !$0 { viewerRowId = nil } })) {
            if let viewerRowId {
                MediaViewer(model: model, loader: media, close: { self.viewerRowId = nil }, rowId: viewerRowId)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.markAsRead() } }
            if phase == .background { model.leave() }
        }
        .sheet(isPresented: presence(\.actionsRowId)) {
            if let row = model.row(model.actionsRowId) {
                MessageActionsSheet(model: model, row: row)
            }
        }
        .sheet(isPresented: presence(\.pickerOpenFor)) {
            EmojiPickerSheet { model.pickEmoji($0) }
        }
        .sheet(isPresented: presence(\.reactionSheet)) {
            if let reaction = model.reactionSheet {
                ReactionSendersSheet(reaction: reaction)
            }
        }
        .sheet(isPresented: presence(\.readersSheet)) {
            if let readers = model.readersSheet {
                ReadersSheet(readers: readers, media: media)
            }
        }
        .sheet(isPresented: presence(\.report)) {
            MessageReportSheet(model: model)
        }
        .alert(BipeStrings.appMessageDeleteTitle, isPresented: presence(\.confirmDeleteRowId)) {
            Button(BipeStrings.appMessageActionDelete, role: .destructive) {
                Task { await model.confirmDelete() }
            }
            Button(BipeStrings.appCancel, role: .cancel) {
                model.confirmDeleteRowId = nil
            }
        } message: {
            Text(BipeStrings.appMessageDeleteBody)
        }
        // Spec notification-service: an open chat has no notifications.
        .onAppear { model.resumeMenuActions(); NotificationActions.shared.chatOpened(BipeNotificationTarget(accountUserId: session.userId, roomId: model.roomId)) }
        .onDisappear {
            let target = BipeNotificationTarget(accountUserId: session.userId, roomId: model.roomId)
            if NotificationActions.shared.visibleRoom == target { NotificationActions.shared.chatOpened(nil) }
        }
        .notificationsPrompt(session: session, roomId: model.roomId, messages: model.rows.compactMap(\.message))
    }

    /// Spec dm-delete: events at or below the marker of a cleared DM are gone.
    private var shownRows: [TimelineRow] {
        model.rows.filter { row in row.message.map { cleared.keeps(roomId: roomId, sentAt: $0.sentAt) } ?? true }
    }

    /// A Bool binding for an optional piece of state: false sets it back to nil.
    private func presence<Value>(_ keyPath: ReferenceWritableKeyPath<ChatTimelineModel, Value?>) -> Binding<Bool> {
        Binding(get: { model[keyPath: keyPath] != nil },
                set: { if !$0 { model[keyPath: keyPath] = nil } })
    }

    private var rowList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    historyHeader
                    ForEach(shownRows) { row in
                        TimelineRowView(row: row,
                                        isDm: model.isDm,
                                        isHighlighted: model.highlightedRowId == row.id,
                                        model: model,
                                        media: media,
                                        voicePlayer: voicePlayer,
                                        openPoll: { openPollId = $0 },
                                        openViewer: { viewerRowId = $0 })
                            .id(row.id)
                            .onAppear {
                                if row.id == model.rows.last?.id {
                                    Task { await model.markAsRead() }
                                }
                            }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: model.rows.last?.id) { _, lastId in
                if let lastId {
                    withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                }
            }
            .onChange(of: model.scrollTarget) { _, target in
                guard let target else { return }
                withAnimation { proxy.scrollTo(target, anchor: .center) }
                model.scrolledToTarget()
            }
        }
    }

    private var composerBar: some View {
        ComposerBar(model: model, media: media, openAttachments: { attaching = true }, openStickers: { showsStickers = true })
    }

    private var chatTitle: some View {
        HStack(spacing: 8) {
            AvatarView(name: model.roomName, mxcUrl: model.roomAvatarUrl, loader: media, size: 32, userId: model.peerUserId)
            Text(model.roomName).font(.headline).foregroundStyle(BipeColor.inkPrimary).lineLimit(1)
        }
    }

    @ViewBuilder
    private var historyHeader: some View {
        Group {
            if model.isLoadingHistory && !model.reachedStart {
                Text(BipeStrings.appTimelineLoadingHistory)
            } else {
                Color.clear.frame(height: 1)
            }
        }
        .font(.footnote)
        .foregroundStyle(BipeColor.inkSecondary)
        .padding(.vertical, 8)
        // Runs while the top of the list is on screen, again after each page (action loadHistory).
        .task(id: model.rows.count) {
            await model.loadHistory()
        }
    }

    @ViewBuilder
    private var noticeView: some View {
        if let notice = model.notice {
            Text(notice.text)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(BipeColor.surfaceRaised))
                .overlay(Capsule().stroke(BipeColor.lineSubtle))
                .padding(.bottom, 96)
                .transition(.opacity)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }
}

/// Spec typing-indicator: who is typing, above the composer.
private struct TypingLine: View {
    let names: [String]

    var body: some View {
        if let text {
            HStack(spacing: 6) {
                TypingDots()
                Text(text)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
            .onAppear { UIAccessibility.post(notification: .announcement, argument: text) }
        }
    }

    private var text: String? {
        switch names.count {
        case 0: nil
        case 1: BipeStrings.appTypingOne(names[0])
        case 2: BipeStrings.appTypingTwo(names[0], names[1])
        default: BipeStrings.appTypingMany(names[0])
        }
    }
}

/// The calm three dots of the typing line.
private struct TypingDots: View {
    @State private var phase = 0.0

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3) { index in
                Circle()
                    .fill(BipeColor.glyphSecondary)
                    .frame(width: 5, height: 5)
                    .opacity(0.35 + 0.65 * max(0, sin(phase - Double(index) * 0.8)))
            }
        }
        .accessibilityHidden(true)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: false)) {
                phase = .pi * 2
            }
        }
    }
}

private struct PollSheetId: Identifiable {
    let id: String
}

/// Picked files for upload-preview, with the reply target taken when they were picked.
private struct PickedUpload: Identifiable {
    let id = UUID()
    let urls: [URL]
    let replyToEventId: String?
}

/// Spec composer: Markdown text, @ mentions in groups, the draft, and the entries to attachments,
/// stickers and voice. The reply bar (message-reply) and the edit bar (message-edit) sit above it.
private struct ComposerBar: View {
    @ObservedObject var model: ChatTimelineModel
    let media: MediaLoader
    let openAttachments: () -> Void
    let openStickers: () -> Void
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !model.canPost {
                Text(BipeStrings.dmNoPermissionToPost)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(16)
            } else if let recording = model.recording {
                VoiceRecordingView(model: recording) { model.recording = nil }
            } else {
                composer
            }
        }
        .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
        .accessibilityIdentifier(BipeScreen.composer.rawValue)
        .onChange(of: model.replyingToRowId) { _, rowId in
            if rowId != nil { focused = true }
        }
        .onChange(of: model.editing) { _, editing in
            if editing != nil { focused = true }
        }
    }

    @ViewBuilder
    private var composer: some View {
        if !model.suggestions.isEmpty {
            suggestionList
        }
        if let reply = model.replyingTo {
            bar(icon: .arrowBendUpLeft,
                title: BipeStrings.appMessageReplyingTo(reply.senderName ?? ""),
                preview: reply.preview,
                onClose: model.cancelReply)
        } else if let editing = model.editing {
            bar(icon: .pencilSimple,
                title: BipeStrings.appMessageEditing,
                preview: editing.originalText,
                onClose: model.cancelEdit)
        }
        HStack(alignment: .bottom, spacing: 8) {
            if model.editing == nil {
                iconButton(.paperclip, label: BipeStrings.appComposerAttach, action: openAttachments)
            }
            HStack(alignment: .bottom, spacing: 4) {
                TextField(BipeStrings.appTimelineComposerPlaceholder,
                          text: Binding(get: { model.draft }, set: { model.editDraft($0) }),
                          axis: .vertical)
                    .focused($focused)
                    .lineLimit(1...(verticalSizeClass == .compact ? 3 : 5))
                    .padding(.vertical, 8)
                    .foregroundStyle(BipeColor.inkPrimary)
                if model.editing == nil {
                    Button(action: openStickers) {
                        PhosphorIcon.sticker.image
                            .resizable()
                            .frame(width: 20, height: 20)
                            .foregroundStyle(BipeColor.glyphSecondary)
                            .padding(.vertical, 8)
                    }
                    .accessibilityLabel(BipeStrings.actionStickers)
                }
            }
            .padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 20).fill(BipeColor.surfaceSunken))
            // The send button and the record button share one place (spec composer notes).
            if model.canSend || model.editing != nil {
                Button {
                    Task { await model.send() }
                } label: {
                    (model.editing == nil ? PhosphorIcon.paperPlaneRight : PhosphorIcon.check).image
                        .resizable()
                        .frame(width: 22, height: 22)
                        .padding(9)
                        .foregroundStyle(model.canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                        .background(Circle().fill(model.canSend ? BipeColor.accentFill : BipeColor.surfaceSunken))
                }
                .disabled(!model.canSend)
                .accessibilityLabel(model.editing == nil ? BipeStrings.appTimelineSend : BipeStrings.appMessageSaveEdit)
            } else {
                Button {
                    focused = false
                    model.startRecording()
                } label: {
                    PhosphorIcon.microphone.image
                        .resizable()
                        .frame(width: 22, height: 22)
                        .padding(9)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Circle().fill(BipeColor.accentFill))
                }
                .accessibilityLabel(BipeStrings.appComposerRecord)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    /// The @ suggestions of a group (spec composer, ADR 0141).
    private var suggestionList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(model.suggestions) { suggestion in
                    Button {
                        model.pickSuggestion(suggestion)
                    } label: {
                        HStack(spacing: 10) {
                            AvatarView(name: suggestion.name, mxcUrl: suggestion.avatarUrl, loader: media, size: 32)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(suggestion.name)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                if let handle = suggestion.handle {
                                    Text(handle)
                                        .font(.caption)
                                        .foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .frame(maxHeight: 200)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func iconButton(_ icon: PhosphorIcon, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            icon.image
                .resizable()
                .frame(width: 22, height: 22)
                .padding(9)
                .foregroundStyle(BipeColor.glyphPrimary)
        }
        .accessibilityLabel(label)
    }

    private func bar(icon: PhosphorIcon, title: String, preview: String?, onClose: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            icon.image
                .resizable()
                .frame(width: 18, height: 18)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.accentInk)
                if let preview {
                    Text(preview)
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            Button(action: onClose) {
                PhosphorIcon.x.image
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundStyle(BipeColor.glyphSecondary)
                    .padding(6)
            }
            .accessibilityLabel(BipeStrings.appCancel)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

/// signup-done notes: until the inviter's note arrives, the chat says the inviter knows, with a one-tap greeting.
private struct InviterHintRow: View {
    let hint: BipeInviterGreeting
    let inviterName: String
    let greet: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.inviterKnowsYouJoined(inviterName))
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            HStack {
                if let handle = hint.handle {
                    Text(BipeStrings.inviterHint(handle))
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                Spacer()
                Button(action: greet) {
                    Text(BipeStrings.inviterHintAction)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
            }
        }
        .padding(12)
        .background(BipeColor.accentWash)
    }
}

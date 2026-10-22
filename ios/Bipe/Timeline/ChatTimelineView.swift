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
    @State private var isGroup = false
    @State private var deletingDm = false
    @ObservedObject private var cleared = ClearedChatsStore.shared
    @Environment(\.closeChat) private var closeChat
    @State private var creatingPoll = false
    @State private var openPollId: String?
    @StateObject private var voicePlayer = VoicePlayer()
    @State private var viewerRowId: String?
    private let media: MediaLoader
    private let session: AccountSession
    private let roomId: String

    init(session: AccountSession, media: MediaLoader, roomId: String) {
        _model = StateObject(wrappedValue: ChatTimelineModel(session: session, roomId: roomId))
        self.media = media
        self.session = session
        self.roomId = roomId
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
            ComposerBar(model: model) { creatingPoll = true }
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .overlay(alignment: .bottom) { noticeView }
        .navigationTitle(model.roomName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isGroup {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        GroupDetailsView(session: session, media: media, roomId: roomId)
                    } label: {
                        PhosphorIcon.info.image.resizable().frame(width: 22, height: 22)
                    }
                    .accessibilityLabel(BipeStrings.appGroupDetailsTitle)
                }
            }
            if model.isDm {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(BipeStrings.dmDeleteAction, role: .destructive) {
                            deletingDm = true
                        }
                    } label: {
                        PhosphorIcon.info.image.resizable().frame(width: 22, height: 22)
                    }
                    .accessibilityLabel(BipeStrings.dmDetailsTitle)
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
        .task {
            // group-details is for groups only: RoomInfo.isDm is the one DM test (ADR 0141).
            if let info = try? await session.client.getRoom(roomId: roomId)?.roomInfo() {
                isGroup = !info.isDm && !info.isSpace
            }
        }
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

/// The composer with the reply bar (message-reply) and the edit bar (message-edit).
private struct PollSheetId: Identifiable {
    let id: String
}

private struct ComposerBar: View {
    @ObservedObject var model: ChatTimelineModel
    /// Opens poll-create (the attach menu comes with the composer spec, bipe-chat/bipe#563).
    let createPoll: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
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
                    Button(action: createPoll) {
                        PhosphorIcon.chartBar.image
                            .resizable()
                            .frame(width: 22, height: 22)
                            .padding(9)
                            .foregroundStyle(BipeColor.glyphPrimary)
                    }
                    .accessibilityLabel(BipeStrings.appPollCreateTitle)
                }
                TextField(BipeStrings.appTimelineComposerPlaceholder,
                          text: Binding(get: { model.draft }, set: { model.editDraft($0) }),
                          axis: .vertical)
                    .focused($focused)
                    .lineLimit(1...5)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 20).fill(BipeColor.surfaceSunken))
                    .foregroundStyle(BipeColor.inkPrimary)
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
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
        .onChange(of: model.replyingToRowId) { _, rowId in
            if rowId != nil { focused = true }
        }
        .onChange(of: model.editing) { _, editing in
            if editing != nil { focused = true }
        }
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

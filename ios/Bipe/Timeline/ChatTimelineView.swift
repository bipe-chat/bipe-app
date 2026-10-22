// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-timeline.
struct ChatTimelineView: View {
    @StateObject private var model: ChatTimelineModel
    private let media: MediaLoader
    /// Opens the chat's info: person-profile for a DM.
    private let openInfo: (() -> Void)?

    init(session: AccountSession, media: MediaLoader, roomId: String, openInfo: (() -> Void)? = nil) {
        _model = StateObject(wrappedValue: ChatTimelineModel(session: session, roomId: roomId))
        self.media = media
        self.openInfo = openInfo
    }

    var body: some View {
        VStack(spacing: 0) {
            messageList
            if let hint = model.inviterHint {
                InviterHintRow(hint: hint, inviterName: model.roomName) {
                    Task { await model.sendGreeting() }
                }
            }
            Composer(model: model)
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(model.roomName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let openInfo {
                ToolbarItem(placement: .principal) {
                    Button(action: openInfo) {
                        Text(model.roomName)
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .lineLimit(1)
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.chatTimeline.rawValue)
        .task { await model.start() }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    historyHeader
                    ForEach(model.messages) { message in
                        MessageRow(message: message) {
                            Task { await model.retrySend(id: message.id) }
                        }
                        .id(message.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: model.messages.last?.id) { _, lastId in
                if let lastId {
                    withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                }
            }
        }
    }

    @ViewBuilder
    private var historyHeader: some View {
        Group {
            if model.reachedStart {
                Text(BipeStrings.appTimelineStart)
            } else if model.isLoadingHistory {
                Text(BipeStrings.appTimelineLoadingHistory)
            } else {
                Color.clear.frame(height: 1)
            }
        }
        .font(.footnote)
        .foregroundStyle(BipeColor.inkSecondary)
        .padding(.vertical, 8)
        // Runs while the top of the list is on screen, again after each page (action loadHistory).
        .task(id: model.messages.count) {
            await model.loadHistory()
        }
    }
}

private struct MessageRow: View {
    let message: TimelineMessage
    let retry: () -> Void

    var body: some View {
        switch message.kind {
        case .text:
            bubble {
                if !message.isOwn {
                    Text(message.senderName)
                        .font(.caption.bold())
                        .foregroundStyle(BipeColor.accentInk)
                }
                Text(message.body ?? "")
                    .foregroundStyle(BipeColor.inkPrimary)
                    .textSelection(.enabled)
                sendStateLine
            }
        case .undecryptable:
            bubble {
                if !message.isOwn {
                    Text(message.senderName)
                        .font(.caption.bold())
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                HStack(alignment: .top, spacing: 6) {
                    PhosphorIcon.lockSimple.image
                        .resizable()
                        .frame(width: 14, height: 14)
                        .foregroundStyle(BipeColor.glyphSecondary)
                        .accessibilityHidden(true)
                    Text(undecryptableText)
                        .font(.subheadline.italic())
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
        case .unsupported:
            // The slice shows text only (spec chat-timeline notes). bipe-shared has no string for other events yet.
            EmptyView()
        }
    }

    /// Spec message-undecryptable: the cause in plain words.
    private var undecryptableText: String {
        switch message.cause {
        case .beforeJoined: BipeStrings.appUtdBeforeJoined
        case .historyNotOnThisPhone: BipeStrings.appUtdHistory
        case .notSharedWithThisPhone: BipeStrings.appUtdNotShared
        case .unapprovedSenderDevice: BipeStrings.appUtdUnapprovedDevice(message.senderName)
        case .waiting, .none: BipeStrings.appTimelineUndecryptable
        }
    }

    @ViewBuilder
    private var sendStateLine: some View {
        switch message.sendState {
        case .sent:
            EmptyView()
        case .sending:
            Text(BipeStrings.appTimelineSending)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
        case .failed:
            Button(action: retry) {
                HStack(spacing: 4) {
                    PhosphorIcon.warningCircle.image
                        .resizable()
                        .frame(width: 14, height: 14)
                        .accessibilityHidden(true)
                    Text(BipeStrings.appTimelineFailed)
                        .font(.caption)
                }
                .foregroundStyle(BipeColor.trustDangerInk)
            }
            .buttonStyle(.plain)
        }
    }

    private func bubble<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        HStack {
            if message.isOwn { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 4) {
                content()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 16).fill(message.isOwn ? BipeColor.bubbleOwn : BipeColor.bubbleOther))
            if !message.isOwn { Spacer(minLength: 48) }
        }
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

private struct Composer: View {
    @ObservedObject var model: ChatTimelineModel

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(BipeStrings.appTimelineComposerPlaceholder,
                      text: Binding(get: { model.draft }, set: { model.editDraft($0) }),
                      axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 20).fill(BipeColor.surfaceSunken))
                .foregroundStyle(BipeColor.inkPrimary)
            Button {
                Task { await model.send() }
            } label: {
                PhosphorIcon.paperPlaneRight.image
                    .resizable()
                    .frame(width: 22, height: 22)
                    .padding(9)
                    .foregroundStyle(model.canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                    .background(Circle().fill(model.canSend ? BipeColor.accentFill : BipeColor.surfaceSunken))
            }
            .disabled(!model.canSend)
            .accessibilityLabel(BipeStrings.appTimelineSend)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
    }
}

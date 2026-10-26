// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

extension View {
    /// Spec share-to-chat in the app: a pending share from the share extension shows on the next open, after the
    /// app lock, for the current account. Nothing leaves the phone before the chat choice and Send.
    func shareToChat(chatList: ChatListModel, openChat: @escaping (String) -> Void) -> some View {
        modifier(ShareToChatHost(chatList: chatList, openChat: openChat))
    }
}

/// A claimed share with its files moved into the app cache, where upload-preview keeps its sources.
struct PendingShare: Identifiable {
    let id: String
    let text: String
    let files: [URL]
    let droppedOverLimit: Bool

    /// Moves the files out of the app group, then deletes the claimed folder.
    init(_ claimed: ShareHandoff.Claimed) {
        id = claimed.manifest.id
        text = claimed.text
        droppedOverLimit = claimed.manifest.droppedOverLimit
        files = claimed.files.compactMap { source in
            guard let folder = try? MediaPreparation.workFolder() else { return nil }
            let target = folder.appendingPathComponent(source.lastPathComponent)
            return (try? FileManager.default.moveItem(at: source, to: target)) != nil ? target : nil
        }
        claimed.delete()
    }

    /// Action cancel: deletes every copy.
    func delete() {
        for file in files {
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        }
    }
}

private struct ShareToChatHost: ViewModifier {
    @ObservedObject var chatList: ChatListModel
    let openChat: (String) -> Void
    @ObservedObject private var lock = AppLockModel.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var share: PendingShare?

    func body(content: Content) -> some View {
        content
            .task { take() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { take() }
            }
            .onChange(of: lock.isLocked) { _, locked in
                if !locked { take() }
            }
            .fullScreenCover(item: $share) { share in
                ShareToChatView(chatList: chatList, share: share,
                                close: { sentTo in
                                    self.share = nil
                                    if let sentTo { openChat(sentTo) } else { share.delete() }
                                })
            }
    }

    /// The items wait while the app is locked (spec app-lock): a cover would show above the lock screen.
    private func take() {
        guard share == nil, !lock.isLocked, scenePhase == .active, let claimed = ShareHandoff.claim() else { return }
        share = PendingShare(claimed)
    }
}

/// The chat picker: search, recent chats first, an optional message, then Send or upload-preview.
private struct ShareToChatView: View {
    @ObservedObject var chatList: ChatListModel
    let share: PendingShare
    /// The chat that got the share, or nil when nothing was sent.
    let close: (String?) -> Void
    @State private var query = ""
    @State private var message: String
    @State private var picked: ChatSummary?
    @State private var isSending = false
    @State private var failed = false
    @State private var upload: ShareUpload?

    init(chatList: ChatListModel, share: PendingShare, close: @escaping (String?) -> Void) {
        self.chatList = chatList
        self.share = share
        self.close = close
        _message = State(initialValue: share.text)
    }

    private var hasFiles: Bool { !share.files.isEmpty }

    /// The room list order is newest activity first.
    private var chats: [ChatSummary] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return chatList.chats }
        return chatList.chats.filter { $0.name.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }

    private var canSend: Bool {
        picked != nil && !isSending && !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            List {
                if share.droppedOverLimit {
                    Text(BipeStrings.shareTooMany)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                if chats.isEmpty {
                    Text(query.isEmpty ? BipeStrings.appChatListEmpty : BipeStrings.appSearchNoResults(query))
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                ForEach(chats) { chat in
                    HStack {
                        Button {
                            pick(chat)
                        } label: {
                            ChatRow(chat: chat, media: chatList.media)
                        }
                        .accessibilityAddTraits(picked?.roomId == chat.roomId ? .isSelected : [])
                        // The picked chat: a check mark, not only a colour.
                        if picked?.roomId == chat.roomId {
                            PhosphorIcon.check.image
                                .resizable()
                                .frame(width: 20, height: 20)
                                .foregroundStyle(BipeColor.accentInk)
                                .accessibilityHidden(true)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .searchable(text: $query, prompt: BipeStrings.appSearchPlaceholder)
            .navigationTitle(BipeStrings.shareTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !hasFiles { composer }
            }
            .disabled(isSending)
            .alert(BipeStrings.errorGeneric, isPresented: $failed) {
                Button(BipeStrings.appClose, role: .cancel) {}
            }
            .fullScreenCover(item: $upload) { target in
                UploadPreviewView(model: target.model) {
                    let sent = target.model.didSend
                    upload = nil
                    if sent { close(target.roomId) }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.shareToChat.rawValue)
    }

    /// Text only: the optional message, prefilled with the shared text, and Send.
    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(BipeStrings.shareMessagePlaceholder, text: $message, axis: .vertical)
                .lineLimit(1...6)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 20).fill(BipeColor.surfaceSunken))
                .foregroundStyle(BipeColor.inkPrimary)
            Button {
                send()
            } label: {
                Group {
                    if isSending {
                        ProgressView().tint(BipeColor.inkOnAccent)
                    } else {
                        PhosphorIcon.paperPlaneRight.image
                            .resizable()
                            .frame(width: 22, height: 22)
                    }
                }
                .frame(width: 22, height: 22)
                .padding(9)
                .foregroundStyle(canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                .background(Circle().fill(canSend ? BipeColor.accentFill : BipeColor.surfaceSunken))
                .bipeTapTarget()
            }
            .disabled(!canSend)
            .accessibilityLabel(BipeStrings.appTimelineSend)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(BipeColor.surfaceBase)
    }

    /// Action pick: with files, upload-preview for that chat with the message as caption; else selects the chat.
    private func pick(_ chat: ChatSummary) {
        guard hasFiles else {
            picked = chat
            return
        }
        isSending = true
        Task {
            defer { isSending = false }
            guard let room = try? chatList.session.client.getRoom(roomId: chat.roomId),
                  let timeline = try? await room.timeline() else {
                failed = true
                return
            }
            let model = UploadPreviewModel(urls: share.files, client: chatList.session.client, timeline: timeline,
                                           replyToEventId: nil, caption: message)
            upload = ShareUpload(roomId: chat.roomId, model: model)
        }
    }

    /// Action send: text only, one text message to the picked chat, then that chat opens.
    private func send() {
        guard canSend, let picked else { return }
        isSending = true
        let body = message.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            defer { isSending = false }
            guard let room = try? chatList.session.client.getRoom(roomId: picked.roomId),
                  let timeline = try? await room.timeline(),
                  let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: body, formatted: nil))),
                  (try? await timeline.send(msg: content)) != nil else {
                failed = true
                return
            }
            close(picked.roomId)
        }
    }
}

private struct ShareUpload: Identifiable {
    let roomId: String
    let model: UploadPreviewModel
    var id: String { roomId }
}

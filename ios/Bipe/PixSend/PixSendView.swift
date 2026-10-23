// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

extension View {
    /// Spec pix-check-result, action sendToChat in the app: a pending preview from the share extension shows
    /// on a later open, for the current account only. Nothing leaves the phone before chat choice and Send.
    func pixHandoff(chatList: ChatListModel) -> some View {
        modifier(PixHandoffHost(chatList: chatList))
    }
}

private struct PixHandoffHost: ViewModifier {
    @ObservedObject var chatList: ChatListModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var claimed: ClaimedPreview?
    /// Kept apart from the sheet item, which is already nil when the sheet's onDismiss runs.
    @State private var current: PixHandoff.Claimed?

    func body(content: Content) -> some View {
        content
            .task { take() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { take() }
            }
            .sheet(item: $claimed, onDismiss: {
                // Sent, cancelled or dismissed: the preview is used once and goes.
                current?.delete()
                current = nil
            }) { item in
                PixSendView(chatList: chatList, claimed: item.value) { claimed = nil }
            }
    }

    private func take() {
        guard claimed == nil, current == nil, let preview = PixHandoff.claim() else { return }
        current = preview
        claimed = ClaimedPreview(value: preview)
    }
}

private struct ClaimedPreview: Identifiable {
    let value: PixHandoff.Claimed
    var id: String { value.preview.id }
}

/// The editable preview, the chat choice, then a final confirmation. Cancel deletes the preview.
private struct PixSendView: View {
    @ObservedObject var chatList: ChatListModel
    let claimed: PixHandoff.Claimed
    let close: () -> Void
    @State private var text: String
    @State private var chosen: ChatSummary?
    @State private var isSending = false
    @State private var failed = false
    @State private var upload: UploadTarget?

    init(chatList: ChatListModel, claimed: PixHandoff.Claimed, close: @escaping () -> Void) {
        self.chatList = chatList
        self.claimed = claimed
        self.close = close
        _text = State(initialValue: claimed.preview.text ?? "")
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    // The account that sends: confirmed here before any choice.
                    Text(verbatim: chatList.session.userId)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                    if let imageURL = claimed.imageURL, let image = UIImage(contentsOfFile: imageURL.path) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 220)
                    } else {
                        TextEditor(text: $text)
                            .frame(minHeight: 120)
                    }
                }
                Section {
                    ForEach(chatList.chats) { chat in
                        Button {
                            chosen = chat
                        } label: {
                            ChatRow(chat: chat, media: chatList.media)
                        }
                    }
                }
            }
            .navigationTitle(BipeStrings.pixCheckSendToChat)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel, action: close)
                }
            }
            .disabled(isSending)
            .alert(chosen?.name ?? "", isPresented: Binding(get: { chosen != nil }, set: { if !$0 { chosen = nil } })) {
                Button(BipeStrings.appTimelineSend) {
                    if let chosen { send(to: chosen.roomId) }
                }
                Button(BipeStrings.appCancel, role: .cancel) { chosen = nil }
            }
            .alert(BipeStrings.errorGeneric, isPresented: $failed) {
                Button(BipeStrings.appClose, role: .cancel) {}
            }
            .fullScreenCover(item: $upload) { target in
                UploadPreviewView(model: UploadPreviewModel(urls: [target.url], client: chatList.session.client,
                                                            timeline: target.timeline, replyToEventId: nil),
                                  close: close)
            }
        }
    }

    /// The final Send after the chat choice. A text goes as typed; an image goes through upload-preview.
    private func send(to roomId: String) {
        isSending = true
        Task {
            defer { isSending = false }
            guard let room = try? chatList.session.client.getRoom(roomId: roomId),
                  let timeline = try? await room.timeline() else {
                failed = true
                return
            }
            if let imageURL = claimed.imageURL {
                upload = UploadTarget(url: imageURL, timeline: timeline)
                return
            }
            let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !body.isEmpty,
                  let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: body, formatted: nil))),
                  (try? await timeline.send(msg: content)) != nil else {
                failed = true
                return
            }
            close()
        }
    }
}

private struct UploadTarget: Identifiable {
    let url: URL
    let timeline: Timeline
    var id: String { url.absoluteString }
}

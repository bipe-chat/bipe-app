// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Type ForwardChat of spec message-forward.
struct ForwardChat: Identifiable, Equatable {
    let roomId: String
    let name: String
    let avatarUrl: String?

    var id: String { roomId }
}

/// Spec message-forward (bipe#664, bipe#860): copies of sent messages to up to 5 chats.
@MainActor
final class MessageForwardModel: ObservableObject {
    /// Spec message-forward notes: at most this many chats per forward.
    static let limit = 5

    @Published var query = ""
    @Published private(set) var chats: [ForwardChat] = []
    @Published private(set) var picked: [String] = []
    @Published private(set) var isSending = false
    @Published var showsLimit = false

    private let client: Client
    /// The messages, in timeline order.
    private let msgTypes: [MessageType]

    init(client: Client, msgTypes: [MessageType]) {
        self.client = client
        self.msgTypes = msgTypes
    }

    /// State chats: joined chats, spaces left out, by name.
    func load() {
        // Spec drafts notes: never the drafts room.
        let draftsRoomId = (try? client.userId()).flatMap { AssistantDraftsStore.shared.roomId(for: $0) }
        chats = client.rooms()
            .filter { $0.membership() == .joined && !$0.isSpace() && $0.id() != draftsRoomId }
            .map { ForwardChat(roomId: $0.id(), name: $0.displayName()?.nilIfBlank ?? BipeStrings.dmNoName, avatarUrl: $0.avatarUrl()) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var shownChats: [ForwardChat] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return chats }
        return chats.filter { $0.name.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }

    /// Action toggle.
    func toggle(_ roomId: String) {
        if let index = picked.firstIndex(of: roomId) {
            picked.remove(at: index)
        } else if picked.count >= Self.limit {
            showsLimit = true
        } else {
            picked.append(roomId)
        }
    }

    /// Action send. Returns the names of the chats whose send failed. A failed message stops that chat,
    /// so the copies never arrive out of order.
    func send() async -> [String] {
        guard !picked.isEmpty, !isSending else { return [] }
        isSending = true
        defer { isSending = false }
        var failed: [String] = []
        for roomId in picked {
            do {
                guard let room = try client.getRoom(roomId: roomId) else { throw CancellationError() }
                let timeline = try await room.timeline()
                for msgType in msgTypes {
                    _ = try await timeline.send(msg: try messageEventContentNew(msgtype: msgType))
                }
            } catch {
                failed.append(chats.first { $0.roomId == roomId }?.name ?? roomId)
            }
        }
        return failed
    }
}

/// Spec message-forward: the chat picker, as a sheet over chat-timeline.
struct MessageForwardSheet: View {
    @StateObject private var model: MessageForwardModel
    let media: MediaLoader
    /// Called after the send with the names of the chats that failed; nil when the person cancelled.
    let close: ([String]?) -> Void

    init(client: Client, msgTypes: [MessageType], media: MediaLoader, close: @escaping ([String]?) -> Void) {
        _model = StateObject(wrappedValue: MessageForwardModel(client: client, msgTypes: msgTypes))
        self.media = media
        self.close = close
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(model.shownChats) { chat in
                    let isPicked = model.picked.contains(chat.roomId)
                    Button {
                        model.toggle(chat.roomId)
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, size: 40)
                            Text(chat.name)
                                .foregroundStyle(BipeColor.inkPrimary)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            // The picked chat: a check mark, not only a colour.
                            if isPicked {
                                PhosphorIcon.check.image
                                    .resizable()
                                    .frame(width: 20, height: 20)
                                    .foregroundStyle(BipeColor.accentInk)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                    .accessibilityAddTraits(isPicked ? .isSelected : [])
                }
            }
            .listStyle(.plain)
            .searchable(text: $model.query, prompt: BipeStrings.appSearchPlaceholder)
            .navigationTitle(BipeStrings.forwardTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.isSending {
                        ProgressView()
                    } else {
                        Button(BipeStrings.forwardAction) {
                            Task { close(await model.send()) }
                        }
                        .disabled(model.picked.isEmpty)
                    }
                }
            }
            .disabled(model.isSending)
            .alert(BipeStrings.forwardLimit(String(MessageForwardModel.limit)), isPresented: $model.showsLimit) {
                Button(BipeStrings.actionContinue, role: .cancel) {}
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.messageForward.rawValue)
        .task { model.load() }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import SwiftUI

/// Spec device-detail state fullContentRooms (bipe-chat/bipe#1515, ADR 0130 point 3): the joined chats where this
/// account's assistant reads every member. bipe-mcp sets the room state chat.bipe.assistant, with the account's user ID
/// as state key, before it reads another member's message. The Matrix auth rules let only that user send a state event
/// whose key is its own ID, so the content alone is enough.
@MainActor
final class AssistantReadChats: ObservableObject {
    struct Chat: Identifiable, Equatable {
        let id: String
        let name: String
    }

    enum Phase: Equatable {
        case loading
        case loaded([Chat])
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    let session: AccountSession
    private var task: Task<Void, Never>?

    init(session: AccountSession) {
        self.session = session
    }

    /// The first load, when the detail opens. Coming back from a chat keeps the list.
    func start() {
        if task == nil { load() }
    }

    /// Reads the marker of each joined chat, eight at a time. A late result from an old load is dropped.
    func load() {
        task?.cancel()
        phase = .loading
        let client = session.client
        let userId = session.userId
        let hidden = AccountPrivateRooms.ids(userId: userId)
        let rooms = client.rooms()
            .filter { $0.membership() == .joined && !$0.isSpace() && !hidden.contains($0.id()) }
            .map { (id: $0.id(), name: $0.displayName()?.nilIfBlank ?? BipeStrings.dmNoName) }
        task = Task { [weak self] in
            do {
                var chats: [Chat] = []
                var start = 0
                while start < rooms.count {
                    let batch = rooms[start..<min(start + 8, rooms.count)]
                    start += 8
                    try await withThrowingTaskGroup(of: Chat?.self) { group in
                        for room in batch {
                            group.addTask {
                                let data = try await ServerRoomState.content(client: client, roomId: room.id,
                                                                             eventType: BipeAssistantSend.readerEventType,
                                                                             stateKey: userId)
                                return Self.isOn(data) ? Chat(id: room.id, name: room.name) : nil
                            }
                        }
                        for try await chat in group {
                            if let chat { chats.append(chat) }
                        }
                    }
                    try Task.checkCancellation()
                }
                chats.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                guard let self, !Task.isCancelled else { return }
                self.phase = .loaded(chats)
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.phase = .failed
            }
        }
    }

    /// The marker is on when its content has the reader field set to true.
    nonisolated static func isOn(_ data: Data?) -> Bool {
        guard let data, let content = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return content[BipeAssistantSend.readerField] as? Bool == true
    }
}

/// The section of device-detail for kind assistant: the chats it reads in full, each opens the chat (action openChat).
struct AssistantReadChatsSection: View {
    @ObservedObject var chats: AssistantReadChats

    var body: some View {
        switch chats.phase {
        case .loading:
            Section {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        case .failed:
            Section {
                Button {
                    chats.load()
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(BipeStrings.deviceDetailReadsFailed)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(BipeStrings.appRetry)
                            .foregroundStyle(BipeColor.accentInk)
                    }
                }
            }
        case .loaded(let list) where list.isEmpty:
            Section {
                Text(BipeStrings.deviceDetailReadsOwnOnly)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
        case .loaded(let list):
            Section {
                ForEach(list) { chat in
                    NavigationLink {
                        ChatTimelineView(session: chats.session, media: MediaLoader(client: chats.session.client), roomId: chat.id)
                    } label: {
                        Text(chat.name)
                            .foregroundStyle(BipeColor.inkPrimary)
                    }
                }
            } header: {
                Text(BipeStrings.deviceDetailReadsAll)
            } footer: {
                Text(BipeStrings.deviceDetailReadsAllFooter)
            }
        }
    }
}

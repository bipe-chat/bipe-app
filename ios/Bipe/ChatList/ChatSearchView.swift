// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec chat-search: finds a chat or group by name among the person's own chats. Bipe has no directory.
@MainActor
final class ChatSearchModel: ObservableObject {
    @Published private(set) var results: [ChatSummary] = []
    @Published private(set) var isSearching = false
    @Published private(set) var query = ""

    private let session: AccountSession
    private var list: FilteredRoomList?
    private var debounce: Task<Void, Never>?

    init(session: AccountSession) {
        self.session = session
    }

    /// Action editQuery: sets the name filter, at most every 150 ms.
    func edit(_ text: String) {
        query = text
        debounce?.cancel()
        let pattern = text.trimmingCharacters(in: .whitespaces)
        guard !pattern.isEmpty else {
            results = []
            isSearching = false
            return
        }
        isSearching = true
        debounce = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled, let self else { return }
            if list == nil {
                list = FilteredRoomList { [weak self] rooms in self?.show(rooms) }
            }
            // Both tabs together; requests (invites) are left out by Joined.
            await list?.start(session: session, filter: .all(filters: [.joined, .nonSpace, .deduplicateVersions, .normalizedMatchRoomName(pattern: pattern)]))
        }
    }

    private func show(_ rooms: [Room]) {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        Task {
            var summaries: [ChatSummary] = []
            for room in rooms {
                if let summary = await ChatListModel.summary(of: room) { summaries.append(summary) }
            }
            results = summaries
            isSearching = false
        }
    }

    /// Action close.
    func close() {
        edit("")
    }

    /// findByHandle is offered when the query starts with @ and nothing matched.
    var offersHandleLookup: Bool {
        query.hasPrefix("@") && results.isEmpty && !isSearching
    }
}

/// The results under the search field of chat-list.
struct ChatSearchResults: View {
    @ObservedObject var search: ChatSearchModel
    let media: MediaLoader
    let onOpenChat: (String) -> Void

    var body: some View {
        List {
            if search.isSearching && search.results.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .listRowBackground(BipeColor.surfaceBase)
            } else if search.results.isEmpty {
                Text(BipeStrings.appSearchNoResults(search.query))
                    .foregroundStyle(BipeColor.inkSecondary)
                    .listRowBackground(BipeColor.surfaceBase)
                if search.offersHandleLookup {
                    // Action findByHandle: the start-chat lookup comes with people and profiles (bipe-chat/bipe#568).
                    Section {
                        Text(BipeStrings.appSearchFindHandle)
                            .foregroundStyle(BipeColor.inkDisabled)
                    } footer: {
                        Text(BipeStrings.startChatSearchByHandle)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
                }
            } else {
                ForEach(search.results) { chat in
                    // Action openChat.
                    Button {
                        onOpenChat(chat.roomId)
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chat.name)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                    .lineLimit(1)
                                // Each result shows whether it is a chat or a group.
                                Text(chat.isDirect ? BipeStrings.tabChats : BipeStrings.tabGroups)
                                    .font(.caption)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(BipeColor.surfaceBase)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier(BipeScreen.chatSearch.rawValue)
    }
}

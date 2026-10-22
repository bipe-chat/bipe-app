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
    private let notes: NotesRoom?
    private var list: FilteredRoomList?
    private var debounce: Task<Void, Never>?

    init(session: AccountSession, notes: NotesRoom? = nil) {
        self.session = session
        self.notes = notes
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
        let expectedQuery = query
        Task {
            var summaries: [ChatSummary] = []
            for room in rooms where room.id() != notes?.roomId {
                if let summary = await ChatListModel.summary(of: room, ownUserId: session.userId) { summaries.append(summary) }
            }
            guard query == expectedQuery else { return }
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
    var onFindHandle: (String) -> Void = { _ in }

    var body: some View {
        List {
            if search.query.trimmingCharacters(in: .whitespaces).isEmpty {
                EmptyView()
            } else if search.isSearching && search.results.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .listRowBackground(BipeColor.surfaceBase)
            } else if search.results.isEmpty {
                Text(BipeStrings.appSearchNoResults(search.query))
                    .foregroundStyle(BipeColor.inkSecondary)
                    .listRowBackground(BipeColor.surfaceBase)
                if search.offersHandleLookup {
                    Section {
                        Button(BipeStrings.appSearchFindHandle) { onFindHandle(search.query) }
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
                            AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, size: 40, userId: chat.peerUserId)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chat.name)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                    .lineLimit(1)
                                // Each result shows whether it is a chat or a group.
                                Text(chat.isDm ? BipeStrings.tabChats : BipeStrings.tabGroups)
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

/// The native search tab searches the account's SDK list, independently of unread filters and loaded pages.
struct AccountSearchView: View {
    private enum Destination: Hashable { case person(String), ownLink }
    @ObservedObject private var model: ChatListModel
    @StateObject private var search: ChatSearchModel
    @StateObject private var people: PeopleService
    @State private var path = NavigationPath()
    @State private var lookup: String?

    init(model: ChatListModel, api: BipeAPIClient) {
        _model = ObservedObject(wrappedValue: model)
        _search = StateObject(wrappedValue: ChatSearchModel(session: model.session, notes: model.notes))
        _people = StateObject(wrappedValue: PeopleService(session: model.session, api: api, media: model.media))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ChatSearchResults(search: search, media: model.media,
                              onOpenChat: { path.append($0) },
                              onFindHandle: { lookup = String($0.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines) })
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.appTabSearch)
                .navigationDestination(for: String.self) { roomId in
                    ChatTimelineView(session: model.session, media: model.media, roomId: roomId) {
                        Task { if let peer = await people.dmPartner(roomId: roomId) { path.append(Destination.person(peer)) } }
                    }
                }
                .navigationDestination(for: Destination.self) { destination in
                    switch destination {
                    case .person(let userId):
                        PersonProfileView(people: people, userId: userId, fromDm: false,
                                          routes: PersonProfileRoutes(openChat: { path.append($0) }))
                    case .ownLink:
                        ProfileLinkView(people: people, pickHandle: {})
                    }
                }
        }
        .searchable(text: Binding(get: { search.query }, set: search.edit), prompt: Text(BipeStrings.appSearchPlaceholder))
        .onAppear { people.start() }
        .onDisappear { search.close() }
        .sheet(item: Binding(get: { lookup.map(HandleLink.init) }, set: { lookup = $0?.handle })) { link in
            ProfileLinkOpenView(people: people, handle: link.handle,
                                openPerson: { path.append(Destination.person($0)) },
                                openOwnLink: { path.append(Destination.ownLink) })
        }
    }
}

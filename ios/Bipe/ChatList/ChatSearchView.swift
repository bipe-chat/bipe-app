// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import MatrixRustSDK
import SwiftUI

/// Spec chat-search: finds a chat or group by name among the person's own chats. Bipe has no directory.
@MainActor
final class ChatSearchModel: ObservableObject {
    @Published private(set) var results: [ChatSummary] = []
    @Published private(set) var isSearching = false
    @Published private(set) var query = ""
    /// State messages (bipe#671).
    @Published private(set) var messageHits: [MessageHit] = []
    /// State recent.
    @Published private(set) var recentQueries: [String] = []

    private let session: AccountSession
    private let messages: MessageSearchModel
    private let recent: RecentSearches
    private var messageChanges: AnyCancellable?
    private var recentChanges: AnyCancellable?
    private let notes: NotesRoom?
    private var list: FilteredRoomList?
    private var debounce: Task<Void, Never>?
    private var clearChanges: AnyCancellable?

    init(session: AccountSession, notes: NotesRoom? = nil) {
        self.session = session
        self.notes = notes
        messages = MessageSearchModel(session: session)
        recent = RecentSearches(userId: session.userId)
        clearChanges = ClearedChatsStore.shared.$revision.dropFirst().sink { [weak self] _ in self?.list?.refresh() }
        messageChanges = messages.$hits.sink { [weak self] in self?.messageHits = $0 }
        recentChanges = recent.$queries.sink { [weak self] in self?.recentQueries = $0 }
    }

    /// Action editQuery: sets the name filter, at most every 150 ms.
    func edit(_ text: String) {
        query = text
        debounce?.cancel()
        let pattern = text.trimmingCharacters(in: .whitespaces)
        guard !pattern.isEmpty else {
            results = []
            isSearching = false
            Task { await messages.search("") }
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
            await messages.search(pattern)
        }
    }

    /// Action openMessage keeps the query in recent; the view opens the chat.
    func openedResult() {
        recent.add(query)
    }

    /// Action clearRecent.
    func clearRecent() {
        recent.clear()
    }

    /// The last message row showed.
    func loadMoreMessages() {
        Task { await messages.loadMore() }
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
            let visible = await NativeDmSelection.visible(summaries, session: session)
            guard query == expectedQuery else { return }
            results = visible
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
    /// Action openMessage (bipe#671): room ID, event ID.
    var onOpenMessage: (String, String) -> Void = { _, _ in }

    var body: some View {
        List {
            if search.query.trimmingCharacters(in: .whitespaces).isEmpty {
                recentSection
            } else if search.isSearching && search.results.isEmpty && search.messageHits.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity)
            } else if search.results.isEmpty && search.messageHits.isEmpty {
                Text(BipeStrings.appSearchNoResults(search.query))
                    .foregroundStyle(BipeColor.inkSecondary)
                if search.offersHandleLookup {
                    Section {
                        Button(BipeStrings.appSearchFindHandle) { onFindHandle(search.query) }
                    } footer: {
                        Text(BipeStrings.startChatSearchByHandle)
                    }
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
                }
                messagesSection
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier(BipeScreen.chatSearch.rawValue)
    }

    /// State messages, under the chats.
    @ViewBuilder
    private var messagesSection: some View {
        if !search.messageHits.isEmpty {
            Section {
                ForEach(search.messageHits) { hit in
                    Button {
                        search.openedResult()
                        onOpenMessage(hit.roomId, hit.eventId)
                    } label: {
                        MessageHitRow(hit: hit).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .onAppear { if hit.id == search.messageHits.last?.id { search.loadMoreMessages() } }
                }
            } header: {
                Text(BipeStrings.searchMessages)
            }
        }
    }

    /// State recent, while the query is blank.
    @ViewBuilder
    private var recentSection: some View {
        if !search.recentQueries.isEmpty {
            Section {
                ForEach(search.recentQueries, id: \.self) { query in
                    Button(query) { search.edit(query) }
                        .foregroundStyle(BipeColor.inkPrimary)
                }
            } header: {
                HStack {
                    Text(BipeStrings.searchRecent)
                    Spacer()
                    Button(BipeStrings.searchRecentClear) { search.clearRecent() }
                        .font(.caption)
                }
            }
        }
    }
}

/// The native search tab searches the account's SDK list, independently of unread filters and loaded pages.
struct AccountSearchView: View {
    private enum Destination: Hashable { case person(String), ownLink, message(roomId: String, eventId: String) }
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
                              onFindHandle: { lookup = String($0.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines) },
                              onOpenMessage: { path.append(Destination.message(roomId: $0, eventId: $1)) })
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.appTabSearch)
                .navigationDestination(for: String.self) { roomId in
                    ChatTimelineView(session: model.session, media: model.media, roomId: roomId, people: people) {
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
                    case .message(let roomId, let eventId):
                        ChatTimelineView(session: model.session, media: model.media, roomId: roomId, people: people,
                                         focusEventId: eventId) {
                            Task { if let peer = await people.dmPartner(roomId: roomId) { path.append(Destination.person(peer)) } }
                        }
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

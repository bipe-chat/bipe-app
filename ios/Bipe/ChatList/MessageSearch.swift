// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Feeds the results of the SDK search service to an AsyncStream, like Common/Listeners.swift.
final class SearchResultsStream: SearchServiceResultsListener {
    let updates: AsyncStream<[SearchServiceResultsUpdate]>
    private let continuation: AsyncStream<[SearchServiceResultsUpdate]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(updates: [SearchServiceResultsUpdate]) {
        continuation.yield(updates)
    }
}

/// Type MessageHit of spec chat-search.
struct MessageHit: Identifiable, Equatable {
    let roomId: String
    let eventId: String
    let chatName: String
    let senderName: String
    let body: String
    let sentAt: Date

    var id: String { roomId + "|" + eventId }
}

/// State messages of spec chat-search (bipe#671): the SDK's local search index.
@MainActor
final class MessageSearchModel: ObservableObject {
    /// Spec chat-search notes (bipe-chat/bipe#861): in one chat, pages load until this many messages match.
    static let roomFill = 20
    /// The most pages that one setQuery loads for roomFill.
    static let roomFillPages = 5

    @Published private(set) var hits: [MessageHit] = []

    private let session: AccountSession
    /// Spec chat-search roomId: only the messages of this room. nil: every chat.
    private let roomId: String?
    private lazy var service = session.client.searchService()
    private var handle: TaskHandle?
    private var results: [SearchServiceResult] = []
    /// The pages loaded for roomFill since the last setQuery.
    private var fillPages = 0

    init(session: AccountSession, roomId: String? = nil) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    /// Runs with editQuery, after its 150 ms limit. A blank query clears the hits.
    func search(_ query: String) async {
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            hits = []
            return
        }
        if handle == nil {
            let stream = SearchResultsStream()
            handle = await service.subscribeToResults(listener: stream)
            Task { [weak self] in
                for await updates in stream.updates {
                    guard let self else { return }
                    for update in updates { results.apply(update.vectorDiff) }
                    hits = results.compactMap(hit)
                    fillRoom()
                }
            }
        }
        fillPages = 0
        try? await service.setQuery(query: query)
    }

    /// SearchService has no room filter: in one chat, the next page loads while too few messages match.
    private func fillRoom() {
        guard roomId != nil, !results.isEmpty, hits.count < Self.roomFill, fillPages < Self.roomFillPages else { return }
        fillPages += 1
        Task { try? await service.paginate() }
    }

    /// The last row showed: the next page.
    func loadMore() async {
        try? await service.paginate()
    }

    private func hit(_ result: SearchServiceResult) -> MessageHit? {
        guard case .message(let roomId, let message) = result, self.roomId == nil || self.roomId == roomId,
              case .msgLike(let content) = message.content, case .message(let text) = content.kind,
              // Spec drafts notes: the drafts room never shows in search.
              !AssistantDraftsStore.shared.isDraftsRoom(roomId, userId: session.userId) else { return nil }
        let room = try? session.client.getRoom(roomId: roomId)
        let sender = message.sender == session.userId ? BipeStrings.appYou : message.senderProfile.displayName(or: message.sender)
        let body = text.body.replacingOccurrences(of: "\n", with: " ")
        return MessageHit(roomId: roomId, eventId: message.eventId,
                          chatName: room?.displayName()?.nilIfBlank ?? BipeStrings.dmNoName,
                          senderName: sender, body: body, sentAt: Date(milliseconds: message.timestamp))
    }
}

/// State recent of spec chat-search: the last 10 queries that opened a result, on this phone, per account.
@MainActor
final class RecentSearches: ObservableObject {
    static let limit = 10

    @Published private(set) var queries: [String]
    private let key: String

    init(userId: String) {
        key = "recentSearches.\(userId)"
        queries = UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    func add(_ query: String) {
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return }
        queries = Array(([query] + queries.filter { $0 != query }).prefix(Self.limit))
        UserDefaults.standard.set(queries, forKey: key)
    }

    /// Action clearRecent.
    func clear() {
        queries = []
        UserDefaults.standard.removeObject(forKey: key)
    }
}

/// One message hit: the chat, the sender and the text, with the date. In one chat the row leaves out the chat name.
struct MessageHitRow: View {
    let hit: MessageHit
    var showsChat = true

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if showsChat {
                    Text(hit.chatName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .lineLimit(1)
                } else {
                    sender
                }
                Spacer(minLength: 8)
                Text(hit.sentAt, format: ChatRow.timeFormat(for: hit.sentAt))
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .monospacedDigit()
            }
            if showsChat { sender }
            Text(hit.body)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
                .lineLimit(2)
        }
        .accessibilityElement(children: .combine)
    }

    private var sender: some View {
        Text(hit.senderName)
            .font(.caption.weight(.medium))
            .foregroundStyle(BipeColor.inkSecondary)
            .lineLimit(1)
    }
}

/// Spec chat-search with roomId (bipe-chat/bipe#861): the messages of one chat, from the chat menu.
struct ChatMessageSearchView: View {
    @StateObject private var model: MessageSearchModel
    @State private var query = ""
    /// The query of the last search, for the no-results line.
    @State private var searched = ""
    /// Action openMessage with roomId: gets the event ID after the search closed.
    private let openMessage: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    init(session: AccountSession, roomId: String, openMessage: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: MessageSearchModel(session: session, roomId: roomId))
        self.openMessage = openMessage
    }

    var body: some View {
        NavigationStack {
            List {
                if !searched.isEmpty && model.hits.isEmpty {
                    Text(BipeStrings.appSearchNoResults(searched))
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                ForEach(model.hits) { hit in
                    Button {
                        dismiss()
                        openMessage(hit.eventId)
                    } label: {
                        MessageHitRow(hit: hit, showsChat: false).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .onAppear { if hit.id == model.hits.last?.id { Task { await model.loadMore() } } }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.searchInChat)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text(BipeStrings.searchInChat))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.chatSearch.rawValue)
        // Action editQuery: at most every 150 ms.
        .task(id: query) {
            guard (try? await Task.sleep(for: .milliseconds(150))) != nil else { return }
            await model.search(query)
            searched = query.trimmingCharacters(in: .whitespaces)
        }
    }
}

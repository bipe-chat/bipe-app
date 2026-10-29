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
    @Published private(set) var hits: [MessageHit] = []

    private let session: AccountSession
    private lazy var service = session.client.searchService()
    private var handle: TaskHandle?
    private var results: [SearchServiceResult] = []

    init(session: AccountSession) {
        self.session = session
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
                }
            }
        }
        try? await service.setQuery(query: query)
    }

    /// The last row showed: the next page.
    func loadMore() async {
        try? await service.paginate()
    }

    private func hit(_ result: SearchServiceResult) -> MessageHit? {
        guard case .message(let roomId, let message) = result,
              case .msgLike(let content) = message.content, case .message(let text) = content.kind else { return nil }
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

/// One message hit: the chat, the sender and the text, with the date.
struct MessageHitRow: View {
    let hit: MessageHit

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(hit.chatName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(hit.sentAt, format: ChatRow.timeFormat(for: hit.sentAt))
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .monospacedDigit()
            }
            Text(hit.senderName)
                .font(.caption.weight(.medium))
                .foregroundStyle(BipeColor.inkSecondary)
                .lineLimit(1)
            Text(hit.body)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
                .lineLimit(2)
        }
        .accessibilityElement(children: .combine)
    }
}

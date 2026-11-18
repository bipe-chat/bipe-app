// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Type MessageHit of spec chat-search.
struct MacMessageHit: Identifiable, Equatable {
    let roomID: String
    let eventID: String
    let chatName: String
    let senderName: String
    let body: String
    let sentAt: Date
    var id: String { roomID + "|" + eventID }
}

/// A message to open in a chat: chat-search openMessage runs chat-timeline openOriginal with the event.
struct MacMessageFocus: Equatable {
    let id = UUID()
    let roomID: String
    let eventID: String
}

/// State messages of spec chat-search (bipe#671) on the Mac: the SDK's local search index, as on iOS. Only rooms of
/// the account's chat list show, so requests and the private drafts and notes rooms never do.
@MainActor
final class MacMessageSearch: ObservableObject {
    /// Spec chat-search notes (bipe-chat/bipe#861): in one chat, pages load until this many messages match.
    static let roomFill = 20
    /// The most pages that one setQuery loads for roomFill.
    static let roomFillPages = 5

    @Published private(set) var hits: [MacMessageHit] = []
    /// The query of the last search, for the no-results line.
    @Published private(set) var searched = ""

    /// Spec chat-search roomId: only the messages of this room. nil: every chat of the list.
    private let roomID: String?
    private var service: SearchService?
    private weak var client: Client?
    private var handle: TaskHandle?
    private var listenTask: Task<Void, Never>?
    private var results: [SearchServiceResult] = []
    private var fillPages = 0
    private var ownUserID = ""
    /// Room ID to chat name, for the rooms that may show.
    private var rooms: [String: String] = [:]

    init(roomID: String? = nil) { self.roomID = roomID }

    deinit {
        handle?.cancel()
        listenTask?.cancel()
    }

    /// Runs with editQuery, after its 150 ms limit. A query without a letter or digit clears the hits.
    func search(_ text: String, client: Client?, rooms: [String: String]) async {
        self.rooms = rooms
        guard let client, let query = MessageSearchQuery.make(text) else {
            hits = []
            searched = text.trimmingCharacters(in: .whitespaces)
            return
        }
        if self.client !== client { reset(); self.client = client }
        ownUserID = (try? client.userId()) ?? ""
        let service = service ?? client.searchService()
        self.service = service
        if handle == nil {
            let stream = SearchResultsStream()
            handle = await service.subscribeToResults(listener: stream)
            listenTask = Task { [weak self] in
                for await updates in stream.updates {
                    guard let self else { return }
                    for update in updates { results.apply(update.vectorDiff) }
                    rebuild()
                }
            }
        }
        fillPages = 0
        try? await service.setQuery(query: query)
        searched = text.trimmingCharacters(in: .whitespaces)
    }

    /// The last row showed: the next page.
    func loadMore() async { try? await service?.paginate() }

    func clear() {
        hits = []
        searched = ""
    }

    private func reset() {
        handle?.cancel(); handle = nil
        listenTask?.cancel(); listenTask = nil
        service = nil
        results = []
        hits = []
    }

    private func rebuild() {
        hits = results.compactMap(hit)
        // SearchService has no room filter: in one chat, the next page loads while too few messages match.
        guard roomID != nil, !results.isEmpty, hits.count < Self.roomFill, fillPages < Self.roomFillPages else { return }
        fillPages += 1
        Task { try? await service?.paginate() }
    }

    private func hit(_ result: SearchServiceResult) -> MacMessageHit? {
        guard case .message(let roomID, let message) = result, self.roomID == nil || self.roomID == roomID,
              let chatName = rooms[roomID],
              case .msgLike(let content) = message.content, case .message(let text) = content.kind else { return nil }
        let sender = message.sender == ownUserID ? BipeStrings.appYou : MacRoomSummary.personName(message.senderProfile, userID: message.sender)
        return MacMessageHit(roomID: roomID, eventID: message.eventId, chatName: chatName, senderName: sender,
                             body: text.body.replacingOccurrences(of: "\n", with: " "),
                             sentAt: Date(timeIntervalSince1970: Double(message.timestamp) / 1000))
    }
}

/// One message hit: the chat, the sender and the text, with the date. In one chat the row leaves out the chat name.
struct MacMessageHitRow: View {
    let hit: MacMessageHit
    var showsChat = true

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if showsChat {
                    Text(hit.chatName).macFont(.subheadline).fontWeight(.semibold).foregroundStyle(BipeColor.inkPrimary).lineLimit(1)
                } else {
                    sender
                }
                Spacer(minLength: 8)
                Text(hit.sentAt, format: Self.timeFormat(for: hit.sentAt))
                    .macFont(.caption).foregroundStyle(BipeColor.inkSecondary).monospacedDigit()
            }
            if showsChat { sender }
            Text(hit.body).macFont(.subheadline).foregroundStyle(BipeColor.inkPrimary).lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var sender: some View {
        Text(hit.senderName).macFont(.caption).fontWeight(.medium).foregroundStyle(BipeColor.inkSecondary).lineLimit(1)
    }

    /// The chat-list date, as iOS ChatRow.timeFormat: the time today, else day and month.
    static func timeFormat(for date: Date) -> Date.FormatStyle {
        Calendar.current.isDateInToday(date) ? .dateTime.hour().minute() : .dateTime.day().month(.abbreviated)
    }
}

/// Spec chat-search with roomId (bipe-chat/bipe#861): the messages of one chat, from the chat toolbar.
struct MacChatSearchSheet: View {
    let roomID: String
    let chatName: String
    let client: Client
    /// Action openMessage with roomId: gets the event ID after the search closed.
    let openMessage: (String) -> Void
    let close: () -> Void
    @StateObject private var model: MacMessageSearch
    @State private var query = ""

    init(roomID: String, chatName: String, client: Client, openMessage: @escaping (String) -> Void, close: @escaping () -> Void) {
        self.roomID = roomID
        self.chatName = chatName
        self.client = client
        self.openMessage = openMessage
        self.close = close
        _model = StateObject(wrappedValue: MacMessageSearch(roomID: roomID))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(BipeStrings.searchInChat).macFont(.headline).padding([.horizontal, .top])
            TextField(BipeStrings.searchInChat, text: $query).textFieldStyle(.roundedBorder).padding()
            List {
                if !model.searched.isEmpty && model.hits.isEmpty {
                    Text(BipeStrings.appSearchNoResults(model.searched)).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
                }
                ForEach(model.hits) { hit in
                    Button { openMessage(hit.eventID) } label: { MacMessageHitRow(hit: hit, showsChat: false) }
                        .buttonStyle(.plain)
                        .onAppear { if hit.id == model.hits.last?.id { Task { await model.loadMore() } } }
                }
            }
            HStack {
                Spacer()
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.cancelAction)
            }
            .padding()
        }
        .frame(width: 460, height: 520)
        .accessibilityIdentifier(BipeScreen.chatSearch.rawValue)
        // Action editQuery: at most every 150 ms.
        .task(id: query) {
            guard (try? await Task.sleep(for: .milliseconds(150))) != nil else { return }
            await model.search(query, client: client, rooms: [roomID: chatName])
        }
    }
}

/// Spec chat-search recent: while the field is blank, the last queries that opened a result, and clearRecent.
struct MacRecentSearchSuggestions: View {
    @ObservedObject var recent: RecentSearches

    var body: some View {
        if !recent.queries.isEmpty {
            Section(BipeStrings.searchRecent) {
                ForEach(recent.queries, id: \.self) { query in
                    Text(query).searchCompletion(query)
                }
                Button(BipeStrings.searchRecentClear) { recent.clear() }
            }
        }
    }
}

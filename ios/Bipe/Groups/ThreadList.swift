// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec thread-list (bipe#667): the threads of a group.
@MainActor
final class ThreadListModel: ObservableObject {
    @Published private(set) var threads: [ThreadListItem] = []
    @Published private(set) var isLoading = true

    private let session: AccountSession
    private let roomId: String
    private var service: ThreadListService?
    private var reachedEnd = false
    private var paging = false

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    /// Action appear.
    func appear() async {
        guard service == nil, let room = try? session.client.getRoom(roomId: roomId) else {
            isLoading = false
            return
        }
        service = room.threadListService()
        await loadMore()
        isLoading = false
    }

    /// Action loadMore. A failed page changes nothing.
    func loadMore() async {
        guard let service, !reachedEnd, !paging else { return }
        paging = true
        defer { paging = false }
        let before = service.items().count
        guard (try? await service.paginate()) != nil else { return }
        let items = service.items()
        reachedEnd = items.count == before
        threads = items
    }
}

/// Spec thread-list.
struct ThreadListView: View {
    @StateObject private var model: ThreadListModel
    private let session: AccountSession
    private let media: MediaLoader
    private let roomId: String

    init(session: AccountSession, media: MediaLoader, roomId: String) {
        _model = StateObject(wrappedValue: ThreadListModel(session: session, roomId: roomId))
        self.session = session
        self.media = media
        self.roomId = roomId
    }

    var body: some View {
        List {
            if model.threads.isEmpty {
                if model.isLoading {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text(BipeStrings.threadListEmpty)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            ForEach(model.threads, id: \.rootEvent.eventId) { thread in
                NavigationLink {
                    ChatTimelineView(session: session, media: media, roomId: roomId, threadRoot: thread.rootEvent.eventId)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(thread.rootEvent.senderProfile.displayName(or: thread.rootEvent.sender))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(BipeColor.inkSecondary)
                        if let preview = preview(of: thread) {
                            Text(preview)
                                .lineLimit(2)
                                .foregroundStyle(BipeColor.inkPrimary)
                        }
                        Text(BipeStrings.threadReplies(thread.numReplies.formatted()))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    .accessibilityElement(children: .combine)
                }
                .onAppear {
                    if thread.rootEvent.eventId == model.threads.last?.rootEvent.eventId {
                        Task { await model.loadMore() }
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(BipeStrings.threadListTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.threadList.rawValue)
        .task { await model.appear() }
    }

    /// State preview: the body of a text root. The device decrypts it.
    private func preview(of thread: ThreadListItem) -> String? {
        guard case .msgLike(let content) = thread.rootEvent.content, case .message(let message) = content.kind else { return nil }
        return message.body
    }
}

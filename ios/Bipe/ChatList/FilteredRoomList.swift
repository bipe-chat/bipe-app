// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// One subscription to the room list service with its own filter (RoomList.entriesWithDynamicAdapters), for the
/// lists next to chat-list: requests, group invites, search results.
@MainActor
final class FilteredRoomList {
    private(set) var rooms: [Room] = []
    private var result: RoomListEntriesWithDynamicAdaptersResult?
    private var handle: TaskHandle?
    private var task: Task<Void, Never>?
    private let subscriptionOwner = UUID().uuidString
    private var subscriptions: BipeRoomSubscriptions?
    private let onChange: ([Room]) -> Void

    init(onChange: @escaping ([Room]) -> Void) {
        self.onChange = onChange
    }

    deinit {
        handle?.cancel()
        task?.cancel()
        let subscriptions = subscriptions
        let owner = subscriptionOwner
        Task { await subscriptions?.remove(owner: owner) }
    }

    /// Subscribes once, then sets the filter; a later call only changes the filter.
    func start(session: AccountSession, filter: RoomListEntriesDynamicFilterKind, pageSize: UInt32 = 50) async {
        subscriptions = session.roomSubscriptions
        if let result {
            _ = result.controller().setFilter(kind: filter)
            return
        }
        guard let roomList = try? await session.syncService.roomListService().allRooms() else { return }
        let stream = RoomListEntriesStream()
        let result = roomList.entriesWithDynamicAdapters(pageSize: pageSize, listener: stream)
        _ = result.controller().setFilter(kind: filter)
        handle = result.entriesStream()
        self.result = result
        task = Task { [weak self] in
            for await updates in stream.updates {
                guard let self else { return }
                for update in updates {
                    rooms.apply(update.vectorDiff)
                }
                onChange(rooms)
                await session.roomSubscriptions.update(owner: subscriptionOwner, roomIDs: rooms.map { $0.id() })
            }
        }
    }

    func loadMore() {
        result?.controller().addOnePage()
    }

    func refresh() { onChange(rooms) }
}

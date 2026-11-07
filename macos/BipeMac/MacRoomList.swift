// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation
import MatrixRustSDK

/// Joined, favourite and archived streams share one subscription union for this immutable account session.
@MainActor
final class MacRoomList: ObservableObject {
    @Published private(set) var rows: [MacRoomSummary] = []
    @Published private(set) var loaded = false
    @Published private(set) var failed = false
    @Published private(set) var actionFailed = false
    @Published private(set) var busy: Set<String> = []
    @Published private(set) var favouritesOrder: [String] = []
    private var client: Client?
    private var ownUserID = ""
    private var generation = UUID()
    private var entries: [RoomListEntriesWithDynamicAdaptersResult] = []
    private var tasks: [Task<Void, Never>] = []
    private var rebuildTask: Task<Void, Never>?
    private var orderTask: Task<Void, Never>?
    private var orderReadTask: Task<Void, Never>?
    private var rooms: [[Room]] = [[], [], []]
    private var subscriptions: BipeRoomSubscriptions?
    private var known: [String: BipeRowActivity.Row] = [:]
    private var pendingFavourite: [String: Bool] = [:]
    private var orderUnsaved = false

    func attach(client: Client, service: RoomListService, ownUserID: String) async throws {
        reset()
        self.client = client
        self.ownUserID = ownUserID
        let generation = generation
        subscriptions = BipeRoomSubscriptions { try await service.subscribeToRooms(roomIds: $0) }
        let list = try await service.allRooms()
        guard self.generation == generation else { throw CancellationError() }
        let filters: [RoomListEntriesDynamicFilterKind] = [
            .all(filters: [.joined, .nonSpace, .deduplicateVersions]),
            .all(filters: [.joined, .nonSpace, .favourite, .deduplicateVersions]),
            .all(filters: [.joined, .nonSpace, .lowPriority, .deduplicateVersions]),
        ]
        for (index, filter) in filters.enumerated() {
            let stream = RoomListEntriesStream()
            let entry = list.entriesWithDynamicAdapters(pageSize: 100, listener: stream)
            _ = entry.controller().setFilter(kind: filter)
            entries.append(entry)
            tasks.append(Task { [weak self] in
                for await updates in stream.updates {
                    guard let self, !Task.isCancelled, self.generation == generation else { return }
                    for update in updates { self.rooms[index].apply(update.vectorDiff) }
                    if index == 0 { self.loaded = true }
                    self.rebuild()
                    if index == 1 {
                        self.orderReadTask?.cancel()
                        self.orderReadTask = Task { await self.refreshFavouritesOrder() }
                    }
                }
            })
        }
        orderReadTask = Task { [weak self] in
            guard let self else { return }
            await self.refreshFavouritesOrder()
        }
    }

    func reset() {
        generation = UUID()
        entries.forEach { $0.entriesStream().cancel() }
        entries = []
        tasks.forEach { $0.cancel() }; tasks = []
        rebuildTask?.cancel(); rebuildTask = nil
        orderTask?.cancel(); orderTask = nil
        orderReadTask?.cancel(); orderReadTask = nil
        if let subscriptions { Task { await subscriptions.close() } }
        subscriptions = nil
        client = nil
        rooms = [[], [], []]; rows = []; known = [:]; pendingFavourite = [:]
        loaded = false; failed = false; actionFailed = false; busy = []
        favouritesOrder = []; orderUnsaved = false
    }

    func loadMore() { entries.forEach { $0.controller().addOnePage() } }

    func scoped(_ scope: MacDestination, isCompany: Bool, archived: Bool = false) -> [MacRoomSummary] {
        rows.filter { scope.includes(isDm: $0.isDm, isCompany: isCompany) && $0.isArchived == archived }
    }

    func favourites(_ scope: MacDestination, isCompany: Bool) -> [MacRoomSummary] {
        BipeFavouritesOrder.sorted(scoped(scope, isCompany: isCompany).filter(\.isFavourite), order: favouritesOrder,
                                   roomId: \.id, name: \.name)
    }

    func setFavourite(_ row: MacRoomSummary, value: Bool) async {
        guard !busy.contains(row.id), !row.isArchived else { return }
        let generation = generation
        busy.insert(row.id); actionFailed = false
        defer { if self.generation == generation { busy.remove(row.id) } }
        do {
            let info = try await row.room.roomInfo()
            guard info.membership == .joined, !info.isSpace, !info.isLowPriority else { return }
            try await row.room.setIsFavourite(isFavourite: value, tagOrder: nil)
            guard self.generation == generation else { return }
            pendingFavourite[row.id] = value
            // A first write before the first read would drop the order synced from other devices.
            if !orderRead { await refreshFavouritesOrder() }
            await saveOrder(value ? BipeFavouritesOrder.adding(row.id, to: favouritesOrder)
                                  : BipeFavouritesOrder.removing(row.id, from: favouritesOrder))
            rebuild()
        } catch { if self.generation == generation { actionFailed = true } }
    }

    func moveFavourite(_ id: String, to index: Int, shown: [String]) async {
        await saveOrder(BipeFavouritesOrder.moving(id, to: index, shown: shown, order: favouritesOrder))
    }

    func refreshFavouritesOrder() async {
        guard let client, !orderUnsaved else { return }
        let generation = generation
        let cached = try? await client.accountData(eventType: BipeFavouritesOrder.eventType)
        guard !Task.isCancelled, self.generation == generation, !orderUnsaved else { return }
        favouritesOrder = BipeFavouritesOrder.parse(cached)
        do {
            let json = try await ServerAccountData.json(client: client, eventType: BipeFavouritesOrder.eventType)
            guard !Task.isCancelled, self.generation == generation, !orderUnsaved else { return }
            favouritesOrder = BipeFavouritesOrder.parse(json)
            orderRead = true
        } catch { /* Offline lists keep the local account-data order. */ }
    }

    private var orderRead = false

    private func saveOrder(_ order: [String]) async {
        guard let client else { return }
        favouritesOrder = order; orderUnsaved = true
        let generation = generation
        let previous = orderTask
        let task = Task { [weak self] in
            await previous?.value
            guard let self, !Task.isCancelled, self.generation == generation else { return }
            do {
                try await client.setAccountData(eventType: BipeFavouritesOrder.eventType, content: BipeFavouritesOrder.content(order))
                if self.generation == generation, self.favouritesOrder == order { self.orderUnsaved = false }
            } catch { if self.generation == generation { self.actionFailed = true } }
        }
        orderTask = task
        await task.value
    }

    private func rebuild() {
        rebuildTask?.cancel()
        let generation = generation
        let ownUserID = ownUserID
        var seen = Set<String>()
        let combined = rooms.flatMap { $0 }.filter { seen.insert($0.id()).inserted }
        rebuildTask = Task { [weak self] in
            guard let self else { return }
            var rows: [MacRoomSummary] = []
            for room in combined {
                guard !Task.isCancelled, self.generation == generation else { return }
                guard let info = try? await room.roomInfo(), info.membership == .joined, !info.isSpace else { continue }
                let activity = MacRoomSummary.activity(await room.latestEvent(), known: self.known[room.id()])
                self.known[room.id()] = activity
                if self.pendingFavourite[room.id()] == info.isFavourite { self.pendingFavourite[room.id()] = nil }
                rows.append(MacRoomSummary(room: room, info: info, ownUserID: ownUserID,
                                           activity: activity, favourite: self.pendingFavourite[room.id()]))
            }
            guard !Task.isCancelled, self.generation == generation else { return }
            self.rows = rows
            await self.subscriptions?.update(owner: "lists", roomIDs: combined.map { $0.id() })
        }
    }
}

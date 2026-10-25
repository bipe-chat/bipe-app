// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Spec chat-list, bipe-chat/bipe#592: the local timeline events of rooms without an SDK latest event, for the row
/// date (BipeRowActivity). One scanner per account session; it reads only what the SDK already stored on this phone.
@MainActor
final class RowActivityScanner {
    private let client: Client
    private let onResults: @MainActor () -> Void
    private var events: [String: [BipeRowActivity.LocalEvent]] = [:]
    private var scannedAt: [String: ContinuousClock.Instant] = [:]
    private var queue: [String] = []
    private var worker: Task<Void, Never>?
    /// A room with no local events yet is read again after this, when the list updates.
    private static let emptyRetry: Duration = .seconds(30)

    init(client: Client, onResults: @escaping @MainActor () -> Void) {
        self.client = client
        self.onResults = onResults
    }

    deinit { worker?.cancel() }

    /// The local events read for the room, or nil when they were not read yet. Asks for a read when needed.
    func localEvents(roomId: String) -> [BipeRowActivity.LocalEvent]? {
        let cached = events[roomId]
        let stale = cached?.isEmpty == true && scannedAt[roomId].map { $0.duration(to: .now) > Self.emptyRetry } ?? true
        if cached == nil || stale, !queue.contains(roomId) {
            queue.append(roomId)
            startWorker()
        }
        return cached
    }

    /// The room changed in the room list: its newest events may be new.
    func invalidate(roomId: String) {
        events.removeValue(forKey: roomId)
        scannedAt.removeValue(forKey: roomId)
    }

    private func startWorker() {
        guard worker == nil else { return }
        worker = Task { [weak self] in
            var sinceUpdate = 0
            while let self, !Task.isCancelled, !self.queue.isEmpty {
                let roomId = self.queue.removeFirst()
                var found: [BipeRowActivity.LocalEvent] = []
                if let room = try? self.client.getRoom(roomId: roomId) { found = await Self.read(room) }
                guard !Task.isCancelled else { return }
                self.events[roomId] = found
                self.scannedAt[roomId] = .now
                sinceUpdate += 1
                // Publish in batches: each publish summarizes every row again.
                if self.queue.isEmpty || sinceUpdate >= 8 {
                    sinceUpdate = 0
                    self.onResults()
                }
            }
            self?.worker = nil
        }
    }

    /// The SDK sends the cached timeline items as the first update, before addListener returns (matrix-sdk-ffi
    /// Timeline.add_listener). No pagination, no network.
    private static func read(_ room: Room) async -> [BipeRowActivity.LocalEvent] {
        guard let timeline = try? await room.timeline() else { return [] }
        let stream = TimelineDiffStream()
        let handle = await timeline.addListener(listener: stream)
        defer { handle.cancel() }
        var items: [TimelineItem] = []
        for await diffs in stream.updates {
            diffs.forEach { items.apply($0.vectorDiff) }
            break
        }
        return items.compactMap { item in
            guard let event = item.asEvent() else { return nil }
            return BipeRowActivity.LocalEvent(timestampMs: event.timestamp, kind: kind(of: event.content))
        }
    }

    /// Message-like events, undecryptable ones included: the time of an encrypted event is not encrypted.
    private static func kind(of content: TimelineItemContent) -> BipeRowActivity.Kind {
        switch content {
        case .msgLike: .message
        default: .other
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Spec chat-list, bipe-chat/bipe#592: the local timeline events of every row, for the row date and text
/// (BipeRowActivity). One scanner per account session; it reads only what the SDK already stored on this phone.
@MainActor
final class RowActivityScanner {
    private let client: Client
    private let onResults: @MainActor () -> Void
    private var events: [String: [BipeRowActivity.LocalEvent]] = [:]
    private var scannedAt: [String: ContinuousClock.Instant] = [:]
    /// Rooms whose last read held an undecryptable message: its key can arrive later.
    private var undecryptable: Set<String> = []
    private var undecryptableReads: [String: Int] = [:]
    private var retryTask: Task<Void, Never>?
    private var queue: [String] = []
    private var worker: Task<Void, Never>?
    /// A room with no local events yet, or with an undecryptable message, is read again after this, when the list updates.
    private static let emptyRetry: Duration = .seconds(30)
    /// An undecryptable message whose key has not come after this many reads stays as it is until the room changes.
    private static let maxUndecryptableReads = 10

    init(client: Client, onResults: @escaping @MainActor () -> Void) {
        self.client = client
        self.onResults = onResults
    }

    deinit {
        worker?.cancel()
        retryTask?.cancel()
    }

    /// The local events read for the room, or nil when they were not read yet. Asks for a read when needed.
    func localEvents(roomId: String) -> [BipeRowActivity.LocalEvent]? {
        let cached = events[roomId]
        let retry = cached?.isEmpty == true || undecryptable.contains(roomId)
        let stale = retry && scannedAt[roomId].map { $0.duration(to: .now) > Self.emptyRetry } ?? true
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
        undecryptableReads.removeValue(forKey: roomId)
    }

    private func startWorker() {
        guard worker == nil else { return }
        worker = Task { [weak self] in
            var sinceUpdate = 0
            while let self, !Task.isCancelled, !self.queue.isEmpty {
                let roomId = self.queue.removeFirst()
                var found: (events: [BipeRowActivity.LocalEvent], undecryptable: Bool) = ([], false)
                if let room = try? self.client.getRoom(roomId: roomId) { found = await Self.read(room) }
                guard !Task.isCancelled else { return }
                self.events[roomId] = found.events
                let reads = found.undecryptable ? self.undecryptableReads[roomId, default: 0] + 1 : 0
                self.undecryptableReads[roomId] = reads
                if found.undecryptable, reads < Self.maxUndecryptableReads {
                    self.undecryptable.insert(roomId)
                } else {
                    self.undecryptable.remove(roomId)
                }
                self.scannedAt[roomId] = .now
                sinceUpdate += 1
                // Publish in batches: each publish summarizes every row again.
                if self.queue.isEmpty || sinceUpdate >= 8 {
                    sinceUpdate = 0
                    self.onResults()
                }
            }
            self?.worker = nil
            self?.scheduleRetry()
        }
    }

    /// A room key can arrive after the read without any room list change: ask for the rows again once the retry is due.
    private func scheduleRetry() {
        guard !undecryptable.isEmpty, retryTask == nil else { return }
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: Self.emptyRetry + .seconds(1))
            guard let self, !Task.isCancelled else { return }
            self.retryTask = nil
            self.onResults()
        }
    }

    /// The SDK sends the cached timeline items as the first update, before addListener returns (matrix-sdk-ffi
    /// Timeline.add_listener). No pagination, no network.
    private static func read(_ room: Room) async -> (events: [BipeRowActivity.LocalEvent], undecryptable: Bool) {
        guard let timeline = try? await room.timeline() else { return ([], false) }
        let stream = TimelineDiffStream()
        let handle = await timeline.addListener(listener: stream)
        defer { handle.cancel() }
        var items: [TimelineItem] = []
        for await diffs in stream.updates {
            diffs.forEach { items.apply($0.vectorDiff) }
            break
        }
        let events = items.compactMap { $0.asEvent() }
        let local = events.map { event in
            BipeRowActivity.LocalEvent(timestampMs: event.timestamp, kind: kind(of: event.content),
                                       previewText: event.content.previewText, isOwn: event.isOwn)
        }
        return (local, events.contains { isUndecryptable($0.content) })
    }

    private static func isUndecryptable(_ content: TimelineItemContent) -> Bool {
        guard case .msgLike(let content) = content, case .unableToDecrypt = content.kind else { return false }
        return true
    }

    /// Message-like events, undecryptable ones included: the time of an encrypted event is not encrypted.
    private static func kind(of content: TimelineItemContent) -> BipeRowActivity.Kind {
        switch content {
        case .msgLike: .message
        default: .other
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Combine
import Foundation

/// Contract: `HandleChange` (ADR 0066). A first set has no `old`, a removal has no `new`.
public struct BipeHandleChange: Codable, Sendable, Hashable {
    public let userId: String
    public let old: String?
    public let new: String?
    public let at: Date
    
    /// The most Matrix IDs per `POST /v1/handle-changes` call.
    public static let batchSize = 100
    
    public init(userId: String, old: String?, new: String?, at: Date) {
        self.userId = userId
        self.old = old
        self.new = new
        self.at = at
    }
    
    /// The timeline line. `name` is the person's display name in the room. `nil` when the change has neither handle.
    public func line(name: String) -> String? {
        switch (old, new) {
        case let (old?, new?): BipeStrings.handleChanged(name, new, old)
        case let (nil, new?): BipeStrings.handleSet(name, new)
        case let (old?, nil): BipeStrings.handleRemoved(name, old)
        case (nil, nil): nil
        }
    }
    
    /// A stable ID for the local timeline item.
    public var lineID: String {
        "bipe-handle-change-\(userId)-\(Int(at.timeIntervalSince1970 * 1000))"
    }
}

/// Where the handle-change lines go in a loaded timeline (ADR 0066).
public nonisolated enum BipeHandleChangePlacement {
    /// One loaded timeline item, oldest first. `timestamp` is `nil` for items without a time (pagination indicators).
    public struct Anchor: Sendable {
        public let timestamp: Date?
        
        public init(timestamp: Date?) {
            self.timestamp = timestamp
        }
    }
    
    /// The insertion index for each change that belongs in the loaded range, sorted by index then time.
    /// - A change older than the first loaded item is outside the range (back-pagination brings it in later).
    /// - A change newer than the last item shows at the end only when the timeline is live (`includesEnd`).
    /// - `members`: people who are members of the room now. Others get no line.
    /// - `joinedAt`: join times seen in the loaded items. A change before the person joined gets no line.
    public static func placements(of changes: [BipeHandleChange],
                                  in anchors: [Anchor],
                                  members: Set<String>,
                                  joinedAt: [String: Date],
                                  includesEnd: Bool) -> [(index: Int, change: BipeHandleChange)] {
        let timed = anchors.enumerated().compactMap { offset, anchor in anchor.timestamp.map { (offset, $0) } }
        guard let first = timed.first, let last = timed.last else { return [] }
        
        var result: [(index: Int, change: BipeHandleChange)] = []
        for change in changes where members.contains(change.userId) {
            guard change.at >= first.1 else { continue }
            if let joined = joinedAt[change.userId], change.at < joined {
                continue
            }
            if change.at > last.1 {
                if includesEnd {
                    result.append((last.0 + 1, change))
                }
                continue
            }
            // Before the first item that is newer than the change.
            let index = timed.first { $0.1 > change.at }?.0 ?? last.0 + 1
            result.append((index, change))
        }
        return result.sorted { ($0.index, $0.change.at) < ($1.index, $1.change.at) }
    }
}

/// The handle change history of the people in the user's rooms, cached on the device (ADR 0066).
/// One instance per user session. Fetches through `POST /v1/handle-changes` in chunks of 100,
/// with `since` set to the latest cached change of the chunk's people.
@MainActor
public final class BipeHandleChangeStore {
    private let apiClient: BipeAPIClient
    private let openID: BipeOpenIDTokens
    private let fileURL: URL?
    
    private var changesByUser: [String: [BipeHandleChange]]
    /// People asked about at least once.
    private var fetchedUsers: Set<String>
    private var inFlight: Set<String> = []
    
    private let changesSubject = PassthroughSubject<Void, Never>()
    /// Fires on the main actor when new changes arrive.
    public var changesPublisher: AnyPublisher<Void, Never> {
        changesSubject.eraseToAnyPublisher()
    }
    
    private struct Cache: Codable {
        var changes: [BipeHandleChange]
        var fetchedUsers: [String]
    }
    
    /// `cacheDirectory`: where the per-account cache file lives. `nil` keeps it in memory only.
    public init(apiClient: BipeAPIClient, userID: String, cacheDirectory: URL?, openID: BipeOpenIDTokens) {
        self.apiClient = apiClient
        self.openID = openID
        let safeName = userID.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? String($0) : "_" }.joined()
        fileURL = cacheDirectory?.appending(path: "bipe-handle-changes-\(safeName).json")
        
        let cache = fileURL.flatMap { try? Data(contentsOf: $0) }.flatMap { try? Self.decoder.decode(Cache.self, from: $0) }
        changesByUser = Dictionary(grouping: cache?.changes ?? [], by: \.userId)
        fetchedUsers = Set(cache?.fetchedUsers ?? [])
    }
    
    /// Cached changes of these people, oldest first.
    public func changes(for userIDs: Set<String>) -> [BipeHandleChange] {
        userIDs.flatMap { changesByUser[$0] ?? [] }.sorted { $0.at < $1.at }
    }
    
    /// The people with cached changes or an earlier fetch.
    public var knownUserIDs: Set<String> {
        fetchedUsers.union(changesByUser.keys)
    }
    
    /// Fetches new changes for these people. Failures keep the cache; the next call tries again.
    public func refresh(userIDs: some Sequence<String>) async {
        let wanted = Set(userIDs).subtracting(inFlight)
        guard !wanted.isEmpty else { return }
        inFlight.formUnion(wanted)
        defer { inFlight.subtract(wanted) }
        
        // People without cached changes go without `since`; the others share the oldest of their latest times.
        let (known, unknown) = wanted.sorted().reduce(into: ([String](), [String]())) { result, userID in
            if changesByUser[userID]?.isEmpty == false {
                result.0.append(userID)
            } else {
                result.1.append(userID)
            }
        }
        
        var gotNew = false
        for chunk in Self.chunks(unknown) {
            gotNew = await fetch(chunk, since: nil) || gotNew
        }
        for chunk in Self.chunks(known) {
            gotNew = await fetch(chunk, since: since(for: chunk)) || gotNew
        }
        
        save()
        if gotNew {
            changesSubject.send()
        }
    }
    
    /// The latest cached time of each person, then the oldest of those. `nil` when one of them has no cached change.
    func since(for userIDs: [String]) -> Date? {
        let latest = userIDs.map { changesByUser[$0]?.last?.at }
        guard !latest.contains(where: { $0 == nil }) else { return nil }
        return latest.compactMap(\.self).min()
    }
    
    // MARK: - Private
    
    private static func chunks(_ userIDs: [String]) -> [[String]] {
        stride(from: 0, to: userIDs.count, by: BipeHandleChange.batchSize).map {
            Array(userIDs[$0..<min($0 + BipeHandleChange.batchSize, userIDs.count)])
        }
    }
    
    /// `true` when the answer had a change the cache did not have.
    private func fetch(_ userIDs: [String], since: Date?) async -> Bool {
        do {
            let changes = try await BipeAPIClient.withOpenIDRetry(openID) { [apiClient] (token: BipeOpenIDToken) async throws(BipeAPIError) -> [BipeHandleChange] in
                try await apiClient.handleChanges(userIDs: userIDs, since: since, openID: token)
            }
            fetchedUsers.formUnion(userIDs)
            var gotNew = false
            for change in changes where userIDs.contains(change.userId) {
                var list = changesByUser[change.userId] ?? []
                guard !list.contains(change) else { continue }
                list.append(change)
                list.sort { $0.at < $1.at }
                changesByUser[change.userId] = list
                gotNew = true
            }
            return gotNew
        } catch {
            return false
        }
    }
    
    private func save() {
        guard let fileURL else { return }
        let cache = Cache(changes: changesByUser.values.flatMap(\.self), fetchedUsers: fetchedUsers.sorted())
        guard let data = try? Self.encoder.encode(cache) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        return encoder
    }()
    
    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return decoder
    }()
}

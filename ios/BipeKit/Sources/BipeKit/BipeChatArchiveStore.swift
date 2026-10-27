// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation

/// Local baselines and migration metadata. Matrix owns the archive tag.
public nonisolated struct BipeChatArchiveStore {
    public let file: URL
    public struct State: Sendable {
        public let markers: [String: Int64]
        public let syncedRooms: Set<String>
    }
    private struct Snapshot: Codable { let version: Int; let markers: [String: Int64]; let syncedRooms: [String]? }
    private enum Failure: Error { case invalid }

    public init(directory: URL, accountId: String) {
        let owner = SHA256.hash(data: Data(accountId.utf8)).map { String(format: "%02x", $0) }.joined()
        file = directory.appendingPathComponent(owner + ".json")
    }

    public func load() throws -> [String: Int64] {
        try loadState().markers
    }

    public func loadState() throws -> State {
        guard FileManager.default.fileExists(atPath: file.path) else { return State(markers: [:], syncedRooms: []) }
        let value = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: file))
        let synced = Set(value.syncedRooms ?? [])
        guard value.version == 1, valid(value.markers), synced.isSubset(of: Set(value.markers.keys)) else { throw Failure.invalid }
        return State(markers: value.markers, syncedRooms: synced)
    }

    public func save(_ markers: [String: Int64], syncedRooms: Set<String> = []) throws {
        guard valid(markers), syncedRooms.isSubset(of: Set(markers.keys)) else { throw Failure.invalid }
        _ = try loadState()
        var directory = file.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        let data = try JSONEncoder().encode(Snapshot(version: 1, markers: markers, syncedRooms: syncedRooms.sorted()))
        #if os(iOS)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: file, options: .atomic)
        #endif
    }

    private func valid(_ markers: [String: Int64]) -> Bool {
        markers.allSatisfy { !$0.key.isEmpty && $0.value >= 0 && $0.value <= 9_007_199_254_740_991 }
    }
}

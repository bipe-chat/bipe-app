// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation

/// Local archive metadata only. The caller owns serial mutations and publishes only after a successful save.
public nonisolated struct BipeChatArchiveStore {
    public let file: URL
    private struct Snapshot: Codable { let version: Int; let markers: [String: Int64] }
    private enum Failure: Error { case invalid }

    public init(directory: URL, accountId: String) {
        let owner = SHA256.hash(data: Data(accountId.utf8)).map { String(format: "%02x", $0) }.joined()
        file = directory.appendingPathComponent(owner + ".json")
    }

    public func load() throws -> [String: Int64] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [:] }
        let value = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: file))
        guard value.version == 1, valid(value.markers) else { throw Failure.invalid }
        return value.markers
    }

    public func save(_ markers: [String: Int64]) throws {
        guard valid(markers) else { throw Failure.invalid }
        var directory = file.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        let data = try JSONEncoder().encode(Snapshot(version: 1, markers: markers))
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

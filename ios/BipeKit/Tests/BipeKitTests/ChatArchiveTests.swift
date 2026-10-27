// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct ChatArchiveTests {
    @Test func serverSyncCases() throws {
        for v in try SharedVectors.array("archive-sync.json", key: "cases") {
            let expected = try #require(v["expected"] as? [String: Any])
            let result = BipeChatArchive.reconcile(marker: (v["marker"] as? NSNumber)?.int64Value,
                synced: v["synced"] as? Bool ?? false, serverArchived: v["serverArchived"] as? Bool ?? false,
                activity: (v["activity"] as? NSNumber)?.int64Value, incoming: (v["incoming"] as? NSNumber)?.int64Value)
            #expect(result.marker == (expected["marker"] as? NSNumber)?.int64Value, "\(v["name"] ?? "") marker")
            #expect(result.synced == expected["synced"] as? Bool, "\(v["name"] ?? "") migration")
            #expect(result.tagWrite == expected["tagWrite"] as? Bool, "\(v["name"] ?? "") tag write")
        }
    }

    @Test func migrationMetadataIsAtomicAndLegacyCompatible() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = BipeChatArchiveStore(directory: directory, accountId: "@a:example.invalid")
        try store.save(["!room:example.invalid": 100])
        #expect(try store.loadState().syncedRooms.isEmpty)
        try store.save(["!room:example.invalid": 100], syncedRooms: ["!room:example.invalid"])
        #expect(try store.loadState().syncedRooms == ["!room:example.invalid"])
        #expect(throws: (any Error).self) { try store.save([:], syncedRooms: ["!room:example.invalid"]) }
        #expect(try store.loadState().markers == ["!room:example.invalid": 100])
        #expect(try store.loadState().syncedRooms == ["!room:example.invalid"])
        try store.save([:], syncedRooms: [])
        #expect(try store.loadState().syncedRooms.isEmpty)
        // A file made before archive sync has no migration field.
        try Data(#"{"version":1,"markers":{"!old:example.invalid":50}}"#.utf8).write(to: store.file)
        #expect(try store.loadState().markers == ["!old:example.invalid": 50])
        #expect(try store.loadState().syncedRooms.isEmpty)
    }

    @Test func incomingRestoresButOwnOrOldActivityDoesNot() {
        #expect(BipeChatArchive.isArchived(marker: 100, incoming: nil))
        #expect(BipeChatArchive.isArchived(marker: 100, incoming: 100))
        #expect(BipeChatArchive.isArchived(marker: 100, incoming: 99))
        #expect(!BipeChatArchive.isArchived(marker: 100, incoming: 101))
        #expect(BipeChatArchive.isArchived(marker: 0, incoming: nil))
        #expect(!BipeChatArchive.isArchived(marker: nil, incoming: 100))
    }

    @Test func persistsOfflineAndIsolatesOwners() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let a = BipeChatArchiveStore(directory: directory, accountId: "@a:example.invalid")
        let b = BipeChatArchiveStore(directory: directory, accountId: "@b:example.invalid")
        try a.save(["!room:example.invalid": 100])
        #expect(try BipeChatArchiveStore(directory: directory, accountId: "@a:example.invalid").load() == ["!room:example.invalid": 100])
        #expect(try b.load().isEmpty)
        try a.save([:])
        #expect(try a.load().isEmpty)
        #expect(!a.file.lastPathComponent.contains("@a"))
        try Data("corrupt".utf8).write(to: a.file)
        #expect(throws: (any Error).self) { try a.load() }
        #expect(try String(contentsOf: a.file, encoding: .utf8) == "corrupt")
    }

    @Test func failedWriteKeepsPreviousSnapshot() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = BipeChatArchiveStore(directory: directory, accountId: "@a:example.invalid")
        try store.save(["!kept:example.invalid": 100])
        #expect(throws: (any Error).self) { try store.save(["!invalid:example.invalid": -1]) }
        #expect(try store.load() == ["!kept:example.invalid": 100])
    }
}

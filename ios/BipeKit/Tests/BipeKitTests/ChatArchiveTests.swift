// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct ChatArchiveTests {
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

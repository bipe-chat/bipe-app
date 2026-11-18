// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import XCTest
@testable import BipeMac

final class MacSearchTests: XCTestCase {
    func testSearchIndexLivesInTheAccountDataFolder() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let paths = try MacAccount.paths(folder: UUID().uuidString, root: root)
        let index = try XCTUnwrap(MacAccount.searchIndexPath(data: paths.data))
        XCTAssertEqual(index.deletingLastPathComponent().standardizedFileURL, paths.data.standardizedFileURL)
        var isFolder: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: index.path, isDirectory: &isFolder))
        XCTAssertTrue(isFolder.boolValue)
    }

    @MainActor
    func testRecentSearchesKeepTenNewestFirstWithoutRepeats() {
        let recent = RecentSearches(userId: "@mac-tests-" + UUID().uuidString + ":example.invalid")
        defer { recent.clear() }
        for index in 0..<12 { recent.add("q\(index)") }
        recent.add("q5")
        XCTAssertEqual(recent.queries.count, RecentSearches.limit)
        XCTAssertEqual(recent.queries.first, "q5")
        XCTAssertEqual(recent.queries.filter { $0 == "q5" }.count, 1)
    }

    @MainActor
    func testBlankOrSymbolQueryHasNoMessageHits() async {
        let search = MacMessageSearch()
        await search.search("!!", client: nil, rooms: [:])
        XCTAssertTrue(search.hits.isEmpty)
        XCTAssertEqual(search.searched, "!!")
    }
}

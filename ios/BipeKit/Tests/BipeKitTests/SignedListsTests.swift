// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import BipeCore
import Foundation
import Testing

/// SignedLists (bipe-chat/bipe#645): the bundled lists, the stored envelope and the "keep the current list" rule.
/// bipe-core's own CI runs every case of test-vectors/signed-lists.json; these check the app's use of it.
struct SignedListsTests {
    @Test
    func bundledListsVerify() throws {
        let lists = SignedLists(directory: nil)
        let operators = try #require(lists.current(.operators))
        let issuers = try #require(lists.current(.issuers))
        #expect(operators.version >= 1)
        #expect(issuers.version >= 1)
        #expect(operators.operators?.region.coreApi.hasPrefix("https://") == true)
    }

    @Test
    func defaultServerCountsAlsoWhenStale() {
        let lists = SignedLists(directory: nil)
        let far = Date(timeIntervalSince1970: 4_102_444_800)
        #expect(lists.isBipeServer("bipe.net.br"))
        #expect(lists.isBipeServer("bipe.net.br", now: far))
        #expect(!lists.isBipeServer("matrix.org"))
        #expect(!lists.isBipeServer("matrix.org", now: far))
    }

    @Test
    func rejectedEnvelopeKeepsTheCurrentList() throws {
        let lists = SignedLists(directory: nil)
        let before = try #require(lists.current(.operators))
        #expect(lists.accept("{}", type: .operators, now: Date()) != "valid")
        #expect(lists.accept(SignedLists.bundled(.issuers), type: .operators, now: Date()) == "wrong_type")
        #expect(lists.current(.operators) == before)
    }

    @Test
    func acceptedEnvelopeIsStoredAndLoadedAgain() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("signed-lists-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let lists = SignedLists(directory: dir)
        let bundled = SignedLists.bundled(.operators)
        let issued = try #require(lists.current(.operators)).issued
        // The same version with the same payload is valid; it is stored as received.
        #expect(lists.accept(bundled, type: .operators, now: Date(timeIntervalSince1970: TimeInterval(issued))) == "valid")
        let stored = try String(contentsOf: dir.appendingPathComponent("operators.json"), encoding: .utf8)
        #expect(stored == bundled)
        #expect(SignedLists(directory: dir).current(.operators) == lists.current(.operators))
    }

    @Test
    func storedEnvelopeThatFailsIsIgnored() throws {
        let list = SignedLists.newest(bundled: SignedLists.bundled(.operators), stored: "not json", type: .operators)
        #expect(list?.version == SignedLists(directory: nil).current(.operators)?.version)
    }

    @Test
    func upperCaseServerIsNotBipe() {
        #expect(BipeServers.isBipeUserID("@ana:bipe.net.br"))
        #expect(!BipeServers.isBipeUserID("@ana:BIPE.NET.BR"))
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Security
import Testing

/// Synthetic keychain records and a temporary folder only (bipe-chat/bipe#603). No system keychain call.
struct LegacyDataTests {
    private let team = "TEAM.test"
    private let fork = "FORK.test"
    private let own = BipeCredentialStore.keychainServices

    private func subject(_ fake: LegacyFakeKeychain) -> BipeLegacyData {
        BipeLegacyData(groups: [team, fork, "$(UNSET)"], ownServices: own, keychain: fake)
    }

    @Test func onlyServicesThisAppNeverWritesCount() {
        let fake = LegacyFakeKeychain()
        fake.seed(group: team, service: "chat.bipe.account.password", account: "@ana:example.test", value: "fixture secret")
        #expect(!subject(fake).isPresent)
        fake.seed(group: fork, service: "fixture.session", account: "@ana:example.test", value: #"{"accessToken":"fixture-token-value"}"#)
        let items = subject(fake).foreignItems()
        #expect(items.count == 1)
        #expect(items.first?.service == "fixture.session")
        #expect(items.first?.account == "@…:example.test")
        #expect(!String(describing: items).contains("fixture-token-value"))
        #expect(fake.mutations == 0)
    }

    @Test func reportHasStructureAndNoValue() throws {
        let fake = LegacyFakeKeychain()
        fake.seed(group: fork, service: "fixture.session", account: "@ana:example.test",
                  value: #"{"accessToken":"fixture-token-value","deviceId":"FIXTUREDEV","accounts":{"@bia:example.test":{"password":"fixture-pass"}},"list":[1,2]}"#)
        fake.seed(group: fork, service: "fixture.blob", account: "a1b2c3d4e5f6a7b8c9d0e1f2", value: "plain fixture text")
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("legacy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder.appendingPathComponent("@ana:example.test"), withIntermediateDirectories: true)
        try Data("fixture body".utf8).write(to: folder.appendingPathComponent("@ana:example.test/notes.txt"))
        try Data(repeating: 7, count: 32).write(to: folder.appendingPathComponent("store.sqlite3"))
        defer { try? FileManager.default.removeItem(at: folder) }

        let data = subject(fake).report(folders: ["appGroup": folder], defaults: [:])
        let text = try #require(String(data: data, encoding: .utf8))
        for secret in ["fixture-token-value", "FIXTUREDEV", "fixture-pass", "plain fixture text", "fixture body", "@ana", "@bia", "a1b2c3d4e5f6"] {
            #expect(!text.contains(secret), "report leaks \(secret)")
        }
        #expect(text.contains("accessToken"))
        #expect(text.contains("string(19)"))
        #expect(text.contains("@…:example.test"))
        #expect(text.contains("text(18)"))
        #expect(text.contains("notes.txt"))
        #expect(text.contains("not plain"))
        #expect(fake.mutations == 0)
    }

    @Test func redactKeepsServiceNamesAndHidesTheRest() {
        #expect(BipeLegacyShape.redact("chat.bipe.account.password") == "chat.bipe.account.password")
        #expect(BipeLegacyShape.redact("@ana:example.test") == "@…:example.test")
        #expect(BipeLegacyShape.redact("ana@example.test") == "text(16)")
        #expect(BipeLegacyShape.redact("Ana Souza") == "text(9)")
        #expect(BipeLegacyShape.redact("9f86d081884c7d659a2feaa0c55ad015") == "text(32)")
    }

    @Test func describeNamesTheKind() {
        #expect(BipeLegacyShape.describe(Data([0, 159, 146, 150])) as? String == "binary(4)")
        #expect(BipeLegacyShape.describe(Data("fixture".utf8)) as? String == "text(7)")
        let json = BipeLegacyShape.describe(Data(#"{"flag":true,"n":3}"#.utf8)) as? [String: Any]
        let shape = json?["json"] as? [String: Any]
        #expect(shape?["flag"] as? String == "bool")
        #expect(shape?["n"] as? String == "number")
    }
}

/// Each test owns one fake and uses it synchronously.
private final class LegacyFakeKeychain: CredentialKeychain, @unchecked Sendable {
    private var items: [[String: Any]] = []
    var mutations = 0

    func seed(group: String, service: String, account: String, value: String) {
        items.append([kSecClass as String: kSecClassGenericPassword, kSecAttrAccessGroup as String: group,
                      kSecAttrService as String: service, kSecAttrAccount as String: account,
                      kSecValueData as String: Data(value.utf8), kSecAttrSynchronizable as String: false])
    }

    func copyMatching(_ query: [String: Any]) -> (OSStatus, AnyObject?) {
        let found = items.filter { item in
            [kSecAttrService, kSecAttrAccount, kSecAttrAccessGroup].allSatisfy { key in
                guard let expected = query[key as String] as? String else { return true }
                return item[key as String] as? String == expected
            }
        }
        return found.isEmpty ? (errSecItemNotFound, nil) : (errSecSuccess, found as AnyObject)
    }

    func add(_ attributes: [String: Any]) -> OSStatus {
        mutations += 1
        return errSecSuccess
    }

    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        mutations += 1
        return errSecSuccess
    }

    func delete(_ query: [String: Any]) -> OSStatus {
        mutations += 1
        return errSecSuccess
    }
}

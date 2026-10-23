// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Security
import Testing

struct InviteStoreTests {
    @Test
    func legacyLinkIsReadButOnlyOwnSnapshotChanges() throws {
        let fake = InviteKeychain()
        let legacy = try JSONEncoder().encode(["invite": "https://example.invalid/synthetic"])
        fake.records["legacy|chat.bipe.invites|@fixture:example.invalid"] = legacy
        let store = makeStore(fake)
        #expect(store.links().count == 1)
        #expect(store.keepLinks(forPendingInviteIDs: []))
        #expect(store.links().isEmpty)
        #expect(fake.records["legacy|chat.bipe.invites|@fixture:example.invalid"] == legacy)
        #expect(fake.mutationGroups == ["own", "own"])
    }

    @Test
    func failedClaimRetainsNoteAndNeverTouchesLegacy() throws {
        let fake = InviteKeychain()
        let data = try JSONEncoder().encode(["invite": "Synthetic first message"])
        fake.records["legacy|chat.bipe.invites.notes|@fixture:example.invalid"] = data
        let store = makeStore(fake)
        fake.failWrite = true
        #expect(store.takeNote(forInviteID: "invite") == nil)
        #expect(store.notes()["invite"] == "Synthetic first message")
        fake.failWrite = false
        #expect(store.takeNote(forInviteID: "invite") == "Synthetic first message")
        #expect(store.takeNote(forInviteID: "invite") == nil)
        #expect(fake.records["legacy|chat.bipe.invites.notes|@fixture:example.invalid"] == data)
        #expect(fake.mutationGroups.allSatisfy { $0 == "own" })
    }

    @Test
    func missingGroupAndUnreadableDataPreventMutations() throws {
        let fake = InviteKeychain()
        let invalid = makeStore(fake, group: "$(AppIdentifierPrefix)unresolved")
        #expect(!invalid.saveLink(try #require(URL(string: "https://example.invalid/synthetic")), forInviteID: "invite"))
        #expect(fake.mutationGroups.isEmpty)
        fake.records["own|chat.bipe.invites.notes|@fixture:example.invalid"] = Data("unreadable".utf8)
        #expect(!makeStore(fake).saveNote("Synthetic", forInviteID: "invite"))
        #expect(fake.mutationGroups.isEmpty)
    }

    private func makeStore(_ keychain: InviteKeychain, group: String = "own") -> BipeInviteStore {
        BipeInviteStore(userID: "@fixture:example.invalid", defaults: UserDefaults(suiteName: UUID().uuidString)!,
                        ownedAccessGroup: group, legacyReadGroups: ["legacy"], keychain: keychain)
    }
}

private final class InviteKeychain: CredentialKeychain, @unchecked Sendable {
    var records: [String: Data] = [:]
    var mutationGroups: [String] = []
    var failWrite = false

    private func key(_ query: [String: Any]) -> String {
        [kSecAttrAccessGroup, kSecAttrService, kSecAttrAccount].map { query[$0 as String] as? String ?? "UNSCOPED" }.joined(separator: "|")
    }

    func copyMatching(_ query: [String: Any]) -> (OSStatus, AnyObject?) {
        guard let data = records[key(query)] else { return (errSecItemNotFound, nil) }
        return (errSecSuccess, data as NSData)
    }
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        mutationGroups.append(query[kSecAttrAccessGroup as String] as? String ?? "UNSCOPED")
        if failWrite { return errSecInteractionNotAllowed }
        guard records[key(query)] != nil else { return errSecItemNotFound }
        records[key(query)] = attributes[kSecValueData as String] as? Data
        return errSecSuccess
    }
    func add(_ attributes: [String: Any]) -> OSStatus {
        mutationGroups.append(attributes[kSecAttrAccessGroup as String] as? String ?? "UNSCOPED")
        if failWrite { return errSecInteractionNotAllowed }
        records[key(attributes)] = attributes[kSecValueData as String] as? Data
        return errSecSuccess
    }
    func delete(_ query: [String: Any]) -> OSStatus {
        Issue.record("The invite store must not delete a keychain record")
        return errSecParam
    }
}

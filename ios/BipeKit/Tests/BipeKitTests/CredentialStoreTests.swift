// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Security
import Testing

/// Synthetic keychain records only. No test calls a system keychain API or reads a real credential.
struct CredentialStoreTests {
    private let user = "@fixture:example.test"
    private let other = "@other:example.test"
    private let own = "test.own"
    private let legacy = "test.legacy"
    private let password = "chat.bipe.account.password"
    private let token = "chat.bipe.account.recovery-token"

    private func store(_ fake: FakeCredentialKeychain, group: String? = "test.own") -> BipeCredentialStore {
        BipeCredentialStore(ownedAccessGroup: group, legacyReadGroups: [legacy], keychain: fake)
    }

    @Test func ownValueWinsOverNewerLegacyValue() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: own, service: password, account: user, value: "own fixture", at: 1)
        fake.seed(group: legacy, service: password, account: user, value: "legacy fixture", at: 9)
        fake.seed(group: legacy, service: password, account: other, value: "other fixture", at: 2)
        let subject = store(fake)
        #expect(subject.password(for: user)?.secret == "own fixture")
        let discovered = subject.passwordsWithCreationDates()
        #expect(discovered.count == 2)
        #expect(discovered.first { $0.item.userID == user }?.item.secret == "own fixture")
        #expect(discovered.first { $0.item.userID == user }?.created == Date(timeIntervalSince1970: 1))
        #expect(subject.password(for: other)?.secret == "other fixture")
    }

    @Test func replacementUpdatesOnlyOwnItemsAndKeepsProtection() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: own, service: password, account: user, value: "old fixture")
        fake.seed(group: legacy, service: password, account: user, value: "legacy fixture")
        fake.seed(group: own, service: password, account: other, value: "other fixture")
        let saved = store(fake).save(BipeCredentials(userId: user, password: "new fixture", recoveryToken: "token fixture"))
        #expect(saved)
        #expect(fake.value(own, password, user) == "new fixture")
        #expect(fake.value(legacy, password, user) == "legacy fixture")
        #expect(fake.value(own, password, other) == "other fixture")
        #expect(fake.mutations.allSatisfy { $0[kSecAttrAccessGroup as String] as? String == own })
        #expect(fake.deletes.isEmpty)
        let passwordItem = fake.record(own, password, user)
        #expect(passwordItem?[kSecAttrSynchronizable as String] as? Bool == false)
        #expect(passwordItem?[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        let tokenItem = fake.record(own, token, user)
        #expect(tokenItem?[kSecAttrSynchronizable as String] as? Bool == true)
        #expect(tokenItem?[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlock as String)
    }

    @Test func failedAddDoesNotDeleteLegacyOrClaimSaveSucceeded() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: legacy, service: password, account: user, value: "same fixture")
        fake.addStatus = errSecNotAvailable
        #expect(!store(fake).save(BipeCredentials(userId: user, password: "same fixture", recoveryToken: "token fixture")))
        #expect(fake.value(legacy, password, user) == "same fixture")
        #expect(fake.value(own, password, user) == nil)
        #expect(fake.deletes.isEmpty)
    }

    @Test func failedUpdateKeepsPreviousOwnValue() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: own, service: password, account: user, value: "old fixture")
        fake.updateStatus = errSecAuthFailed
        #expect(!store(fake).save(BipeCredentials(userId: user, password: "new fixture", recoveryToken: "token fixture")))
        #expect(fake.value(own, password, user) == "old fixture")
        #expect(fake.deletes.isEmpty)
    }

    @Test func singleAndAllAccountDeletesStayInOwnGroup() {
        let fake = FakeCredentialKeychain()
        for group in [own, legacy] {
            for id in [user, other] { fake.seed(group: group, service: password, account: id, value: "fixture") }
            fake.seed(group: group, service: token, account: user, value: "token fixture")
        }
        let subject = store(fake)
        subject.deletePassword(for: user)
        #expect(fake.value(own, password, user) == nil)
        #expect(fake.value(own, password, other) == "fixture")
        #expect(fake.value(legacy, password, user) == "fixture")
        subject.deleteAllPasswords()
        subject.deleteRecoveryToken(for: user)
        #expect(fake.value(own, password, other) == nil)
        #expect(fake.value(legacy, password, other) == "fixture")
        #expect(fake.value(legacy, token, user) == "token fixture")
        #expect(fake.deletes.allSatisfy { $0[kSecAttrAccessGroup as String] as? String == own })
    }

    @Test func pendingOperationsNeverReadOrMutateLegacyItems() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: legacy, service: "chat.bipe.account.pending-signup", account: "pending", value: "legacy fixture")
        fake.seed(group: legacy, service: "chat.bipe.account.pending-recovery", account: "pending", value: "legacy fixture")
        let subject = store(fake)
        #expect(subject.pendingSignup() == nil)
        let pending = subject.pendingSignup(pin: "12345678")
        #expect(subject.pendingSignup() == pending)
        let recovery = BipeRecoveryStarted(recoveryId: "fixture", claimSecret: "synthetic", purpose: .access, readyAt: Date(timeIntervalSince1970: 1))
        subject.savePendingRecovery(recovery)
        #expect(subject.pendingRecovery() == recovery)
        subject.deletePendingSignup()
        subject.deletePendingRecovery()
        #expect(fake.value(legacy, "chat.bipe.account.pending-signup", "pending") == "legacy fixture")
        #expect(fake.value(legacy, "chat.bipe.account.pending-recovery", "pending") == "legacy fixture")
        #expect(fake.mutations.allSatisfy { $0[kSecAttrAccessGroup as String] as? String == own })
    }

    @Test func signOutAndInvalidTokenCannotReappearThroughLegacyFallback() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: legacy, service: password, account: user, value: "legacy fixture")
        fake.seed(group: legacy, service: token, account: user, value: "legacy token")
        let subject = store(fake)
        subject.deletePassword(for: user)
        subject.deleteRecoveryToken(for: user)
        #expect(subject.password(for: user) == nil)
        #expect(subject.passwordsWithCreationDates().isEmpty)
        #expect(subject.recoveryToken(for: user) == nil)
        #expect(fake.value(legacy, password, user) == "legacy fixture")
        #expect(fake.value(legacy, token, user) == "legacy token")
        // The own markers survive a new store instance, while an explicit successful save restores own discovery.
        #expect(store(fake).password(for: user) == nil)
        #expect(subject.save(BipeCredentials(userId: user, password: "new fixture", recoveryToken: "new token")))
        #expect(subject.password(for: user)?.secret == "new fixture")
        #expect(subject.recoveryToken(for: user)?.secret == "new token")
    }

    @Test func missingOrUnexpandedOwnGroupCannotMutateAnything() {
        for group in [nil, "", "$(AppIdentifierPrefix)chat.bipe.app.next"] as [String?] {
            let fake = FakeCredentialKeychain()
            fake.seed(group: legacy, service: password, account: user, value: "legacy fixture")
            let subject = store(fake, group: group)
            #expect(!subject.save(BipeCredentials(userId: user, password: "fixture", recoveryToken: "token fixture")))
            subject.deletePassword(for: user)
            subject.deleteAllPasswords()
            subject.deletePendingSignup()
            subject.deletePendingRecovery()
            subject.deleteRecoveryToken(for: user)
            #expect(fake.mutations.isEmpty)
            #expect(subject.password(for: user)?.secret == "legacy fixture")
        }
    }

    @Test func recoveryDiscoveryDeduplicatesAndExcludesSignedInAccounts() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: own, service: token, account: user, value: "own fixture", at: 1)
        fake.seed(group: legacy, service: token, account: user, value: "legacy fixture", at: 9)
        fake.seed(group: legacy, service: token, account: other, value: "other fixture", at: 2)
        #expect(store(fake).recoveryToken(excluding: [other])?.secret == "own fixture")
        #expect(store(fake).recoveryToken(excluding: [user])?.userID == other)
    }
}

/// The app-group move before the App Store transfer (bipe-chat/bipe#531). Synthetic records only.
struct KeychainMoveTests {
    private let user = "@fixture:example.test"
    private let other = "@other:example.test"
    private let appGroup = "group.test"
    private let team = "TEAM.test"
    private let fork = "FORK.test"
    private let password = "chat.bipe.account.password"
    private let token = "chat.bipe.account.recovery-token"
    private let excluded = "chat.bipe.account.password.excluded"

    private func move(_ fake: FakeCredentialKeychain) -> BipeKeychainMove {
        BipeKeychainMove(target: appGroup, sources: [team, fork], services: BipeCredentialStore.keychainServices, keychain: fake)
    }

    @Test func copiesEveryItemKeepsSourcesAndProtection() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: team, service: password, account: user, value: "team fixture")
        fake.seed(group: team, service: token, account: user, value: "token fixture", synchronizable: true)
        fake.seed(group: fork, service: password, account: other, value: "fork fixture")
        let result = move(fake).run()
        #expect(result == BipeKeychainMove.Result(done: true, copied: 3))
        #expect(fake.value(appGroup, password, user) == "team fixture")
        #expect(fake.value(appGroup, password, other) == "fork fixture")
        #expect(fake.record(appGroup, password, user)?[kSecAttrSynchronizable as String] as? Bool == false)
        #expect(fake.record(appGroup, password, user)?[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        #expect(fake.record(appGroup, token, user)?[kSecAttrSynchronizable as String] as? Bool == true)
        #expect(fake.value(team, password, user) == "team fixture")
        #expect(fake.value(fork, password, other) == "fork fixture")
        #expect(fake.deletes.isEmpty)
        #expect(fake.mutations.allSatisfy { $0[kSecAttrAccessGroup as String] as? String == appGroup })
        // The marker makes the next launch a no-op.
        #expect(move(fake).isDone)
        #expect(move(fake).run() == BipeKeychainMove.Result(done: true, copied: 0))
    }

    @Test func neverReplacesATargetItemAndTheTeamGroupWinsOverTheFork() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: appGroup, service: password, account: user, value: "newer fixture")
        fake.seed(group: team, service: password, account: user, value: "older fixture")
        fake.seed(group: team, service: password, account: other, value: "team fixture")
        fake.seed(group: fork, service: password, account: other, value: "fork fixture")
        #expect(move(fake).run().done)
        #expect(fake.value(appGroup, password, user) == "newer fixture")
        #expect(fake.value(appGroup, password, other) == "team fixture")
    }

    @Test func aSignedOutAccountStaysSignedOutAfterTheMove() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: team, service: excluded, account: user, value: "")
        fake.seed(group: fork, service: password, account: user, value: "fork fixture")
        #expect(move(fake).run().done)
        let store = BipeCredentialStore(ownedAccessGroup: appGroup, legacyReadGroups: [team, fork], keychain: fake)
        #expect(store.password(for: user) == nil)
        #expect(store.passwordsWithCreationDates().isEmpty)
    }

    @Test func aLegacyMarkerHidesOnlyLegacyCopies() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: team, service: excluded, account: user, value: "")
        fake.seed(group: fork, service: password, account: user, value: "fork fixture")
        let store = BipeCredentialStore(ownedAccessGroup: appGroup, legacyReadGroups: [team, fork], keychain: fake)
        #expect(store.password(for: user) == nil)
        #expect(store.save(BipeCredentials(userId: user, password: "new fixture", recoveryToken: "token fixture")))
        #expect(store.password(for: user)?.secret == "new fixture")
    }

    @Test func aFailedCopyIsNotDoneAndLeavesNoMarker() {
        let fake = FakeCredentialKeychain()
        fake.seed(group: team, service: password, account: user, value: "team fixture")
        fake.addStatus = errSecNotAvailable
        let result = move(fake).run()
        #expect(!result.done)
        #expect(!move(fake).isDone)
        #expect(fake.value(team, password, user) == "team fixture")
        #expect(fake.deletes.isEmpty)
    }

    @Test func noTargetGroupMovesNothing() {
        for group in [nil, "", "$(BIPE_APP_GROUP)"] as [String?] {
            let fake = FakeCredentialKeychain()
            fake.seed(group: team, service: password, account: user, value: "team fixture")
            let result = BipeKeychainMove(target: group, sources: [team], services: BipeCredentialStore.keychainServices, keychain: fake).run()
            #expect(!result.done)
            #expect(fake.mutations.isEmpty)
        }
    }
}

/// Each test owns one fake and uses it synchronously. The Sendable conformance matches the injected boundary.
private final class FakeCredentialKeychain: CredentialKeychain, @unchecked Sendable {
    private var items: [[String: Any]] = []
    var mutations: [[String: Any]] = []
    var deletes: [[String: Any]] = []
    var addStatus: OSStatus = errSecSuccess
    var updateStatus: OSStatus? = nil

    func seed(group: String, service: String, account: String, value: String, at: TimeInterval = 1, synchronizable: Bool = false) {
        items.append([kSecClass as String: kSecClassGenericPassword, kSecAttrAccessGroup as String: group,
                      kSecAttrService as String: service, kSecAttrAccount as String: account,
                      kSecValueData as String: Data(value.utf8), kSecAttrSynchronizable as String: synchronizable,
                      kSecAttrCreationDate as String: Date(timeIntervalSince1970: at),
                      kSecAttrModificationDate as String: Date(timeIntervalSince1970: at)])
    }

    func record(_ group: String, _ service: String, _ account: String) -> [String: Any]? {
        items.first { $0[kSecAttrAccessGroup as String] as? String == group && $0[kSecAttrService as String] as? String == service && $0[kSecAttrAccount as String] as? String == account }
    }
    func value(_ group: String, _ service: String, _ account: String) -> String? {
        record(group, service, account).flatMap { $0[kSecValueData as String] as? Data }.flatMap { String(data: $0, encoding: .utf8) }
    }
    private func matches(_ item: [String: Any], _ query: [String: Any]) -> Bool {
        for key in [kSecClass, kSecAttrService, kSecAttrAccount, kSecAttrAccessGroup] {
            if let expected = query[key as String] as? String, item[key as String] as? String != expected { return false }
        }
        return true
    }
    func copyMatching(_ query: [String: Any]) -> (OSStatus, AnyObject?) {
        let found = items.filter { matches($0, query) }
        return found.isEmpty ? (errSecItemNotFound, nil) : (errSecSuccess, found as AnyObject)
    }
    func add(_ attributes: [String: Any]) -> OSStatus {
        mutations.append(attributes)
        guard addStatus == errSecSuccess else { return addStatus }
        if items.contains(where: { matches($0, attributes) }) { return errSecDuplicateItem }
        items.append(attributes)
        return errSecSuccess
    }
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        mutations.append(query)
        if let updateStatus { return updateStatus }
        let indices = items.indices.filter { matches(items[$0], query) }
        guard !indices.isEmpty else { return errSecItemNotFound }
        for index in indices { items[index].merge(attributes) { _, new in new } }
        return errSecSuccess
    }
    func delete(_ query: [String: Any]) -> OSStatus {
        mutations.append(query)
        deletes.append(query)
        items.removeAll { matches($0, query) }
        return errSecSuccess
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import XCTest
@testable import BipeMac

private final class MemoryKeychain: MacKeychain, @unchecked Sendable {
    var values: [String: Data] = [:]
    var refuseWrites = false
    func read(_ account: String) throws -> Data? { values[account] }
    func write(_ data: Data, account: String) throws {
        if refuseWrites { throw MacVaultError.keychain }
        values[account] = data
    }
    func remove(_ account: String) throws { values.removeValue(forKey: account) }
}

final class MacSpikeTests: XCTestCase {
    private let session = Session(accessToken: "synthetic-token", refreshToken: "synthetic-refresh",
                                  userId: "@test:example.invalid", deviceId: "TESTDEVICE",
                                  homeserverUrl: "https://example.invalid", oauthData: nil, slidingSyncVersion: .native)

    func testSessionRestoresTheSameDeviceAndRefreshTokens() throws {
        let vault = MacSessionVault(keychain: MemoryKeychain())
        let folder = UUID().uuidString
        try vault.save(session, folder: folder)
        let saved = try XCTUnwrap(vault.load())
        XCTAssertEqual(saved.folder, folder)
        XCTAssertEqual(saved.session.deviceId, session.deviceId)
        XCTAssertEqual(saved.session.refreshToken, session.refreshToken)
        try vault.clear()
        XCTAssertNil(try vault.load())
    }

    func testMissingStoreKeyFailsClosedOnRestore() throws {
        let keychain = MemoryKeychain()
        let vault = MacSessionVault(keychain: keychain)
        XCTAssertThrowsError(try vault.storeKey(create: false))
        let key = try vault.storeKey(create: true)
        XCTAssertEqual(key.count, 32)
        XCTAssertEqual(try vault.storeKey(create: false), key)
        keychain.values["database-key"] = Data([1])
        XCTAssertThrowsError(try vault.storeKey(create: true))
    }

    func testFailedKeychainWriteDoesNotReplaceSavedSession() throws {
        let keychain = MemoryKeychain()
        let vault = MacSessionVault(keychain: keychain)
        let folder = UUID().uuidString
        try vault.save(session, folder: folder)
        keychain.refuseWrites = true
        XCTAssertThrowsError(try vault.save(session, folder: UUID().uuidString))
        XCTAssertEqual(try vault.load()?.folder, folder)
        XCTAssertThrowsError(try vault.save(session, folder: "../other-account"))
    }

    func testApprovalAndRevocationGateSessionDelegateWrites() throws {
        let vault = MacSessionVault(keychain: MemoryKeychain())
        let folder = UUID().uuidString
        let delegate = MacSessionDelegate(vault: vault, folder: folder, active: false, onFailure: {})
        delegate.saveSessionInKeychain(session: session)
        XCTAssertNil(try vault.load())
        try vault.save(session, folder: folder)
        delegate.activate()
        XCTAssertEqual(try delegate.retrieveSessionFromKeychain(userId: session.userId).deviceId, session.deviceId)
        XCTAssertThrowsError(try delegate.retrieveSessionFromKeychain(userId: "@other:example.invalid"))
        delegate.deactivate()
        try vault.clear()
        delegate.saveSessionInKeychain(session: session)
        XCTAssertNil(try vault.load(), "A delayed refresh must not resurrect a revoked device")
    }

    /// Loads the pinned native SDK and encrypted SQLite on the CI Mac without a server or real credentials.
    func testNativeMatrixSDKOpensEncryptedStoreWithoutNetwork() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let data = root.appendingPathComponent("data", isDirectory: true)
        let cache = root.appendingPathComponent("cache", isDirectory: true)
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        func openAndClose() async throws {
            let client = try await MacAccount.builder(data: data, cache: cache, key: Data(repeating: 7, count: 32))
                .homeserverUrl(url: "https://example.invalid").build()
            XCTAssertTrue(client.rooms().isEmpty)
            XCTAssertThrowsError(try client.session())
        }
        try await openAndClose()
        try await openAndClose()
        do {
            _ = try await MacAccount.builder(data: data, cache: cache, key: Data(repeating: 9, count: 32))
                .homeserverUrl(url: "https://example.invalid").build()
            XCTFail("An existing encrypted store must reject a different key")
        } catch {
            // Expected: the SDK cannot decrypt the existing store with another key.
        }
        XCTAssertFalse(ScamDetector.matchIDs(in: "urgente").isEmpty)
    }
}

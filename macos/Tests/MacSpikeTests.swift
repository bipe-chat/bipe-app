// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import AppKit
import CoreImage
import Foundation
import MatrixRustSDK
import SwiftUI
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
    @MainActor
    func testMacQRBitmapRoundTripsThroughThePhonePayloadDecoder() throws {
        let payload = Data("MATRIX".utf8) + Data([2, 1]) + Data((0..<32).map(UInt8.init))
            + Data([0, 31]) + Data("https://matrix.bipe.net.br/test".utf8)
        let bitmap = try XCTUnwrap(MacLinkView.qrImage(payload))
        let data = try XCTUnwrap(bitmap.tiffRepresentation)
        let image = try XCTUnwrap(CIImage(data: data))
        let detector = try XCTUnwrap(CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(),
                                              options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        let feature = try XCTUnwrap(detector.features(in: image).first as? CIQRCodeFeature)
        let descriptor = try XCTUnwrap(feature.symbolDescriptor)
        XCTAssertEqual(releasedPhoneBytes(descriptor.errorCorrectedPayload, version: descriptor.symbolVersion), payload)
    }

    /// Frozen compatibility reader from the released iPhone QR scanner. Intentionally accepts only bytes/ECI.
    private func releasedPhoneBytes(_ data: Data, version: Int) -> Data? {
        var position = 0
        func read(_ count: Int) -> Int? {
            guard position + count <= data.count * 8 else { return nil }
            var value = 0
            for _ in 0..<count { value = value << 1 | Int(data[position / 8] >> (7 - position % 8) & 1); position += 1 }
            return value
        }
        var result = Data()
        while let mode = read(4), mode != 0 {
            if mode == 4 {
                guard let count = read(version < 10 ? 8 : 16) else { return nil }
                for _ in 0..<count { guard let byte = read(8) else { return nil }; result.append(UInt8(byte)) }
            } else if mode == 7 {
                guard let first = read(8) else { return nil }
                if first & 0x80 != 0 { guard read(first & 0x40 != 0 ? 16 : 8) != nil else { return nil } }
            } else { return nil }
        }
        return result.isEmpty ? nil : result
    }

    func testAppleMixedModeRegressionFixtureIsRejectedByReleasedPhone() throws {
        let words = try XCTUnwrap(Data(base64Encoded: "IDPopo0tEMCAQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fAB9odHRwczovL21hdHJpeC5iaXBlLm5ldC5ici90ZXN0A7BHsEew="))
        XCTAssertNil(releasedPhoneBytes(words, version: 4))
    }

    @MainActor
    func testSharedColorsResolveForLightAndDarkAndIconIsBundled() throws {
        func resolve(_ name: NSAppearance.Name) throws -> NSColor {
            let appearance = try XCTUnwrap(NSAppearance(named: name))
            var color: NSColor?
            appearance.performAsCurrentDrawingAppearance {
                color = NSColor(BipeColor.inkSecondary).usingColorSpace(.sRGB)
            }
            return try XCTUnwrap(color)
        }
        let light = try resolve(.aqua)
        let dark = try resolve(.darkAqua)
        XCTAssertGreaterThan(dark.redComponent, light.redComponent)
        // A synthetic NSAppearance does not change NSWorkspace's actual Increase Contrast preference.
        // The shared generator checks all four palettes and their WCAG contrast; the provider reads the OS flag.
        XCTAssertNotNil(Bundle.main.url(forResource: "AppIcon", withExtension: "icns"))
    }

    private let session = Session(accessToken: "synthetic-token", refreshToken: "synthetic-refresh",
                                  userId: "@test:example.invalid", deviceId: "TESTDEVICE",
                                  homeserverUrl: "https://example.invalid", oauthData: nil, slidingSyncVersion: .native)

    func testSessionRestoresTheSameDeviceAndRefreshTokens() throws {
        let vault = MacSessionVault(keychain: MemoryKeychain())
        let folder = UUID().uuidString
        try vault.save(session, folder: folder)
        let saved = try XCTUnwrap(vault.load(userID: session.userId))
        XCTAssertEqual(saved.folder, folder)
        XCTAssertEqual(saved.session.deviceId, session.deviceId)
        XCTAssertEqual(saved.session.refreshToken, session.refreshToken)
        try vault.clear(userID: session.userId)
        XCTAssertNil(try vault.load(userID: session.userId))
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
        XCTAssertEqual(try vault.load(userID: session.userId)?.folder, folder)
        XCTAssertThrowsError(try vault.save(session, folder: "../other-account"))
    }

    func testApprovalAndRevocationGateSessionDelegateWrites() throws {
        let vault = MacSessionVault(keychain: MemoryKeychain())
        let folder = UUID().uuidString
        let delegate = MacSessionDelegate(vault: vault, folder: folder, active: false, onFailure: {})
        delegate.saveSessionInKeychain(session: session)
        XCTAssertNil(try vault.load(userID: session.userId))
        try vault.save(session, folder: folder)
        delegate.activate()
        XCTAssertEqual(try delegate.retrieveSessionFromKeychain(userId: session.userId).deviceId, session.deviceId)
        XCTAssertThrowsError(try delegate.retrieveSessionFromKeychain(userId: "@other:example.invalid"))
        delegate.deactivate()
        try vault.clear(userID: session.userId)
        delegate.saveSessionInKeychain(session: session)
        XCTAssertNil(try vault.load(userID: session.userId), "A delayed refresh must not resurrect a revoked device")
    }

    func testInstalledSingleAccountMigratesWithoutChangingItsDeviceFolderOrKey() throws {
        let keychain = MemoryKeychain()
        let record = MacSessionVault.Record(folder: UUID().uuidString, accessToken: session.accessToken,
                                           refreshToken: session.refreshToken, userId: session.userId, deviceId: session.deviceId,
                                           homeserverUrl: session.homeserverUrl, oauthData: "synthetic-oauth")
        keychain.values["current"] = try JSONEncoder().encode(record)
        keychain.values["database-key"] = Data(repeating: 7, count: 32)
        let vault = MacSessionVault(keychain: keychain)
        XCTAssertEqual(try vault.loadAll(), [record])
        XCTAssertEqual(try vault.load(userID: record.userId), record)
        XCTAssertEqual(try vault.storeKey(create: false), Data(repeating: 7, count: 32))
        XCTAssertNil(keychain.values["current"])
    }

    func testFailedMigrationKeepsTheInstalledLoginIntact() throws {
        let keychain = MemoryKeychain()
        let record = MacSessionVault.Record(folder: UUID().uuidString, accessToken: session.accessToken,
                                           refreshToken: session.refreshToken, userId: session.userId, deviceId: session.deviceId,
                                           homeserverUrl: session.homeserverUrl, oauthData: nil)
        let original = try JSONEncoder().encode(record)
        keychain.values["current"] = original
        keychain.refuseWrites = true
        let vault = MacSessionVault(keychain: keychain)
        XCTAssertThrowsError(try vault.loadAll())
        XCTAssertEqual(keychain.values["current"], original)
        XCTAssertNil(keychain.values["accounts"])
        keychain.refuseWrites = false
        XCTAssertEqual(try vault.loadAll(), [record])
    }

    func testRefreshAndRevocationAffectOnlyTheirOwnAccount() throws {
        let keychain = MemoryKeychain()
        let vault = MacSessionVault(keychain: keychain)
        let firstFolder = UUID().uuidString
        let other = Session(accessToken: "other-token", refreshToken: "other-refresh", userId: "@company:example.invalid",
                            deviceId: "OTHERDEVICE", homeserverUrl: session.homeserverUrl, oauthData: nil, slidingSyncVersion: .native)
        let otherFolder = UUID().uuidString
        try vault.save(session, folder: firstFolder)
        try vault.save(other, folder: otherFolder)
        let saver = MacSessionDelegate(vault: vault, folder: firstFolder, active: true, onFailure: {})
        let refreshed = Session(accessToken: "refreshed-token", refreshToken: "refreshed-refresh", userId: session.userId,
                                deviceId: session.deviceId, homeserverUrl: session.homeserverUrl, oauthData: nil, slidingSyncVersion: .native)
        saver.saveSessionInKeychain(session: refreshed)
        XCTAssertEqual(try vault.loadAll().map(\.userId), [session.userId, other.userId], "Refresh preserves oldest-first order")
        XCTAssertEqual(try vault.load(userID: other.userId)?.refreshToken, other.refreshToken)
        XCTAssertEqual(try vault.load(userID: session.userId)?.refreshToken, refreshed.refreshToken)
        saver.deactivate()
        try vault.clear(userID: session.userId)
        saver.saveSessionInKeychain(session: refreshed)
        XCTAssertEqual(try vault.loadAll().map(\.userId), [other.userId])
        XCTAssertEqual(try vault.load(userID: other.userId)?.folder, otherFolder)
    }

    func testRelinkingCannotOverwriteAnExistingDeviceOrCryptoFolder() throws {
        let vault = MacSessionVault(keychain: MemoryKeychain())
        let folder = UUID().uuidString
        try vault.save(session, folder: folder)
        XCTAssertThrowsError(try vault.save(session, folder: UUID().uuidString))
        XCTAssertEqual(try vault.load(userID: session.userId)?.folder, folder)
    }

    @MainActor
    func testCancelledDuplicateLinkLeavesRetryInsteadOfASpinner() throws {
        let vault = MacSessionVault(keychain: MemoryKeychain())
        let folder = UUID().uuidString
        try vault.save(session, folder: folder)
        let installed = try XCTUnwrap(vault.load(userID: session.userId))
        let slot = MacAccount(vault: vault)
        slot.checkCode = "42"

        // MacWorkspace discards a redundant QR device with cancelLink. Its task then sees
        // a different attempt and returns without running the ordinary failure cleanup.
        slot.cancelLink()
        guard case .failed = slot.phase else { return XCTFail("An abandoned slot must offer Retry") }
        XCTAssertTrue(slot.checkCode.isEmpty)
        XCTAssertEqual(try vault.loadAll(), [installed], "Cancellation preserves the installed device and store")

        slot.cancelLink()
        guard case .failed = slot.phase else { return XCTFail("Repeated cancellation must remain recoverable") }
        XCTAssertEqual(try vault.loadAll(), [installed])
    }

    func testEmptyAccountListNeverResurrectsALegacyRecord() throws {
        let keychain = MemoryKeychain()
        let vault = MacSessionVault(keychain: keychain)
        try vault.save(session, folder: UUID().uuidString)
        let record = try XCTUnwrap(vault.load(userID: session.userId))
        try vault.clear(userID: session.userId)
        keychain.values["current"] = try JSONEncoder().encode(record)
        XCTAssertTrue(try vault.loadAll().isEmpty)
    }

    func testPersonalAndCompanyNavigationUseTheSameDMRule() {
        XCTAssertEqual(MacDestination.visible(isCompany: false), [.chats, .groups, .you])
        XCTAssertEqual(MacDestination.visible(isCompany: true), [.chats, .work, .you])
        XCTAssertTrue(MacDestination.groups.includes(isDm: false, isCompany: false))
        XCTAssertFalse(MacDestination.chats.includes(isDm: false, isCompany: false))
        XCTAssertTrue(MacDestination.chats.includes(isDm: false, isCompany: true))
        XCTAssertTrue(MacDestination.chats.includes(isDm: true, isCompany: false))
    }

    func testReadReceiptPrivacyKeepsGroupsPublicAndDMsPrivateWhenDisabled() throws {
        let suite = "chat.bipe.mac.receipts-test.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(MacReceiptPolicy.type(isDm: true, defaults: defaults), .read)
        defaults.set(false, forKey: MacReceiptPolicy.key)
        XCTAssertEqual(MacReceiptPolicy.type(isDm: true, defaults: defaults), .readPrivate)
        XCTAssertEqual(MacReceiptPolicy.type(isDm: false, defaults: defaults), .read)
    }

    func testRestoreNeverRecreatesAMissingCryptoStore() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let folder = UUID().uuidString
        XCTAssertThrowsError(try MacAccount.paths(folder: folder, restoring: true, root: root))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
        _ = try MacAccount.paths(folder: folder, root: root)
        XCTAssertThrowsError(try MacAccount.paths(folder: folder, restoring: true, root: root))
    }

    /// Loads the pinned native SDK and encrypted SQLite on the CI Mac without a server or real credentials.
    func testNativeMatrixSDKOpensEncryptedStoreWithoutNetwork() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let folder = UUID().uuidString
        let paths = try MacAccount.paths(folder: folder, root: root)
        let (data, cache) = (paths.data, paths.cache)
        func openAndClose() async throws {
            let client = try await MacAccount.builder(data: data, cache: cache, key: Data(repeating: 7, count: 32))
                .homeserverUrl(url: "https://example.invalid").build()
            XCTAssertTrue(client.rooms().isEmpty)
            XCTAssertThrowsError(try client.session())
        }
        try await openAndClose()
        XCTAssertNoThrow(try MacAccount.paths(folder: folder, restoring: true, root: root))
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

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// A signed-in account: the SDK client and its sync service.
final class AccountSession {
    let client: Client
    let syncService: SyncService
    let userId: String

    init(client: Client, syncService: SyncService, userId: String) {
        self.client = client
        self.syncService = syncService
        self.userId = userId
    }

    /// Builds a client whose store lives in the app group, one folder per account
    /// (spec account-start notes: the switch from the fork can reuse it).
    static func makeClient(userId: String) async throws -> Client {
        let paths = try storePaths(userId: userId)
        return try await ClientBuilder()
            .serverNameOrHomeserverUrl(serverNameOrUrl: AppConfig.homeserver)
            .sessionPaths(dataPath: paths.data.path, cachePath: paths.cache.path)
            .slidingSyncVersionBuilder(versionBuilder: .discoverNative)
            .setSessionDelegate(sessionDelegate: SessionSaver())
            .build()
    }

    /// Deletes the store of an account. A new sign-in needs an empty store: the crypto store
    /// belongs to one device.
    static func deleteStore(userId: String) {
        guard let paths = try? storePaths(userId: userId) else { return }
        try? FileManager.default.removeItem(at: paths.data)
        try? FileManager.default.removeItem(at: paths.cache)
    }

    private static func storePaths(userId: String) throws -> (data: URL, cache: URL) {
        let fileManager = FileManager.default
        let root = fileManager.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup)
            ?? fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = Data(userId.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
        let data = root.appendingPathComponent("Sessions", isDirectory: true).appendingPathComponent(folder, isDirectory: true)
        let cache = root.appendingPathComponent("Library/Caches/Sessions", isDirectory: true).appendingPathComponent(folder, isDirectory: true)
        try fileManager.createDirectory(at: data, withIntermediateDirectories: true)
        try fileManager.createDirectory(at: cache, withIntermediateDirectories: true)
        return (data, cache)
    }
}

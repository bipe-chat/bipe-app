// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// A signed-in account: the SDK client and its sync service.
/// Also compiled into the notification service extension (project.yml): keep it free of app-only types.
final class AccountSession {
    let client: Client
    let syncService: SyncService
    let userId: String

    init(client: Client, syncService: SyncService, userId: String) {
        self.client = client
        self.syncService = syncService
        self.userId = userId
    }

    /// The process that opens the store. The app and the notification service extension share the store
    /// in the app group, so both take the SDK's cross-process lock (spec notification-service notes).
    enum Process: String {
        case app
        case notificationService
    }

    /// Signs in with the hidden password (MAS compatibility login, m.login.password), saves the session and starts the sync.
    static func signIn(userId: String, password: String) async throws -> AccountSession {
        deleteStore(userId: userId)
        let client = try await makeClient(userId: userId)
        try await client.login(username: userId, password: password, initialDeviceName: AppConfig.deviceName, deviceId: nil)
        SessionStore.save(try client.session())
        return try await start(client)
    }

    /// Restores a saved session and starts the sync.
    static func restore(_ saved: Session) async throws -> AccountSession {
        let client = try await makeClient(userId: saved.userId)
        try await client.restoreSession(session: saved)
        return try await start(client)
    }

    private static func start(_ client: Client) async throws -> AccountSession {
        let syncService = try await client.syncService().finish()
        await syncService.start()
        return AccountSession(client: client, syncService: syncService, userId: try client.userId())
    }

    /// The access token for bipe-api calls (spec handle-pick notes). Read each time: the SDK may refresh it.
    func accessToken() -> String? {
        try? client.session().accessToken
    }

    /// Signs out on the server and removes this account's session, store and hidden password from this phone.
    func signOut() async {
        try? await client.logout()
        await syncService.stop()
        SessionStore.delete()
        BipeCredentialStore().deletePassword(for: userId)
        Self.deleteStore(userId: userId)
    }

    /// Builds a client whose store lives in the app group, one folder per account
    /// (spec account-start notes: the switch from the fork can reuse it).
    static func makeClient(userId: String, process: Process = .app) async throws -> Client {
        let paths = try storePaths(userId: userId)
        var builder = ClientBuilder()
            .serverNameOrHomeserverUrl(serverNameOrUrl: AppConfig.homeserver)
            .sessionPaths(dataPath: paths.data.path, cachePath: paths.cache.path)
            .slidingSyncVersionBuilder(versionBuilder: .discoverNative)
            .setSessionDelegate(sessionDelegate: SessionSaver())
            .crossProcessLockConfig(crossProcessLockConfig: .multiProcess(holderName: process.rawValue))
        if process == .notificationService {
            builder = builder.systemIsMemoryConstrained()
        }
        return try await builder.build()
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

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Passes ClientDelegate.didReceiveAuthError to the main actor (spec soft-logout).
private final class AuthErrorRelay: ClientDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var _handler: ((Bool) -> Void)?

    var handler: ((Bool) -> Void)? {
        get { lock.withLock { _handler } }
        set { lock.withLock { _handler = newValue } }
    }

    func didReceiveAuthError(isSoftLogout: Bool) {
        guard let handler else { return }
        DispatchQueue.main.async { handler(isSoftLogout) }
    }

    func onBackgroundTaskErrorReport(taskName: String, error: BackgroundTaskFailureReason) {}
}

/// A signed-in account: the SDK client and its sync service.
/// Also compiled into the notification service extension (project.yml): keep it free of app-only types.
final class AccountSession {
    let client: Client
    let syncService: SyncService
    let userId: String
    let roomSubscriptions: BipeRoomSubscriptions
    /// The Matrix OpenID token for the core bipe-api routes (bipe-chat/bipe#646). Memory only.
    let openID: BipeOpenIDTokens
    /// Spec soft-logout: the SDK reports a refused token here. Set by account-start.
    var onAuthError: ((_ isSoftLogout: Bool) -> Void)? {
        get { authErrors.handler }
        set { authErrors.handler = newValue }
    }
    private let authErrors = AuthErrorRelay()
    private var authErrorsHandle: TaskHandle?

    init(client: Client, syncService: SyncService, userId: String) {
        self.client = client
        self.syncService = syncService
        self.userId = userId
        openID = BipeOpenIDTokens(homeserverURL: client.homeserver(), userID: userId) {
            try? client.session().accessToken
        }
        roomSubscriptions = BipeRoomSubscriptions { ids in
            try await syncService.roomListService().subscribeToRooms(roomIds: ids)
        }
    }

    /// The process that opens the store. The app and the notification service extension share the store
    /// in the app group, so both take the SDK's cross-process lock (spec notification-service notes).
    enum Process: String {
        case app
        case notificationService
    }

    /// Signs in with the hidden password (MAS compatibility login, m.login.password), saves the session and starts the sync.
    /// Every step has a time limit (AccountStep).
    static func signIn(userId: String, password: String) async throws -> AccountSession {
        deleteStore(userId: userId)
        let client = try await AccountStep.run("build client") { try await makeClient(userId: userId) }
        try await AccountStep.run("login") {
            try await client.login(username: userId, password: password, initialDeviceName: AppConfig.deviceName, deviceId: nil)
        }
        SessionStore.save(try client.session())
        return try await start(client)
    }

    /// Restores a saved session and starts the sync. Every step has a time limit (AccountStep).
    static func restore(_ saved: Session) async throws -> AccountSession {
        // The keychain outlives a deleted app, the SDK store does not: a session without its store cannot restore.
        guard hasStore(userId: saved.userId) else { throw AccountStep.Failure.noStore }
        let client = try await AccountStep.run("build client") {
            try await makeClient(userId: saved.userId, homeserverURL: saved.homeserverUrl)
        }
        try await AccountStep.run("restore session") { try await client.restoreSession(session: saved) }
        return try await start(client)
    }

    private static func start(_ client: Client) async throws -> AccountSession {
        let syncService = try await AccountStep.run("sync service") {
            // The SDK starts network tasks in the background and reconnects after offline failures.
            // Neither an initial sync response nor a reachability probe is a local-readiness gate.
            let syncService = try await client.syncService().withOfflineMode().finish()
            await syncService.start()
            return syncService
        }
        let session = AccountSession(client: client, syncService: syncService, userId: try client.userId())
        session.authErrorsHandle = try? client.setDelegate(delegate: session.authErrors)
        return session
    }

    /// Spec soft-logout authError: signs in again with the hidden password on the same store and device ID, so keys
    /// and history stay. The caller stops this session first.
    static func signInAgain(after expired: AccountSession, password: String) async throws -> AccountSession {
        let deviceId = try expired.client.deviceId()
        let userId = expired.userId
        let client = try await AccountStep.run("build client") { try await makeClient(userId: userId) }
        try await AccountStep.run("login") {
            try await client.login(username: userId, password: password, initialDeviceName: AppConfig.deviceName, deviceId: deviceId)
        }
        SessionStore.save(try client.session())
        return try await start(client)
    }

    /// Stops the sync and the room subscriptions and keeps everything on this phone (spec soft-logout).
    func pause() async {
        await roomSubscriptions.close()
        await syncService.stop()
    }

    /// true when the server refused the hidden password (M_FORBIDDEN), not a network or local failure.
    static func isRefused(_ error: Error) -> Bool {
        guard let error = error as? ClientError, case .MatrixApi(_, let code, _, _) = error else { return false }
        return code == "M_FORBIDDEN"
    }

    /// true when the account's SDK store folder exists and holds files.
    static func hasStore(userId: String) -> Bool {
        let data = storeRoot().appendingPathComponent("Sessions", isDirectory: true)
            .appendingPathComponent(folderName(userId: userId), isDirectory: true)
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: data.path)) ?? []
        return !contents.isEmpty
    }

    /// The access token for bipe-api calls (spec handle-pick notes). Read each time: the SDK may refresh it.
    func accessToken() -> String? {
        try? client.session().accessToken
    }

    /// Signs out on the server and removes this account's session, store and hidden password from this phone.
    func signOut(alreadyLoggedOut: Bool = false) async {
        await roomSubscriptions.close()
        if !alreadyLoggedOut { try? await client.logout() }
        await syncService.stop()
        SessionStore.delete(userId: userId)
        BipeCredentialStore().deletePassword(for: userId)
        Self.deleteStore(userId: userId)
    }

    /// Builds a client whose store lives in the app group, one folder per account
    /// (spec account-start notes: the switch from the fork can reuse it).
    static func makeClient(userId: String, process: Process = .app,
                           homeserverURL: String = AppConfig.homeserver) async throws -> Client {
        let paths = try storePaths(userId: userId)
        return try await builder(paths: paths, process: process).homeserverUrl(url: homeserverURL).build()
    }

    /// The settings every client of the app shares; the caller names the server.
    static func builder(paths: (data: URL, cache: URL), process: Process = .app) -> ClientBuilder {
        var builder = ClientBuilder()
            .sessionPaths(dataPath: paths.data.path, cachePath: paths.cache.path)
            // Bipe uses native sliding sync. Discovery requests /versions during build, making even
            // a saved local session wait for internet. Restore also retains the saved sync version.
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .setSessionDelegate(sessionDelegate: SessionSaver())
            .crossProcessLockConfig(crossProcessLockConfig: .multiProcess(holderName: process.rawValue))
            // Spec encryption-status: a new account gets cross-signing and key backup with no screen.
            .autoEnableCrossSigning(autoEnableCrossSigning: true)
            .autoEnableBackups(autoEnableBackups: true)
            // Security rule 13: a server-added device receives no keys without its owner's signature.
            .roomKeyRecipientStrategy(strategy: .identityBasedStrategy)
            // Keep pre-trust-metadata history readable; current senders must be cross-signed.
            .decryptionSettings(decryptionSettings: DecryptionSettings(senderDeviceTrustRequirement: .crossSignedOrLegacy))
            // Old messages decrypt from the key backup once this phone has the backup key (recovery-key-enter).
            .backupDownloadStrategy(backupDownloadStrategy: .afterDecryptionFailure)
        if process == .notificationService {
            builder = builder.systemIsMemoryConstrained()
        }
        return builder
    }

    /// Deletes the store of an account. A new sign-in needs an empty store: the crypto store
    /// belongs to one device.
    static func deleteStore(userId: String) {
        deleteStore(folder: folderName(userId: userId))
    }

    static func deleteStore(folder: String) {
        guard let paths = try? storePaths(folder: folder) else { return }
        try? FileManager.default.removeItem(at: paths.data)
        try? FileManager.default.removeItem(at: paths.cache)
    }

    private static func storeRoot() -> URL {
        let fileManager = FileManager.default
        return fileManager.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup)
            ?? fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static func folderName(userId: String) -> String {
        Data(userId.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
    }

    private static func storePaths(userId: String) throws -> (data: URL, cache: URL) {
        try storePaths(folder: folderName(userId: userId))
    }

    /// The data and cache folders of one store, made when missing.
    static func storePaths(folder: String) throws -> (data: URL, cache: URL) {
        let fileManager = FileManager.default
        let root = storeRoot()
        let data = root.appendingPathComponent("Sessions", isDirectory: true).appendingPathComponent(folder, isDirectory: true)
        let cache = root.appendingPathComponent("Library/Caches/Sessions", isDirectory: true).appendingPathComponent(folder, isDirectory: true)
        try fileManager.createDirectory(at: data, withIntermediateDirectories: true)
        try fileManager.createDirectory(at: cache, withIntermediateDirectories: true)
        return (data, cache)
    }
}

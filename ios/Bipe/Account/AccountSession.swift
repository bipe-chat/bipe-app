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

/// Switches a room's send queue on again after the SDK switched it off (bipe-chat/bipe#947). matrix-rust-sdk turns a
/// room's queue off after a failed send and never turns it on by itself, so messages stayed on "Sending…".
private final class SendQueueRelay: SendQueueRoomErrorListener, @unchecked Sendable {
    weak var client: Client?

    func onError(roomId: String, error: ClientError) {
        AccountStep.log.error("send queue: off after an error, on again in 15 s: \(String(describing: error), privacy: .private)")
        Task { [weak client = self.client] in
            try? await Task.sleep(for: .seconds(15))
            try? client?.getRoom(roomId: roomId)?.enableSendQueue(enable: true)
        }
    }
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
    private let sendQueueErrors = SendQueueRelay()
    private var sendQueueHandle: TaskHandle?

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
    /// When this phone still holds the account's store, the login keeps that store and its device ID, so the
    /// cross-signing keys and the room keys stay (bipe-chat/bipe#909). Only a store without a known device ID goes.
    static func signIn(userId: String, password: String) async throws -> AccountSession {
        // The old saved session goes first, so the notification service never opens the new store with the old device ID.
        SessionStore.delete(userId: userId)
        let keptDeviceId = hasStore(userId: userId) ? storedDeviceId(userId: userId) : nil
        if keptDeviceId == nil { deleteStore(userId: userId) }
        let client = try await AccountStep.run("build client") { try await makeClient(userId: userId) }
        try await AccountStep.run("login") {
            try await client.login(username: userId, password: password, initialDeviceName: AppConfig.deviceName,
                                   deviceId: keptDeviceId)
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
        if let deviceId = try? client.deviceId() { saveDeviceId(deviceId, userId: session.userId) }
        session.authErrorsHandle = try? client.setDelegate(delegate: session.authErrors)
        session.sendQueueErrors.client = client
        session.sendQueueHandle = client.subscribeToSendQueueStatus(listener: session.sendQueueErrors)
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

    /// Switches every room's send queue on again, for a queue the SDK left off after a failed send (bipe-chat/bipe#947).
    func resumeSending() async {
        await client.enableAllSendQueues(enable: true)
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
    static func builder(paths: (data: URL, cache: URL), process: Process = .app) throws -> ClientBuilder {
        let key = try storeKey(paths: paths)
        var builder = ClientBuilder()
            .sqliteStore(config: SqliteStoreBuilder(dataPath: paths.data.path, cachePath: paths.cache.path).key(key: key))
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
        } else if let index = searchIndexPath(data: paths.data) {
            // Spec chat-search notes (bipe#671): the SDK's local message index, main app only.
            builder = builder.withSearchIndexStore(path: index.path, password: key?.base64EncodedString())
        }
        return builder
    }

    /// The key of the store in `paths` (bipe-chat/bipe#913). A new store gets the install's key and a marker file, so
    /// it never opens without the key. A store from before the key stays plain until its next fresh sign-in, with no
    /// migration (MZ, 3 Nov 2026). Throws when a keyed store's key cannot be read: the store stays, closed.
    private static func storeKey(paths: (data: URL, cache: URL)) throws -> Data? {
        let fileManager = FileManager.default
        let marker = paths.data.appendingPathComponent("bipe-store-keyed", isDirectory: false)
        if fileManager.fileExists(atPath: marker.path) {
            guard let key = try BipeCredentialStore().storeKey() else { throw BipeCredentialStore.StoreKeyError.unavailable }
            return key
        }
        guard try fileManager.contentsOfDirectory(atPath: paths.data.path).isEmpty else { return nil }
        let key = try BipeCredentialStore().storeKeyMakingOne()
        // A cache left without its data folder is plain, and a keyed client cannot open it.
        try? fileManager.removeItem(at: paths.cache)
        try Data().write(to: marker, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return key
    }

    /// The device ID the account's store belongs to, kept in the store folder so it goes with the store
    /// (bipe-chat/bipe#909). The saved session can be gone (soft logout, PIN recovery) while the store stays.
    static func storedDeviceId(userId: String) -> String? {
        guard let paths = try? storePaths(userId: userId),
              let text = try? String(contentsOf: deviceIdFile(data: paths.data), encoding: .utf8) else { return nil }
        let deviceId = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return deviceId.isEmpty ? nil : deviceId
    }

    private static func saveDeviceId(_ deviceId: String, userId: String) {
        guard let paths = try? storePaths(userId: userId) else { return }
        try? Data(deviceId.utf8).write(to: deviceIdFile(data: paths.data), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func deviceIdFile(data: URL) -> URL {
        data.appendingPathComponent("bipe-device-id", isDirectory: false)
    }

    /// Deletes the store of an account. A new sign-in without a known device ID needs an empty store: the crypto
    /// store belongs to one device.
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

    /// The folder of the local message search index, inside the account's data folder, made when missing.
    private static func searchIndexPath(data: URL) -> URL? {
        let index = data.appendingPathComponent("SearchIndex", isDirectory: true)
        return (try? FileManager.default.createDirectory(at: index, withIntermediateDirectories: true)) != nil ? index : nil
    }

    /// The data and cache folders of one store, made when missing. Both `Sessions` folders stay out of iCloud and
    /// computer backups: the store holds the Olm account, the room keys and the private cross-signing keys
    /// (bipe-chat/bipe#913, ADR 0073).
    static func storePaths(folder: String) throws -> (data: URL, cache: URL) {
        let fileManager = FileManager.default
        let root = storeRoot()
        let sessions = root.appendingPathComponent("Sessions", isDirectory: true)
        let cachedSessions = root.appendingPathComponent("Library/Caches/Sessions", isDirectory: true)
        let data = sessions.appendingPathComponent(folder, isDirectory: true)
        let cache = cachedSessions.appendingPathComponent(folder, isDirectory: true)
        try fileManager.createDirectory(at: data, withIntermediateDirectories: true)
        try fileManager.createDirectory(at: cache, withIntermediateDirectories: true)
        excludeFromBackup(sessions)
        excludeFromBackup(cachedSessions)
        return (data, cache)
    }

    private static func excludeFromBackup(_ folder: URL) {
        var folder = folder
        guard (try? folder.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup != true else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
    }
}

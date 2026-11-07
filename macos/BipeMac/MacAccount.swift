// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation
import MatrixRustSDK

final class MacQRProgress: GeneratedQrLoginProgressListener {
    let updates: AsyncStream<GeneratedQrLoginProgress>
    private let continuation: AsyncStream<GeneratedQrLoginProgress>.Continuation
    init() { (updates, continuation) = AsyncStream.makeStream() }
    func onUpdate(state: GeneratedQrLoginProgress) { continuation.yield(state) }
    func finish() { continuation.finish() }
}

final class MacAuthDelegate: ClientDelegate, @unchecked Sendable {
    let onRefused: @Sendable () -> Void
    init(onRefused: @escaping @Sendable () -> Void) { self.onRefused = onRefused }
    func didReceiveAuthError(isSoftLogout: Bool) { onRefused() }
    func onBackgroundTaskErrorReport(taskName: String, error: BackgroundTaskFailureReason) {}
}

/// First Mac slice of link-computer and chat-list. The phone remains the authority for a new computer.
@MainActor
final class MacAccount: ObservableObject {
    enum Phase { case starting, qr, checkCode, approving, keys, signedIn, failed, revoked }
    @Published private(set) var phase: Phase = .starting
    @Published private(set) var qrBytes: Data?
    let roomList = MacRoomList()
    var roomRows: [MacRoomSummary] { roomList.rows }
    @Published var destination = MacDestination.chats
    @Published private(set) var avatarURL: String?
    private var listChanges: AnyCancellable?
    @Published private(set) var userId = ""
    @Published var selectedRooms: [MacDestination: String] = [:]
    @Published private(set) var displayName = BipeStrings.tabYou
    @Published private(set) var handle: String?
    @Published private(set) var isCompany = false
    @Published private(set) var storageFailed = false
    @Published var checkCode = ""

    private(set) var client: Client?
    private var sync: SyncService?
    let api = BipeAPIClient(baseURL: URL(string: "https://api.bipe.net.br")!)
    let drafts = MacDrafts()
    let media = MacMedia()
    private let vault: MacSessionVault
    private var savedUserID: String?
    private var openID: BipeOpenIDTokens?
    var onReady: (() -> Void)?
    var onAlreadyLinked: ((String) -> Void)?
    private var loginTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var authDelegate: MacAuthDelegate?
    private var sessionSaver: MacSessionDelegate?
    private var authHandle: TaskHandle?
    private var checkSender: CheckCodeSender?
    private var attempt = UUID()
    private var launched = false

    init(record: MacSessionVault.Record? = nil, vault: MacSessionVault = MacSessionVault(keychain: SystemMacKeychain())) {
        self.vault = vault
        listChanges = roomList.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        savedUserID = record?.userId
        if let record {
            userId = record.userId
            displayName = BipeAccountLabels.label(for: record.userId)
            handle = BipeHandleCache.handle(for: record.userId)
            isCompany = BipeAccountLabels.isCompany(record.userId)
        }
    }

    var business: BipeBusinessAPI {
        BipeBusinessAPI(api: api) { [weak self] in try? self?.client?.session().accessToken }
    }

    var hasUnread: Bool { roomRows.contains { $0.unread > 0 || $0.markedUnread } }

    func start() {
        guard !launched else { return }
        launched = true
        retry()
    }

    func retry() {
        media.reset()
        loginTask?.cancel()
        refreshTask?.cancel()
        roomList.reset()
        authHandle?.cancel()
        authHandle = nil
        sessionSaver?.deactivate()
        let oldSync = sync
        sync = nil
        client = nil
        attempt = UUID()
        let current = attempt
        phase = .starting
        qrBytes = nil
        checkSender = nil
        checkCode = ""
        loginTask = Task {
            do {
                await oldSync?.stop()
                try Task.checkCancellation()
                if let savedUserID {
                    guard let saved = try vault.load(userID: savedUserID) else { throw MacVaultError.invalidRecord }
                    let delegate = sessionDelegate(folder: saved.folder, active: true)
                    let client = try await makeClient(folder: saved.folder, restoring: true, delegate: delegate)
                    try Task.checkCancellation()
                    try await client.restoreSession(session: saved.session)
                    try Task.checkCancellation()
                    try await connected(client, attempt: current)
                } else {
                    try await link(attempt: current)
                }
            } catch {
                guard attempt == current, !Task.isCancelled else { return }
                refreshTask?.cancel()
                qrBytes = nil
                checkSender = nil
                phase = .failed
            }
        }
    }

    private func sessionDelegate(folder: String, active: Bool) -> MacSessionDelegate {
        let delegate = MacSessionDelegate(vault: vault, folder: folder, active: active) { [weak self] in
            Task { @MainActor in self?.storageFailed = true }
        }
        sessionSaver = delegate
        return delegate
    }

    private func link(attempt current: UUID) async throws {
        let folder = UUID().uuidString
        var committed = false
        defer {
            if !committed, let paths = try? Self.paths(folder: folder) {
                try? FileManager.default.removeItem(at: paths.data.deletingLastPathComponent())
            }
        }
        let delegate = sessionDelegate(folder: folder, active: false)
        let client = try await makeClient(folder: folder, restoring: false, delegate: delegate)
        let progress = MacQRProgress()
        let reader = Task {
            for await state in progress.updates {
                guard attempt == current, !Task.isCancelled else { return }
                switch state {
                case .starting: break
                case .qrReady(let code):
                    qrBytes = code.toBytes()
                    phase = .qr
                    refreshTask = Task {
                        try? await Task.sleep(for: .seconds(25))
                        guard !Task.isCancelled, attempt == current, phase == .qr else { return }
                        retry()
                    }
                case .qrScanned(let sender):
                    refreshTask?.cancel()
                    qrBytes = nil
                    checkSender = sender
                    phase = .checkCode
                case .waitingForToken:
                    checkSender = nil
                    phase = .approving
                case .syncingSecrets, .done: phase = .keys
                }
            }
        }
        defer {
            progress.finish()
            reader.cancel()
            if attempt == current { refreshTask?.cancel() }
        }
        let oauth = OAuthConfiguration(clientName: "Bipe (Mac)", redirectUri: "chat.bipe.app.mac:/callback",
                                       clientUri: "https://bipe.chat", logoUri: nil, tosUri: nil,
                                       policyUri: nil, staticRegistrations: [:])
        try await client.newLoginWithQrCodeHandler(oauthConfiguration: oauth).generate(progressListener: progress)
        progress.finish()
        await reader.value
        try Task.checkCancellation()
        guard attempt == current else { throw CancellationError() }
        do {
            try vault.save(client.session(), folder: folder)
        } catch MacVaultError.duplicateAccount {
            // End only the redundant new device, leaving the existing account and keys untouched.
            let duplicateUserID = try client.userId()
            try? await client.logout()
            onAlreadyLinked?(duplicateUserID)
            throw MacVaultError.duplicateAccount
        } catch {
            try? await client.logout()
            throw error
        }
        savedUserID = try client.userId()
        committed = true
        delegate.activate()
        try await connected(client, attempt: current)
    }

    func submitCheckCode() {
        guard phase == .checkCode, let code = UInt8(checkCode), code < 100, !checkCode.isEmpty,
              let sender = checkSender else { return }
        checkSender = nil
        phase = .approving
        let current = attempt
        Task {
            do { try await sender.send(code: code) } catch {
                guard attempt == current else { return }
                loginTask?.cancel()
                phase = .failed
            }
        }
    }

    private func makeClient(folder: String, restoring: Bool, delegate: MacSessionDelegate) async throws -> Client {
        let paths = try Self.paths(folder: folder, restoring: restoring)
        let key = try vault.storeKey(create: !restoring)
        return try await Self.builder(data: paths.data, cache: paths.cache, key: key)
            .setSessionDelegate(sessionDelegate: delegate)
            .userAgent(userAgent: "Bipe/Mac")
            .homeserverUrl(url: "https://matrix.bipe.net.br")
            .build()
    }

    /// Shared Matrix security policy: signed recipients, encrypted stores and local offline readiness.
    nonisolated static func builder(data: URL, cache: URL, key: Data) -> ClientBuilder {
        ClientBuilder()
            .sqliteStore(config: SqliteStoreBuilder(dataPath: data.path, cachePath: cache.path).key(key: key))
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .autoEnableCrossSigning(autoEnableCrossSigning: true)
            .autoEnableBackups(autoEnableBackups: true)
            .roomKeyRecipientStrategy(strategy: .identityBasedStrategy)
            .decryptionSettings(decryptionSettings: DecryptionSettings(senderDeviceTrustRequirement: .crossSignedOrLegacy))
            .backupDownloadStrategy(backupDownloadStrategy: .afterDecryptionFailure)
    }

    nonisolated static func paths(folder: String, restoring: Bool = false, root: URL? = nil) throws -> (data: URL, cache: URL) {
        guard UUID(uuidString: folder) != nil else { throw MacVaultError.invalidRecord }
        let manager = FileManager.default
        var root = root ?? manager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat.bipe.app.mac/Stores", isDirectory: true)
        let account = root.appendingPathComponent(folder, isDirectory: true)
        let data = account.appendingPathComponent("data", isDirectory: true)
        let cache = account.appendingPathComponent("cache", isDirectory: true)
        // Restoring tokens into a fresh store would replace this device's crypto keys under its old ID.
        if restoring {
            // matrix-sdk-sqlite 26.08.11 crypto_store.rs DATABASE_NAME; checked by the native SDK test.
            let database = data.appendingPathComponent("matrix-sdk-crypto.sqlite3")
            guard let size = try? database.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0 else {
                throw MacVaultError.missingStore
            }
        }
        for path in [root, account, data, cache] {
            try manager.createDirectory(at: path, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try root.setResourceValues(values)
        return (data, cache)
    }

    private func connected(_ client: Client, attempt current: UUID) async throws {
        guard attempt == current else { throw CancellationError() }
        self.client = client
        media.attach(client)
        userId = try client.userId()
        displayName = BipeAccountLabels.label(for: userId)
        handle = BipeHandleCache.handle(for: userId)
        isCompany = BipeAccountLabels.isCompany(userId)
        openID = BipeOpenIDTokens(homeserverURL: try client.session().homeserverUrl, userID: userId) { [weak self] in
            try? self?.client?.session().accessToken
        }
        let delegate = MacAuthDelegate { [weak self] in
            Task { @MainActor in
                guard self?.attempt == current else { return }
                await self?.revoked()
            }
        }
        authDelegate = delegate
        authHandle = try client.setDelegate(delegate: delegate)
        let sync = try await client.syncService().withOfflineMode().finish()
        try Task.checkCancellation()
        guard attempt == current else { return }
        self.sync = sync
        await sync.start()
        guard attempt == current, phase != .revoked else { return }
        phase = .signedIn
        onReady?()
        Task { await refreshProfile() }
        try await roomList.attach(client: client, service: sync.roomListService(), ownUserID: userId)
    }

    func loadMoreRooms() { roomList.loadMore() }

    func refreshProfile() async {
        guard let client, phase == .signedIn else { return }
        let current = attempt
        async let avatar = try? client.avatarUrl()
        if let name = try? await client.displayName(), attempt == current {
            BipeAccountLabels.remember(displayName: name, for: userId)
            displayName = BipeAccountLabels.label(for: userId)
        }
        if let type = try? await api.accountType(userID: userId), attempt == current {
            isCompany = type == .company
            BipeAccountLabels.rememberCompany(isCompany, for: userId)
        }
        if let openID, let record = try? await BipeAPIClient.withOpenIDRetry(openID, { (token: BipeOpenIDToken) async throws(BipeAPIError) -> BipeMyHandle in
            try await self.api.myHandle(openID: token)
        }), attempt == current {
            handle = record.handle
            BipeHandleCache.store(handle: record.handle, for: userId)
            displayName = BipeAccountLabels.label(for: userId)
        }
        let loadedAvatar = await avatar
        guard attempt == current else { return }
        avatarURL = loadedAvatar
    }

    /// Cancel a QR attempt without changing any other account's saved session.
    func cancelLink() {
        attempt = UUID()
        loginTask?.cancel()
        refreshTask?.cancel()
        qrBytes = nil
        checkSender = nil
        sessionSaver?.deactivate()
    }

    private func revoked() async {
        guard phase != .revoked else { return }
        phase = .revoked
        media.reset()
        attempt = UUID()
        loginTask?.cancel()
        refreshTask?.cancel()
        roomList.reset()
        authHandle?.cancel()
        authHandle = nil
        sessionSaver?.deactivate()
        await sync?.stop()
        sync = nil
        client = nil
        openID = nil
        do { try vault.clear(userID: userId); savedUserID = nil } catch { storageFailed = true }
    }
}

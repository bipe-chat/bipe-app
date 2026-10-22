// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Spec account-start (bipe-shared product/screens/account-start.json).
@MainActor
final class AccountStartModel: ObservableObject {
    enum Phase: Equatable {
        case opening
        case noAccount
        case failed
        case ready
    }

    @Published private(set) var phase: Phase = .opening
    @Published private(set) var userId: String?
    private(set) var session: AccountSession?

    /// Action start: restore the saved session, else sign in with the device credential, else noAccount.
    /// Pending sign-up and recovery are bipe-chat/bipe#554 and #556.
    func start() async {
        guard session == nil else { return }
        phase = .opening
        do {
            let client: Client
            if let saved = SessionStore.load() {
                userId = saved.userId
                client = try await AccountSession.makeClient(userId: saved.userId)
                try await client.restoreSession(session: saved)
            } else if let credential = DeviceCredential.newest() {
                userId = credential.userId
                AccountSession.deleteStore(userId: credential.userId)
                client = try await AccountSession.makeClient(userId: credential.userId)
                // MAS compatibility login (m.login.password).
                try await client.login(username: credential.userId,
                                       password: credential.password,
                                       initialDeviceName: AppConfig.deviceName,
                                       deviceId: nil)
                SessionStore.save(try client.session())
            } else {
                phase = .noAccount
                return
            }
            let syncService = try await client.syncService().finish()
            await syncService.start()
            session = AccountSession(client: client, syncService: syncService, userId: try client.userId())
            phase = .ready
        } catch {
            phase = .failed
        }
    }

    /// Action retry: from failed, run start again.
    func retry() async {
        guard phase == .failed else { return }
        await start()
    }

    /// Starts the sync again when the app comes back to the foreground.
    func resume() async {
        await session?.syncService.start()
    }
}

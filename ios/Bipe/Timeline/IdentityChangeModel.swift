// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Feeds the identity changes of a room to an AsyncStream, like the listeners in Encryption/EncryptionListeners.swift.
final class IdentityStatusStream: IdentityStatusChangeListener {
    let updates: AsyncStream<[IdentityStatusChange]>
    private let continuation: AsyncStream<[IdentityStatusChange]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func call(identityStatusChange: [IdentityStatusChange]) {
        continuation.yield(identityStatusChange)
    }
}

/// Spec identity-change (bipe#770): a member's crypto identity changed in this chat.
@MainActor
final class IdentityChangeModel: ObservableObject {
    /// Type IdentityWarning.
    struct Warning: Equatable, Identifiable {
        enum Kind: Equatable {
            case changed
            case verificationLost
        }

        let userId: String
        var name: String
        let kind: Kind

        var id: String { userId }

        var text: String {
            kind == .changed ? BipeStrings.identityChangeBody(name) : BipeStrings.identityChangeVerifiedBody(name)
        }
    }

    @Published private(set) var warnings: [Warning] = []
    @Published private(set) var busy = false
    @Published var showsHelp = false
    @Published var failed = false

    /// State blocksSending: a verified person is no longer verified.
    var blocksSending: Bool {
        warnings.contains { $0.kind == .verificationLost }
    }

    private let client: Client
    private let ownUserId: String
    private let roomId: String
    private var handle: TaskHandle?

    init(client: Client, ownUserId: String, roomId: String) {
        self.client = client
        self.ownUserId = ownUserId
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    /// Listens until the chat closes.
    func observe() async {
        guard handle == nil, let room = try? client.getRoom(roomId: roomId) else { return }
        let stream = IdentityStatusStream()
        guard let handle = try? await room.subscribeToIdentityStatusChanges(listener: stream) else { return }
        self.handle = handle
        for await changes in stream.updates {
            for change in changes where change.userId != ownUserId {
                warnings.removeAll { $0.userId == change.userId }
                let kind: Warning.Kind
                switch change.changedTo {
                case .pinViolation: kind = .changed
                case .verificationViolation: kind = .verificationLost
                case .verified, .pinned: continue
                }
                let name = BipeChatRowName.person(userID: change.userId, displayName: (try? await room.memberDisplayName(userId: change.userId)) ?? nil,
                                                  handle: nil)
                warnings.append(Warning(userId: change.userId, name: name, kind: kind))
            }
        }
    }

    /// Action acknowledge: pins the new identity, or withdraws the old verification. The SDK then removes the warning.
    func acknowledge() async {
        guard let first = warnings.first, !busy else { return }
        busy = true
        defer { busy = false }
        do {
            guard let identity = try await client.encryption().userIdentity(userId: first.userId, fallbackToServer: true) else {
                failed = true
                return
            }
            switch first.kind {
            case .changed: try await identity.pin()
            case .verificationLost: try await identity.withdrawVerification()
            }
        } catch {
            failed = true
        }
    }
}

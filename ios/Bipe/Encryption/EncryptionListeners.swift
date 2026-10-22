// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

// SDK listeners of the encryption state and of the session verification. Like Common/Listeners.swift,
// each one feeds an AsyncStream, so the models read the updates in order on the main actor.

final class VerificationStateStream: VerificationStateListener {
    let updates: AsyncStream<VerificationState>
    private let continuation: AsyncStream<VerificationState>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(status: VerificationState) {
        continuation.yield(status)
    }
}

final class RecoveryStateStream: RecoveryStateListener {
    let updates: AsyncStream<RecoveryState>
    private let continuation: AsyncStream<RecoveryState>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(status: RecoveryState) {
        continuation.yield(status)
    }
}

final class BackupStateStream: BackupStateListener {
    let updates: AsyncStream<BackupState>
    private let continuation: AsyncStream<BackupState>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(status: BackupState) {
        continuation.yield(status)
    }
}

/// The calls of SessionVerificationControllerDelegate, as values.
enum SessionVerificationEvent {
    case receivedRequest(SessionVerificationRequestDetails)
    case accepted
    case startedSas
    case receivedData(SessionVerificationData)
    case failed
    case cancelled
    case finished
}

final class SessionVerificationStream: SessionVerificationControllerDelegate {
    let events: AsyncStream<SessionVerificationEvent>
    private let continuation: AsyncStream<SessionVerificationEvent>.Continuation

    init() {
        (events, continuation) = AsyncStream.makeStream()
    }

    func didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
        continuation.yield(.receivedRequest(details))
    }

    func didAcceptVerificationRequest() {
        continuation.yield(.accepted)
    }

    func didStartSasVerification() {
        continuation.yield(.startedSas)
    }

    func didReceiveVerificationData(data: SessionVerificationData) {
        continuation.yield(.receivedData(data))
    }

    func didFail() {
        continuation.yield(.failed)
    }

    func didCancel() {
        continuation.yield(.cancelled)
    }

    func didFinish() {
        continuation.yield(.finished)
    }
}

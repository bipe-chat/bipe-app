// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
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

    private let session: AccountSession
    private let roomId: String
    private var handle: TaskHandle?

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    /// Listens until the chat closes.
    func observe() async {
        guard handle == nil, let room = try? session.client.getRoom(roomId: roomId) else { return }
        let stream = IdentityStatusStream()
        guard let handle = try? await room.subscribeToIdentityStatusChanges(listener: stream) else { return }
        self.handle = handle
        for await changes in stream.updates {
            for change in changes where change.userId != session.userId {
                warnings.removeAll { $0.userId == change.userId }
                let kind: Warning.Kind
                switch change.changedTo {
                case .pinViolation: kind = .changed
                case .verificationViolation: kind = .verificationLost
                case .verified, .pinned: continue
                }
                let name = (try? await room.memberDisplayName(userId: change.userId))?.nilIfBlank ?? change.userId
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
            guard let identity = try await session.client.encryption().userIdentity(userId: first.userId, fallbackToServer: true) else {
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

/// Spec identity-change: the bar above the composer.
struct IdentityChangeBar: View {
    @ObservedObject var model: IdentityChangeModel

    var body: some View {
        if let first = model.warnings.first {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    PhosphorIcon.warningCircle.image
                        .resizable()
                        .frame(width: 20, height: 20)
                        .foregroundStyle(BipeColor.trustCautionInk)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(first.text)
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkPrimary)
                        if model.warnings.count > 1 {
                            Text(BipeStrings.identityChangeMore(String(model.warnings.count - 1)))
                                .font(.footnote)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack(spacing: 16) {
                    Button(BipeStrings.identityChangeOk) { Task { await model.acknowledge() } }
                        .font(.subheadline.weight(.semibold))
                        .disabled(model.busy)
                    Button(BipeStrings.identityChangeLearnMore) { model.showsHelp = true }
                        .font(.subheadline)
                }
                .padding(.leading, 28)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustCautionWash))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(BipeColor.trustCautionLine, lineWidth: 1))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .alert(BipeStrings.identityChangeLearnMore, isPresented: $model.showsHelp) {
                Button(BipeStrings.actionContinue, role: .cancel) {}
            } message: {
                Text(BipeStrings.identityChangeHelp)
            }
            .alert(BipeStrings.errorGeneric, isPresented: $model.failed) {
                Button(BipeStrings.actionContinue, role: .cancel) {}
            }
        }
    }
}

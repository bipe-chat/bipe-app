// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec recovery-alert (bipe-shared product/screens/recovery-alert.json): on a signed-in phone, someone started a
/// recovery of this account, or the account signed in on a new device with the synced token.
@MainActor
final class RecoveryAlertModel: ObservableObject {
    enum Phase: Equatable {
        case hidden
        case shown
        case cancelling
        case cancelled
        case failed
    }

    @Published private(set) var phase: Phase = .hidden
    @Published private(set) var notice: BipeRecovery?

    private let api: BipeAPIClient
    private let session: AccountSession
    private var dismissedKey: String { "bipe.recovery.dismissed.\(session.userId)" }

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
    }

    /// Action check: on launch and on foreground.
    func check() async {
        guard phase == .hidden else { return }
        let status = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await self.api.account(accessToken: token)
        }
        guard let status else { return }
        let dismissed = Set(UserDefaults.standard.stringArray(forKey: dismissedKey) ?? [])
        let signedInAt = SessionSignInTime.value(for: session.userId)
        notice = status.recoveries.first { recovery in
            // A recovery older than this phone's sign-in is not news for this phone (it may be the one that brought it).
            recovery.alertMessage != nil && !dismissed.contains(recovery.id) && recovery.createdAt > signedInAt
        }
        if notice != nil {
            phase = .shown
        }
    }

    /// Action cancel: after the owner check.
    func cancel() async {
        guard let notice, notice.canBeCancelled else { return }
        guard await OwnerCheck.confirm(reason: BipeStrings.recoveryAlertTitle) else { return }
        phase = .cancelling
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await self.api.cancelRecovery(id: notice.id, accessToken: token)
            }
            phase = .cancelled
            dismiss(notice.id)
        } catch {
            phase = .failed
        }
    }

    /// Action keep: "It was me". The recovery goes on.
    func keep() {
        guard let notice else { return }
        dismiss(notice.id)
        phase = .hidden
    }

    func close() {
        phase = .hidden
    }

    private func dismiss(_ id: String) {
        var dismissed = UserDefaults.standard.stringArray(forKey: dismissedKey) ?? []
        dismissed.append(id)
        UserDefaults.standard.set(Array(dismissed.suffix(50)), forKey: dismissedKey)
    }
}

/// When this phone signed in to an account. Older recoveries are not alerts for it.
enum SessionSignInTime {
    static func value(for userId: String) -> Date {
        UserDefaults.standard.object(forKey: key(userId)) as? Date ?? .distantPast
    }

    static func record(for userId: String) {
        UserDefaults.standard.set(Date(), forKey: key(userId))
    }

    private static func key(_ userId: String) -> String {
        "bipe.signedInAt.\(userId)"
    }
}

struct RecoveryAlertView: View {
    @ObservedObject var model: RecoveryAlertModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.recoveryAlertTitle)
                .font(.largeTitle.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            if model.phase == .cancelled {
                Text(BipeStrings.recoveryAlertCancelled)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                PrimaryButton(title: BipeStrings.actionContinue) { model.close() }
            } else {
                Text(model.notice?.alertMessage ?? "")
                    .foregroundStyle(BipeColor.inkPrimary)
                if model.phase == .failed {
                    FieldFooter(text: BipeStrings.errorGeneric, isError: true)
                }
                Spacer()
                if model.notice?.canBeCancelled == true {
                    Button {
                        Task { await model.cancel() }
                    } label: {
                        Text(BipeStrings.recoveryAlertCancel)
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .foregroundStyle(BipeColor.trustDangerInk)
                            .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.trustDangerWash))
                    }
                    .disabled(model.phase == .cancelling)
                }
                TextButton(title: BipeStrings.recoveryAlertKeep) { model.keep() }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.recoveryAlert.rawValue)
    }
}

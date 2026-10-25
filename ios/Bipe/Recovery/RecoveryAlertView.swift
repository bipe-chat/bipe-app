// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec recovery-alert (bipe-shared product/screens/recovery-alert.json): on a signed-in phone, a full alert while
/// someone gets back into this account elsewhere, with a cancel; a notice after a sign-in with the synced token; and a
/// notice after an Apple or Google account was linked or unlinked (state linkNotice, ADR 0096).
@MainActor
final class RecoveryAlertModel: ObservableObject {
    enum Phase: Equatable {
        case shown
        case cancelling
        case cancelled
        case failed
    }

    @Published private(set) var notice: BipeRecovery?
    /// Shown when no recovery needs a notice.
    @Published private(set) var linkNotice: BipeLinkEvent?
    @Published private(set) var phase: Phase = .shown

    private let api: BipeAPIClient
    private let session: AccountSession
    private let defaults = UserDefaults.standard

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
    }

    /// Action check: on launch and on foreground, from GET /v1/me/account.
    func check() async {
        guard (notice == nil && linkNotice == nil) || phase == .cancelled else { return }
        let status = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await self.api.account(accessToken: token)
        }
        guard let status else { return }
        let since = alertsSince()
        let dismissed = dismissedIds()
        // A completed recovery older than this phone's session is this phone's own sign-in, or an old one.
        notice = status.recoveries
            .filter { $0.alertMessage != nil && !dismissed.contains($0.id) }
            .filter { $0.state == .waiting || $0.createdAt > since }
            .max { $0.createdAt < $1.createdAt }
        // A link made on this phone was dismissed here when it was made (RecoveryAlertModel.dismissOnThisPhone).
        linkNotice = notice != nil ? nil : (status.linkEvents ?? [])
            .filter { !dismissed.contains($0.id) && $0.at > since }
            .max { $0.at < $1.at }
        phase = .shown
    }

    /// Action cancel: for a waiting recovery, the owner check and then the cancel.
    func cancel() async {
        guard let notice, notice.canBeCancelled else { return }
        guard await OwnerCheck.owner(reason: BipeStrings.recoveryAlertTitle) else { return }
        phase = .cancelling
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await self.api.cancelRecovery(id: notice.id, accessToken: token)
            }
            dismiss(notice.id)
            phase = .cancelled
        } catch {
            phase = .failed
        }
    }

    /// Action keep: it was me. Dismisses the notice on this phone; the recovery goes on.
    func keep() {
        if let notice {
            dismiss(notice.id)
            self.notice = nil
        } else if let linkNotice {
            dismiss(linkNotice.id)
            self.linkNotice = nil
        }
    }

    /// A link or unlink made on this phone needs no notice here.
    static func dismissOnThisPhone(userId: String, eventIds: [String]) {
        let key = "bipe.recoveryAlert.dismissed.\(userId)"
        let defaults = UserDefaults.standard
        defaults.set(Array(Set(defaults.stringArray(forKey: key) ?? []).union(eventIds)), forKey: key)
    }

    /// Closes the cancelled message.
    func close() {
        notice = nil
        phase = .shown
    }

    private var dismissedKey: String { "bipe.recoveryAlert.dismissed.\(session.userId)" }
    private var sinceKey: String { "bipe.recoveryAlert.since.\(session.userId)" }

    private func dismissedIds() -> Set<String> {
        Set(defaults.stringArray(forKey: dismissedKey) ?? [])
    }

    private func dismiss(_ id: String) {
        defaults.set(Array(dismissedIds().union([id])), forKey: dismissedKey)
    }

    /// The first check of this account on this phone. Kept in UserDefaults, which a new install does not carry.
    private func alertsSince() -> Date {
        if let date = defaults.object(forKey: sinceKey) as? Date {
            return date
        }
        let now = Date()
        defaults.set(now, forKey: sinceKey)
        return now
    }
}

/// The alert covers the screen while a recovery waits: it is not a banner the person can miss.
struct RecoveryAlertView: View {
    @ObservedObject var model: RecoveryAlertModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 48, height: 48)
                .foregroundStyle(BipeColor.trustCautionInk)
                .accessibilityHidden(true)
            Text(BipeStrings.recoveryAlertTitle)
                .font(.largeTitle.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            switch model.phase {
            case .cancelled:
                Text(BipeStrings.recoveryAlertCancelled)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                filled(BipeStrings.actionContinue) { model.close() }
            default:
                if let message = model.notice?.alertMessage ?? model.linkNotice?.alertMessage {
                    Text(message)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                if model.phase == .failed {
                    Text(BipeStrings.errorNetwork)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Spacer()
                if model.notice?.canBeCancelled == true {
                    filled(BipeStrings.recoveryAlertCancel, enabled: model.phase != .cancelling) {
                        Task { await model.cancel() }
                    }
                    if model.phase == .cancelling {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
                Button { model.keep() } label: {
                    Text(BipeStrings.recoveryAlertKeep).frame(maxWidth: .infinity)
                }
                .controlSize(.large)
                .disabled(model.phase == .cancelling)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.recoveryAlert.rawValue)
    }

    private func filled(_ title: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!enabled)
    }
}

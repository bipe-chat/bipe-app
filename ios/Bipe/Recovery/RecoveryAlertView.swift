// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec recovery-alert (bipe-shared product/screens/recovery-alert.json): on a signed-in phone, a full alert while
/// someone gets back into this account elsewhere, with a cancel; a notice after a sign-in with the synced token; and a
/// notice after an Apple or Google account was linked or unlinked (state linkNotice, ADR 0096).
/// Spec deletion-alert (ADR 0140): the same alert while a deletion asked on another device waits, after any recovery
/// notice.
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
    /// A deletion asked on another device. Shown when no recovery needs a notice.
    @Published private(set) var deletion: BipeAccountDeletion?
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
        guard !isShown || phase == .cancelled else { return }
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
        DeletionDate.remember(status.deletion?.dueAt, for: session.userId)
        deletion = notice != nil ? nil : status.deletion.flatMap { $0.fromThisDevice || dismissed.contains($0.id) ? nil : $0 }
        // A link made on this phone was dismissed here when it was made (RecoveryAlertModel.dismissOnThisPhone).
        linkNotice = notice != nil || deletion != nil ? nil : (status.linkEvents ?? [])
            .filter { !dismissed.contains($0.id) && $0.at > since }
            .max { $0.at < $1.at }
        phase = .shown
    }

    /// Whether the alert covers the screen.
    var isShown: Bool { notice != nil || deletion != nil || linkNotice != nil }

    /// The deletion alert shows when no recovery alert does.
    var showsDeletion: Bool { notice == nil && deletion != nil }

    /// Action cancel: for a waiting recovery, the owner check and then the cancel.
    func cancel() async {
        if notice == nil, let deletion {
            await cancelDeletion(deletion)
            return
        }
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

    /// Spec deletion-alert, action cancel: the owner check, then POST /v1/me/deletions/{id}/cancel. A 404 means the
    /// request no longer waits. A phone with no passcode cancels without the check: a cancel only keeps the account.
    private func cancelDeletion(_ deletion: BipeAccountDeletion) async {
        if OwnerCheck.canCheckOwner {
            guard await OwnerCheck.owner(reason: BipeStrings.deletionCancelReason) else { return }
        }
        phase = .cancelling
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await self.api.cancelDeletion(id: deletion.id, accessToken: token)
            }
        } catch {
            guard case .server(.notFound, _, _) = error else {
                phase = .failed
                return
            }
        }
        DeletionDate.remember(nil, for: session.userId)
        dismiss(deletion.id)
        phase = .cancelled
    }

    /// Action keep: it was me. Dismisses the notice on this phone; the recovery or the deletion goes on.
    func keep() {
        if let notice {
            dismiss(notice.id)
            self.notice = nil
        } else if let deletion {
            dismiss(deletion.id)
            self.deletion = nil
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
        deletion = nil
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
            Text(model.showsDeletion ? BipeStrings.deletionAlertTitle : BipeStrings.recoveryAlertTitle)
                .font(.largeTitle.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            switch model.phase {
            case .cancelled:
                Text(model.showsDeletion ? BipeStrings.deletionCancelled : BipeStrings.recoveryAlertCancelled)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                filled(BipeStrings.actionContinue) { model.close() }
            default:
                if let message = model.notice?.alertMessage {
                    Text(message)
                        .foregroundStyle(BipeColor.inkPrimary)
                } else if let deletion = model.deletion {
                    Text(BipeStrings.deletionAlertBody(DeletionDate.text(deletion.dueAt)))
                        .foregroundStyle(BipeColor.inkPrimary)
                } else if let message = model.linkNotice?.alertMessage {
                    Text(message)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                if model.phase == .failed {
                    Text(BipeStrings.errorNetwork)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Spacer()
                if model.notice?.canBeCancelled == true || model.showsDeletion {
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
        .accessibilityIdentifier(model.showsDeletion ? BipeScreen.deletionAlert.rawValue : BipeScreen.recoveryAlert.rawValue)
    }

    private func filled(_ title: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bipeProminent)
        .controlSize(.large)
        .disabled(!enabled)
    }
}

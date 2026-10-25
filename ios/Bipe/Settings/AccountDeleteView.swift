// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec account-delete (ADR 0140): what happens, the switch to delete every message, the device owner check, the
/// senha, then POST /v1/me/deletion. The server deletes the account after BipeRegistries.deletionCooldownHours.
struct AccountDeleteView: View {
    enum Phase: Equatable {
        case explain
        case enterPin
        case requesting
        case requested(Date)
        case failed(String)
    }

    @ObservedObject var model: SettingsModel
    @State private var phase: Phase = .explain
    @State private var redactMessages = false
    @State private var pin = ""
    @State private var pinError: String?
    @Environment(\.dismiss) private var dismiss

    private var isCompany: Bool { model.account?.accountType == .company }
    private var isLastAccount: Bool { AccountList.order.count <= 1 }

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .explain:
                    explain
                case .enterPin, .requesting:
                    PinPadView(title: BipeStrings.deletionPinTitle,
                               error: pinError,
                               pin: pin,
                               isBusy: phase == .requesting,
                               onDigit: type,
                               onDelete: { if !pin.isEmpty { pin.removeLast() } })
                case .requested(let dueAt):
                    message(BipeStrings.deletionRequested(DeletionDate.text(dueAt)), icon: .check) {
                        dismiss()
                    }
                case .failed(let text):
                    message(text, icon: .warningCircle) { dismiss() }
                }
            }
            .navigationTitle(BipeStrings.deletionTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Action dismiss: nothing is sent before submitPin.
                    if phase == .explain || phase == .enterPin {
                        Button { dismiss() } label: { Text(BipeStrings.appCancel) }
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.accountDelete.rawValue)
        .interactiveDismissDisabled(phase == .requesting)
    }

    private var explain: some View {
        List {
            Section {
                Text(BipeStrings.deletionIntro(String(BipeRegistries.deletionCooldownHours)))
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            // One line per point: never joined into one sentence (linguagem.md section 6).
            Section {
                point(BipeStrings.deletionPointHandle(String(BipeRegistries.handleReleaseHoldDays)))
                point(BipeStrings.deletionPointGroups)
                point(BipeStrings.deletionPointData)
                point(BipeStrings.deletionPointKept(String(BipeRegistries.deletionVerificationKeepDays)))
                if isCompany {
                    point(BipeStrings.deletionPointCompany)
                }
                if isLastAccount {
                    point(BipeStrings.deletionPointLastAccount)
                }
            }
            Section {
                Toggle(isOn: $redactMessages) {
                    Text(BipeStrings.deletionRedact)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
            } footer: {
                Text(BipeStrings.deletionRedactFooter)
            }
            Section {
                Button(role: .destructive) {
                    Task { await confirmOwner() }
                } label: {
                    Text(BipeStrings.actionContinue)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func point(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(BipeColor.inkPrimary)
    }

    private func message(_ text: String, icon: PhosphorIcon, close: @escaping () -> Void) -> some View {
        VStack(spacing: 16) {
            PhosphorIconView(icon: icon, size: 48)
            Text(text)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button(action: close) {
                Text(BipeStrings.actionContinue).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }

    /// Action continue: the device owner check. A phone with no passcode cannot check its owner: fail closed.
    private func confirmOwner() async {
        guard OwnerCheck.canCheckOwner else {
            phase = .failed(BipeStrings.deletionError)
            return
        }
        guard await OwnerCheck.owner(reason: BipeStrings.deletionOwnerReason) else { return }
        pin = ""
        pinError = nil
        phase = .enterPin
    }

    private func type(_ digit: String) {
        guard phase == .enterPin, pin.count < BipeRegistries.pinLength else { return }
        if pin.isEmpty { pinError = nil }
        pin += digit
        if pin.count == BipeRegistries.pinLength {
            let entered = pin
            pin = ""
            Task { await submit(entered) }
        }
    }

    /// Action submitPin: POST /v1/me/deletion. The senha is never kept.
    private func submit(_ entered: String) async {
        phase = .requesting
        let api = model.api
        let redact = redactMessages
        do {
            let deletion = try await model.authorized { (token: String) async throws(BipeAPIError) -> BipeAccountDeletion in
                try await api.requestDeletion(pin: entered, redactMessages: redact, accessToken: token)
            }
            DeletionDate.remember(deletion.dueAt, for: model.session.userId)
            BipeHaptics.play(.success)
            phase = .requested(deletion.dueAt)
            await model.load()
        } catch {
            switch error {
            case .server(.pinWrong, _, _):
                BipeHaptics.play(.error)
                pinError = BipeStrings.pinWrong(String(error.pinTriesLeft ?? 0))
                phase = .enterPin
            case .server(.pinBlocked, _, _):
                phase = .failed(BipeStrings.pinBlockedBody(String(BipeRegistries.pinMaxWrongTries)))
            case .server(.pinNotSet, _, _):
                phase = .failed(BipeStrings.deletionPinNotSet)
            case .server(.deletionPending, _, _):
                // Another device asked first: the banner or the alert shows it.
                await model.load()
                dismiss()
            case .server(.rateLimited, _, _):
                phase = .failed(BipeStrings.errorRateLimited)
            default:
                phase = .failed(BipeStrings.deletionError)
            }
        }
    }
}

/// The banner at the top of Sua conta while a deletion this phone asked for waits (spec account-delete).
struct DeletionBanner: View {
    let deletion: BipeAccountDeletion
    let isCancelling: Bool
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.deletionBanner(DeletionDate.text(deletion.dueAt)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.trustDangerInk)
            Button(action: onCancel) {
                Text(BipeStrings.deletionCancel)
            }
            .buttonStyle(.bordered)
            .disabled(isCancelling)
            .accessibilityLabel(BipeStrings.a11yDeletionCancel)
        }
        .padding(.vertical, 4)
        .listRowBackground(BipeColor.trustDangerWash)
    }
}

/// Due times of waiting deletions, kept on this phone so the app knows the account is gone when its session ends
/// after the due time (spec account-delete notes: bipe_deletion_done).
enum DeletionDate {
    static func text(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }

    static func remember(_ dueAt: Date?, for userId: String) {
        if let dueAt {
            UserDefaults.standard.set(dueAt, forKey: key(userId))
        } else {
            UserDefaults.standard.removeObject(forKey: key(userId))
        }
    }

    /// True when this account had a deletion whose due time has passed.
    static func isPast(for userId: String) -> Bool {
        guard let dueAt = UserDefaults.standard.object(forKey: key(userId)) as? Date else { return false }
        return dueAt <= Date()
    }

    private static func key(_ userId: String) -> String { "bipe.deletion.due.\(userId)" }
}

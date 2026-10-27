// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec encryption-status: the quiet banner, the first row of the chat list (it scrolls with the chats).
struct HistoryBanner: View {
    @ObservedObject var model: EncryptionStatusModel

    var body: some View {
        HStack(spacing: 12) {
            Button(action: model.openSheet) {
                HStack(spacing: 10) {
                    PhosphorIcon.lockSimple.image
                        .resizable()
                        .frame(width: 18, height: 18)
                        .foregroundStyle(BipeColor.glyphSecondary)
                        .accessibilityHidden(true)
                    Text(BipeStrings.appHistoryBanner)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.leading)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button(action: model.dismiss) {
                Text(BipeStrings.fallbackSkip)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.accentInk)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(BipeColor.surfaceRaised)
        .accessibilityIdentifier(BipeScreen.encryptionStatus.rawValue)
    }
}

/// The sheets of the encryption flow: encryption-status, device-verification and recovery-key-enter.
struct EncryptionSheet: View {
    let route: EncryptionStatusModel.Route
    @ObservedObject var model: EncryptionStatusModel
    let session: AccountSession

    var body: some View {
        Group {
            switch route {
            case .history:
                HistorySheet(model: model)
            case .verification:
                DeviceVerificationView(model: model.verification) { model.route = nil }
            case .recoveryKey:
                RecoveryKeyEnterView(session: session,
                                     close: { model.route = nil },
                                     noKey: { model.route = .history })
            }
        }
        .tint(BipeColor.accentFill)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .presentationDragIndicator(.visible)
    }
}

/// Spec encryption-status: the sheet with the two ways to get old messages.
private struct HistorySheet: View {
    @ObservedObject var model: EncryptionStatusModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.appHistoryTitle)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.recoveryHistoryNote)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
            if model.hasOtherDevices {
                HistoryRow(icon: .devices, title: BipeStrings.appHistoryApprove, action: model.approveWithDevice)
            }
            if model.canUseRecoveryKey {
                HistoryRow(icon: .key, title: BipeStrings.appHistoryUseKey, action: model.useRecoveryKey)
            }
            if !model.hasOtherDevices && !model.canUseRecoveryKey {
                Text(BipeStrings.appHistoryNone)
                    .font(.body)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .presentationDetents([.medium])
        .accessibilityIdentifier(BipeScreen.encryptionStatus.rawValue)
    }
}

private struct HistoryRow: View {
    let icon: PhosphorIcon
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                icon.image
                    .resizable()
                    .frame(width: 24, height: 24)
                    .foregroundStyle(BipeColor.accentInk)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceRaised))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Opens the encryption-status sheet from a row of spec message-undecryptable (action openHelp).
private struct OpenHistoryHelpKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

extension EnvironmentValues {
    var openHistoryHelp: @MainActor () -> Void {
        get { self[OpenHistoryHelpKey.self] }
        set { self[OpenHistoryHelpKey.self] = newValue }
    }
}

/// Spec encryption-status verificationState is unverified: undecryptable rows offer openHelp for more causes
/// (message-undecryptable, bipe-chat/bipe#853).
private struct PhoneUnverifiedKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var phoneUnverified: Bool {
        get { self[PhoneUnverifiedKey.self] }
        set { self[PhoneUnverifiedKey.self] = newValue }
    }
}

/// The account's encryption-status model, for the oldMessages settings row. Nil outside AppTabsView.
private struct EncryptionStatusKey: EnvironmentKey {
    static let defaultValue: EncryptionStatusModel? = nil
}

extension EnvironmentValues {
    var encryptionStatus: EncryptionStatusModel? {
        get { self[EncryptionStatusKey.self] }
        set { self[EncryptionStatusKey.self] = newValue }
    }
}

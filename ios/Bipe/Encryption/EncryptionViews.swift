// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// encryption-status: the quiet banner above the chat list while this phone cannot read old messages.
struct HistoryBanner: View {
    @ObservedObject var model: EncryptionModel

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            PhosphorIcon.lockSimple.image
                .resizable()
                .frame(width: 18, height: 18)
                .foregroundStyle(BipeColor.glyphSecondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                Text(BipeStrings.appHistoryBanner)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkPrimary)
                HStack(spacing: 16) {
                    Button { model.openSheet() } label: {
                        Text(BipeStrings.appUtdHelp).font(.subheadline.weight(.semibold)).foregroundStyle(BipeColor.accentInk)
                    }
                    Button { model.dismiss() } label: {
                        Text(BipeStrings.fallbackSkip).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .accessibilityIdentifier(BipeScreen.encryptionStatus.rawValue)
    }
}

/// encryption-status: the sheet with the two ways to get the old messages.
struct HistorySheet: View {
    @ObservedObject var model: EncryptionModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.appHistoryTitle)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            if model.hasOtherDevices || model.canUseRecoveryKey {
                Text(BipeStrings.recoveryHistoryNote)
                    .foregroundStyle(BipeColor.inkSecondary)
                if model.hasOtherDevices {
                    PrimaryButton(title: BipeStrings.appHistoryApprove) { model.approveWithDevice() }
                }
                if model.canUseRecoveryKey {
                    TextButton(title: BipeStrings.appHistoryUseKey) { model.useRecoveryKey() }
                }
            } else {
                Text(BipeStrings.appHistoryNone)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            Spacer()
        }
        .padding(24)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .presentationDetents([.medium])
    }
}

/// recovery-key-save from the fallback prompt: a card above the chat list for an account without a key.
struct RecoveryKeyPromptCard: View {
    @ObservedObject var model: EncryptionModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.fallbackRecoveryKey)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.fallbackRecoveryKeyBody)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            HStack(spacing: 16) {
                Button { model.showsKeySave = true } label: {
                    Text(BipeStrings.appRecoveryKeyCreate)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
                Button { model.dismissKeyPrompt() } label: {
                    Text(BipeStrings.fallbackSkip).font(.subheadline.weight(.semibold)).foregroundStyle(BipeColor.accentInk)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.accentWash))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

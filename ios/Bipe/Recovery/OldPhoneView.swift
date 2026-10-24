// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Spec recovery-old-phone: the camera (and, in Debug builds and on the Simulator, the paste field) reads the code of
/// the old phone; then the check code, the wait for the approval and the history.
struct OldPhoneView: View {
    @StateObject private var model: OldPhoneModel

    init(onSignedIn: @escaping (AccountSession) -> Void) {
        _model = StateObject(wrappedValue: OldPhoneModel(onSignedIn: onSignedIn))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                content
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(BipeStrings.recoveryQr)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.recoveryOldPhone.rawValue)
        .task { await model.open() }
        .onDisappear { model.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .scanning, .failed, .cameraDenied:
            title(BipeStrings.appOldPhoneTitle)
            Text(BipeStrings.recoveryQrBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            Text(BipeStrings.appOldPhoneWhere)
                .font(.subheadline.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            if model.phase == .cameraDenied {
                Text(BipeStrings.cameraPermissionBody)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Text(BipeStrings.contactsOpenSettings)
                }
                .buttonStyle(.bordered)
            } else {
                QRScannerView { model.codeRead($0) }
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
            }
            // Rule 5, at the moment of the scan.
            Text(BipeStrings.recoveryQrNeverFromOthers)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            if OldPhoneModel.canPaste {
                pasteField
            }
            if let error = model.error {
                failure(error)
            }
        case .connecting:
            ProgressView()
            Text(BipeStrings.appOldPhoneConnecting)
                .foregroundStyle(BipeColor.inkSecondary)
        case .showCheckCode:
            title(BipeStrings.appOldPhoneCheckCodeTitle)
            // Large; never read aloud or sent anywhere.
            Text(verbatim: model.checkCode ?? "")
                .font(.system(size: 64, weight: .bold, design: .monospaced))
                .foregroundStyle(BipeColor.inkPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(BipeStrings.appOldPhoneCheckCodeBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
        case .waitingForApproval:
            title(BipeStrings.appOldPhoneWaitingTitle)
            ProgressView()
            Text(BipeStrings.appOldPhoneWaitingBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
        case .syncing:
            ProgressView()
            Text(BipeStrings.appOldPhoneSyncing)
                .foregroundStyle(BipeColor.inkSecondary)
        }
    }

    /// Debug builds and the Simulator only (bipe-chat/bipe#608).
    private var pasteField: some View {
        VStack(spacing: 8) {
            Text(BipeStrings.appOldPhonePaste)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            TextField(BipeStrings.appOldPhonePasteHint, text: $model.pasted, axis: .vertical)
                .lineLimit(1...4)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.footnote.monospaced())
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceRaised))
            Button {
                model.submitPasted()
            } label: {
                Text(BipeStrings.appOldPhonePasteSubmit).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func failure(_ error: OldPhoneModel.Failure) -> some View {
        VStack(spacing: 8) {
            Text(BipeStrings.appOldPhoneErrorTitle)
                .font(.headline)
                .foregroundStyle(BipeColor.trustDangerInk)
            Text(error.message)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button {
                model.scanAgain()
            } label: {
                Text(BipeStrings.appLinkComputerScanAgain)
            }
            .buttonStyle(.bordered)
        }
    }

    private func title(_ text: String) -> some View {
        Text(text)
            .font(.title2.bold())
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
    }
}

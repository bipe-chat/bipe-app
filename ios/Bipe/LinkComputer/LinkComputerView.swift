// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// The link-a-computer flow: link-computer-scan, then link-computer-warning, then link-computer-approve.
/// The only way into it is Settings > Linked devices > Link a computer (ADR 0116, security.md rule 5).
struct LinkComputerView: View {
    @StateObject private var model: LinkComputerModel
    @Environment(\.dismiss) private var dismiss

    init(settings: SettingsModel) {
        _model = StateObject(wrappedValue: LinkComputerModel(settings: settings))
    }

    var body: some View {
        NavigationStack {
            Group {
                switch model.step {
                case .scan: LinkComputerScanView(model: model)
                case .warning: LinkComputerWarningView(model: model)
                case .approve: LinkComputerApproveView(model: model)
                }
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        if model.step == .approve { model.close() } else { model.cancel() }
                    } label: {
                        Text(model.step == .approve ? BipeStrings.appClose : BipeStrings.appCancel)
                    }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .interactiveDismissDisabled()
        .task { await model.open() }
        .onChange(of: model.isClosed) { _, isClosed in
            if isClosed { dismiss() }
        }
    }
}

/// Spec link-computer-scan.
private struct LinkComputerScanView: View {
    @ObservedObject var model: LinkComputerModel

    var body: some View {
        VStack(spacing: 16) {
            switch model.support {
            case .checking:
                ProgressView().frame(maxHeight: .infinity)
            case .needsPin:
                // Creating the PIN opens the signup-pin steps (bipe-chat/bipe#554), which land with sign-up.
                message(BipeStrings.linkComputerNeedsPin(String(BipeRegistries.pinLength)))
            case .notSupported:
                message(BipeStrings.linkComputerNotSupported)
            case .supported:
                scanner
            }
        }
        .padding(16)
        .accessibilityIdentifier(BipeScreen.linkComputerScan.rawValue)
    }

    @ViewBuilder
    private var scanner: some View {
        Text(BipeStrings.appLinkComputerScanTitle)
            .font(.title2.bold())
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
        switch model.scanPhase {
        case .cameraDenied:
            message(BipeStrings.cameraPermissionBody)
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Text(BipeStrings.contactsOpenSettings)
            }
            .buttonStyle(.borderedProminent)
        case .scanning, .failed:
            Text(BipeStrings.appLinkComputerScanBody)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            QRScannerView { model.codeRead($0) }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16))
            // Rule 5, at the moment of the scan.
            Text(BipeStrings.recoveryQrNeverFromOthers)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            if let error = model.scanError {
                failure(error)
            }
        case .connecting:
            ProgressView()
            Text(BipeStrings.appLinkComputerConnecting)
                .foregroundStyle(BipeColor.inkSecondary)
        case .showCheckCode:
            Text(BipeStrings.appLinkComputerCheckCodeTitle)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            // Large; never read aloud or sent anywhere.
            Text(verbatim: model.checkCode ?? "")
                .font(.system(size: 64, weight: .bold, design: .monospaced))
                .foregroundStyle(BipeColor.inkPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(BipeStrings.appLinkComputerCheckCodeBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
        }
        Spacer(minLength: 0)
    }

    private func failure(_ error: LinkComputerModel.Failure) -> some View {
        VStack(spacing: 8) {
            Text(BipeStrings.linkComputerErrorTitle)
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

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
            .frame(maxHeight: .infinity)
    }
}

/// Spec link-computer-warning: full access, the scam warning and the cooldown.
private struct LinkComputerWarningView: View {
    @ObservedObject var model: LinkComputerModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 48, height: 48)
                .foregroundStyle(BipeColor.trustCautionInk)
                .accessibilityHidden(true)
            Text(BipeStrings.linkComputerWarningTitle)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.linkComputerWarningBody)
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.linkComputerWarningScam)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundStyle(BipeColor.inkPrimary)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustCautionWash))
            Spacer()
            Button {
                model.continueToApprove()
            } label: {
                Text(model.canContinue ? BipeStrings.linkComputerContinue : BipeStrings.pauseCountdown(String(model.secondsLeft)))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!model.canContinue)
        }
        .padding(16)
        .accessibilityIdentifier(BipeScreen.linkComputerWarning.rawValue)
        .onAppear {
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            model.warningAppeared()
        }
        .onDisappear { model.warningLeftFront() }
        // Leaving the app does not skip the wait: it starts again when the screen is back in front.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.warningAppeared() } else { model.warningLeftFront() }
        }
    }
}

/// Spec link-computer-approve: the owner check, then the MAS consent page in a private web view.
private struct LinkComputerApproveView: View {
    @ObservedObject var model: LinkComputerModel

    var body: some View {
        VStack(spacing: 12) {
            switch model.approvePhase {
            case .confirmIdentity:
                ProgressView()
                    .frame(maxHeight: .infinity)
                    .task { await model.confirmIdentity() }
            case .loading, .consent:
                if model.approvePhase == .consent {
                    placeHeader
                } else {
                    Text(BipeStrings.linkComputerLoading)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                if model.masHost != nil {
                    MASWebView(model: model)
                        // The user never sees the MAS login form.
                        .opacity(model.approvePhase == .consent ? 1 : 0)
                }
            case .finishing:
                ProgressView()
                Text(BipeStrings.appLinkComputerFinishing)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .frame(maxHeight: .infinity)
            case .done:
                PhosphorIcon.check.image
                    .resizable()
                    .frame(width: 48, height: 48)
                    .foregroundStyle(BipeColor.trustVerifiedInk)
                    .accessibilityHidden(true)
                Text(BipeStrings.linkComputerDone)
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                Spacer()
            case .failed:
                Text(BipeStrings.linkComputerErrorTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.trustDangerInk)
                Text(model.approveError?.message ?? BipeStrings.errorGeneric)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                Button {
                    model.retry()
                } label: {
                    Text(BipeStrings.appLinkComputerScanAgain)
                }
                .buttonStyle(.borderedProminent)
                Spacer()
            }
        }
        .padding(16)
        .accessibilityIdentifier(BipeScreen.linkComputerApprove.rawValue)
    }

    private var placeHeader: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(BipeStrings.linkComputerConsentHint)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
            if model.placeKnown {
                Text(model.place.map(BipeStrings.linkComputerPlace) ?? BipeStrings.linkComputerPlaceUnknown)
                    .font(.subheadline.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.linkComputerPlaceSource)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

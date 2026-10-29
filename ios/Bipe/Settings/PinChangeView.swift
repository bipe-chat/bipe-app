// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec pin-change: changes the account PIN after the owner check. No old PIN is needed (ADR 0057).
struct PinChangeView: View {
    enum Phase: Equatable {
        case confirmIdentity
        case entering
        case saving
        case failed(String)
        case done
    }

    @ObservedObject var model: SettingsModel
    @StateObject private var setup = PinSetupModel()
    @State private var phase: Phase = .confirmIdentity
    @State private var savedPIN: String?
    /// The Bipe PIN sheet of the owner check, for a phone with no biometrics (bipe-chat/bipe#785).
    @StateObject private var pinPrompt = AccountPinPrompt()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            switch phase {
            case .confirmIdentity:
                BipeColor.surfaceBase.ignoresSafeArea()
            case .entering, .saving:
                PinPadView(title: setup.title,
                           message: setup.message,
                           error: setup.problem,
                           pin: setup.pin,
                           isBusy: phase == .saving,
                           onDigit: { digit in setup.type(digit) { pin in Task { await save(pin) } } },
                           onDelete: setup.delete)
            case .failed(let message):
                VStack(spacing: 16) {
                    Text(message)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    // Action retry: the same PIN while the screen is open.
                    Button {
                        if let savedPIN { Task { await save(savedPIN) } }
                    } label: {
                        Text(BipeStrings.appRetry)
                    }
                    .buttonStyle(.bipeProminent)
                    Button {
                        dismiss()
                    } label: {
                        Text(BipeStrings.appCancel)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
            case .done:
                VStack(spacing: 16) {
                    PhosphorIconView(icon: .check, size: 48)
                    Text(BipeStrings.pinChanged)
                        .font(.title3.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .task {
                    try? await Task.sleep(for: .seconds(1.5))
                    dismiss()
                }
            }
        }
        .navigationTitle(BipeStrings.pinChangeTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.pinChange.rawValue)
        .task { await confirmIdentity() }
        .accountPinPrompt(pinPrompt)
    }

    /// Action confirmIdentity (rule 14 tier 3, bipe-chat/bipe#785): biometrics, or the current Bipe PIN checked by the
    /// server on a phone with no biometrics; never the phone's passcode. A phone with no screen lock cannot change
    /// the PIN; the screen closes (fail closed).
    private func confirmIdentity() async {
        guard phase == .confirmIdentity else { return }
        let api = model.api
        let model = model
        let check = await ApprovalCheck.run(reason: BipeStrings.pinChangeBiometricReason, pinTitle: BipeStrings.pinChangeTitle,
                                            prompt: pinPrompt) { (pin: String) async throws(BipeAPIError) in
            try await model.authorized { (token: String) async throws(BipeAPIError) in
                try await api.checkPIN(pin, accessToken: token)
            }
        }
        if check == .passed {
            phase = .entering
        } else {
            dismiss()
        }
    }

    /// Action save: PUT /v1/me/pin.
    private func save(_ pin: String) async {
        savedPIN = pin
        phase = .saving
        let api = model.api
        do {
            try await model.authorized { (token: String) async throws(BipeAPIError) in
                try await api.setPIN(pin, accessToken: token)
            }
            // One account on the phone and the lock on: the new account PIN is the app lock PIN too (ADR 0086).
            if AppLockModel.shared.store.isOn {
                AppLockModel.shared.store.setPIN(pin)
            }
            BipeHaptics.play(.success)
            phase = .done
        } catch {
            switch error {
            case .server(.pinWeak, let reason, _):
                setup.reset(problem: (reason.flatMap(BipePinProblem.init(rawValue:)) ?? .sequence).localizedMessage)
                phase = .entering
            case .server(.rateLimited, _, _):
                phase = .failed(BipeStrings.errorRateLimited)
            case .network:
                phase = .failed(BipeStrings.errorNetwork)
            default:
                phase = .failed(BipeStrings.errorGeneric)
            }
        }
    }
}

/// A request for the app lock PIN, shown as a sheet by the settings screen.
struct PinCheckRequest: Identifiable {
    let id = UUID()
    let title: String
    let onPass: () -> Void
}

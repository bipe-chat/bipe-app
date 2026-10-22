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
    @State private var pinCheck: PinCheckRequest?
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
                    .buttonStyle(.borderedProminent)
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
        .sheet(item: $pinCheck, onDismiss: {
            if phase == .confirmIdentity { dismiss() }
        }) { request in
            AppLockPinCheckView(title: request.title, onPass: request.onPass)
        }
    }

    /// Action confirmIdentity. A phone with no passcode uses the app lock PIN when the lock is on; else the screen
    /// closes (fail closed, as in data-export).
    private func confirmIdentity() async {
        guard phase == .confirmIdentity else { return }
        if OwnerCheck.canCheckOwner {
            if await OwnerCheck.owner(reason: BipeStrings.pinChangeBiometricReason) {
                phase = .entering
            } else {
                dismiss()
            }
        } else if AppLockModel.shared.store.isOn {
            pinCheck = PinCheckRequest(title: BipeStrings.pinChangeTitle) { phase = .entering }
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

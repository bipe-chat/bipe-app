// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec app-lock, action lock: the cover over Bipe while the lock is on. Off by default (ADR 0137).
@MainActor
final class AppLockModel: ObservableObject {
    static let shared = AppLockModel()

    let store = BipeAppLock()
    /// The lock screen is in front.
    @Published private(set) var isLocked: Bool
    /// The app is not active: a plain cover, so the app switcher shows no chats.
    @Published private(set) var isCovered = false
    @Published private(set) var pin = ""
    @Published private(set) var wrong = false
    private var asking = false

    private init() {
        isLocked = BipeAppLock().isOn
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .inactive:
            isCovered = store.isOn
        case .background:
            isCovered = store.isOn
            if store.isOn { isLocked = true }
        case .active:
            isCovered = false
            if isLocked { Task { await useBiometricsIfOn() } }
        @unknown default:
            break
        }
    }

    /// Action typeDigit, mode locked.
    func type(_ digit: String) {
        guard pin.count < BipeRegistries.pinLength else { return }
        wrong = false
        pin += digit
        guard pin.count == BipeRegistries.pinLength else { return }
        let typed = pin
        pin = ""
        if store.verifyPIN(typed) {
            BipeHaptics.play(.success)
            isLocked = false
        } else {
            BipeHaptics.play(.error)
            wrong = true
        }
    }

    func delete() {
        if !pin.isEmpty { pin.removeLast() }
    }

    var offersBiometrics: Bool {
        store.biometricUnlock && OwnerCheck.biometricKind != .none
    }

    /// Action useBiometrics. A fail or cancel leaves the PIN pad.
    func useBiometrics() async {
        guard !asking, offersBiometrics else { return }
        asking = true
        defer { asking = false }
        if await OwnerCheck.biometrics(reason: BipeStrings.appLockBiometric(OwnerCheck.biometricWord)) {
            BipeHaptics.play(.success)
            isLocked = false
        }
    }

    private func useBiometricsIfOn() async {
        if offersBiometrics { await useBiometrics() }
    }

    /// After the lock was turned off from settings.
    func unlockForGood() {
        isLocked = false
        isCovered = false
    }
}

/// Puts the lock screen, or the plain cover, over the app.
struct AppLockGate: ViewModifier {
    @ObservedObject private var lock = AppLockModel.shared
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .overlay {
                if lock.isLocked {
                    AppLockScreen(lock: lock)
                } else if lock.isCovered {
                    BipeColor.surfaceBase.ignoresSafeArea()
                }
            }
            .onChange(of: scenePhase) { _, phase in
                lock.scenePhaseChanged(phase)
            }
            .task {
                if lock.isLocked { await lock.useBiometrics() }
            }
    }
}

private struct AppLockScreen: View {
    @ObservedObject var lock: AppLockModel

    var body: some View {
        PinPadView(title: BipeStrings.appLockTitle,
                   message: BipeStrings.pinEnterTitle,
                   error: lock.wrong ? BipeStrings.appLockWrong : nil,
                   pin: lock.pin,
                   onBiometrics: lock.offersBiometrics ? { Task { await lock.useBiometrics() } } : nil,
                   onDigit: lock.type,
                   onDelete: lock.delete)
            .accessibilityIdentifier(BipeScreen.appLock.rawValue)
    }
}

/// Spec app-lock, modes turnOn and biometricOffer: the PIN setup from the appLock switch.
struct AppLockSetupView: View {
    let onDone: () -> Void
    @StateObject private var setup = PinSetupModel()
    @State private var offersBiometrics = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if offersBiometrics {
                    biometricOffer
                } else {
                    PinPadView(title: setup.title,
                               message: setup.message,
                               error: setup.problem,
                               pin: setup.pin,
                               onDigit: { digit in setup.type(digit, done: save) },
                               onDelete: setup.delete)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text(BipeStrings.appCancel)
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.appLock.rawValue)
        .interactiveDismissDisabled()
    }

    /// The PIN is stored on this phone only, as the app lock PIN (success haptic, use pin_set).
    private func save(_ pin: String) {
        guard AppLockModel.shared.store.setPIN(pin) else {
            setup.reset(problem: BipeStrings.errorGeneric)
            return
        }
        BipeHaptics.play(.success)
        if OwnerCheck.biometricKind != .none {
            offersBiometrics = true
        } else {
            finish()
        }
    }

    private var biometricOffer: some View {
        VStack(spacing: 20) {
            Spacer()
            PhosphorIconView(icon: .fingerprint, size: 56)
            Text(BipeStrings.appLockBiometricOfferTitle(OwnerCheck.biometricWord))
                .font(.title3.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Spacer()
            // Action acceptBiometrics: asks once to confirm, then turns biometric unlock on.
            Button {
                Task {
                    if await OwnerCheck.biometrics(reason: BipeStrings.appLockBiometric(OwnerCheck.biometricWord)) {
                        AppLockModel.shared.store.setBiometricUnlock(true)
                    }
                    finish()
                }
            } label: {
                Text(BipeStrings.appLockBiometricOfferUse(OwnerCheck.biometricWord))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            // Action skipBiometrics.
            Button {
                finish()
            } label: {
                Text(BipeStrings.fallbackSkip)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(24)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }

    private func finish() {
        onDone()
        dismiss()
    }
}

/// Asks for the app lock PIN once, for a phone with no passcode (app-lock turnOff, data-export enterPin).
struct AppLockPinCheckView: View {
    let title: String
    let onPass: () -> Void
    @State private var pin = ""
    @State private var wrong = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            PinPadView(title: title,
                       message: BipeStrings.pinEnterTitle,
                       error: wrong ? BipeStrings.pinMismatch : nil,
                       pin: pin,
                       onDigit: type,
                       onDelete: { if !pin.isEmpty { pin.removeLast() } })
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button {
                            dismiss()
                        } label: {
                            Text(BipeStrings.appCancel)
                        }
                    }
                }
        }
    }

    private func type(_ digit: String) {
        guard pin.count < BipeRegistries.pinLength else { return }
        wrong = false
        pin += digit
        guard pin.count == BipeRegistries.pinLength else { return }
        let typed = pin
        pin = ""
        if AppLockModel.shared.store.verifyPIN(typed) {
            dismiss()
            onPass()
        } else {
            BipeHaptics.play(.error)
            wrong = true
        }
    }
}

struct PhosphorIconView: View {
    let icon: PhosphorIcon
    var size: CGFloat = 24

    var body: some View {
        icon.image
            .resizable()
            .frame(width: size, height: size)
            .foregroundStyle(BipeColor.accentInk)
            .accessibilityHidden(true)
    }
}

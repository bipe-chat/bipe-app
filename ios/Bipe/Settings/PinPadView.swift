// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// A PIN pad: title, dots for the digits typed, an optional message, the keys. The PIN never shows as text.
struct PinPadView: View {
    let title: String
    var message: String?
    var error: String?
    let pin: String
    var isBusy = false
    /// Shows the biometrics key; nil hides it.
    var onBiometrics: (() -> Void)?
    let onDigit: (String) -> Void
    let onDelete: () -> Void

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                content(keySize: min(72, max(44, (geometry.size.width - 96) / 3)))
                    .padding(24)
                    .frame(maxWidth: 400)
                    .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }

    private func content(keySize: CGFloat) -> some View {
        VStack(spacing: 24) {
            Spacer(minLength: 16)
            PhosphorIcon.lockSimple.image
                .resizable()
                .frame(width: 40, height: 40)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(title)
                .font(.title3.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 12) {
                ForEach(0..<BipeRegistries.pinLength, id: \.self) { index in
                    Circle()
                        .fill(index < pin.count ? BipeColor.accentFill : BipeColor.lineStrong)
                        .frame(width: 12, height: 12)
                }
            }
            .accessibilityElement()
            .accessibilityLabel(title)
            .accessibilityValue(BipeStrings.a11yPinProgress(String(pin.count), String(BipeRegistries.pinLength)))
            Group {
                if isBusy {
                    ProgressView()
                } else if let error {
                    Text(error)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.trustDangerInk)
                        .multilineTextAlignment(.center)
                } else {
                    Color.clear
                }
            }
            .frame(minHeight: 40)
            Spacer(minLength: 8)
            keys(size: keySize)
        }
    }

    private func keys(size: CGFloat) -> some View {
        let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]]
        return VStack(spacing: 14) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 24) {
                    ForEach(row, id: \.self) { digit in key(digit, size: size) }
                }
            }
            HStack(spacing: 24) {
                if let onBiometrics {
                    iconKey(.fingerprint, label: BipeStrings.a11yBiometrics, size: size, action: onBiometrics)
                } else {
                    Color.clear.frame(width: size, height: size)
                }
                key("0", size: size)
                iconKey(.backspace, label: BipeStrings.a11yPinDelete, size: size, action: onDelete)
            }
        }
        .disabled(isBusy)
    }

    private func key(_ digit: String, size: CGFloat) -> some View {
        Button {
            onDigit(digit)
        } label: {
            Text(verbatim: digit)
                .font(.title)
                .foregroundStyle(BipeColor.inkPrimary)
                .frame(width: size, height: size)
                .background(Circle().fill(BipeColor.surfaceRaised))
        }
        .buttonStyle(.plain)
    }

    private func iconKey(_ icon: PhosphorIcon, label: String, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            icon.image
                .resizable()
                .frame(width: 28, height: 28)
                .foregroundStyle(BipeColor.glyphPrimary)
                .frame(width: size, height: size)
                .bipeTapTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The create and confirm steps of signup-pin, shared by app-lock (turnOn) and pin-change.
@MainActor
final class PinSetupModel: ObservableObject {
    enum Step {
        case create
        case confirm
    }

    @Published private(set) var step: Step = .create
    @Published private(set) var pin = ""
    @Published var problem: String?
    private var first = ""

    /// Calls `done` with the PIN when the confirm step matches.
    func type(_ digit: String, done: (String) -> Void) {
        guard pin.count < BipeRegistries.pinLength else { return }
        if pin.isEmpty { problem = nil }
        pin += digit
        guard pin.count == BipeRegistries.pinLength else { return }
        switch step {
        case .create:
            if let weak = BipePIN.problem(for: pin) {
                problem = weak.localizedMessage
                BipeHaptics.play(.error)
                pin = ""
            } else {
                first = pin
                pin = ""
                step = .confirm
            }
        case .confirm:
            if pin == first {
                let confirmed = pin
                pin = ""
                done(confirmed)
            } else {
                problem = BipeStrings.pinMismatch
                BipeHaptics.play(.error)
                reset()
            }
        }
    }

    func delete() {
        if !pin.isEmpty { pin.removeLast() }
    }

    /// Back to create, for example after the server said pin_weak.
    func reset(problem: String? = nil) {
        if let problem { self.problem = problem }
        first = ""
        pin = ""
        step = .create
    }

    var title: String {
        step == .create ? BipeStrings.pinCreateTitle : BipeStrings.pinConfirmTitle
    }

    var message: String {
        step == .create ? BipeStrings.pinCreateBody(String(BipeRegistries.pinLength)) : BipeStrings.pinNeverAsked
    }
}

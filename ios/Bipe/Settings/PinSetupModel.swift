// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// The haptics of a PIN field (design/haptics.md): a tick per digit, a buzz on a wrong PIN, success when a PIN is set
/// or unlocks. The phone plays the generated BipeHaptics; the Mac plays NSHapticFeedbackManager on a Force Touch trackpad.
/// Also compiled into the Mac app (macos/project.yml): keep it free of app-only types.
@MainActor
enum PinHaptic {
    static func digit() {
        #if canImport(UIKit)
        BipeHaptics.play(.tick)
        #elseif canImport(AppKit)
        NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now)
        #endif
    }

    static func wrong() {
        #if canImport(UIKit)
        BipeHaptics.play(.error)
        #elseif canImport(AppKit)
        NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
        #endif
    }

    static func success() {
        #if canImport(UIKit)
        BipeHaptics.play(.success)
        #elseif canImport(AppKit)
        NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
        #endif
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
                PinHaptic.wrong()
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
                PinHaptic.wrong()
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

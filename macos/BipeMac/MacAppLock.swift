// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// Spec app-lock on the Mac: off by default (ADR 0137). The PIN and the Touch ID flag live in this Mac's Keychain
/// only (BipeAppLock with a Mac service), never synced and never sent. The try limit is BipeAppLock's (bipe#777).
/// The Mac locks at launch, when it sleeps or its screen locks, and when Bipe is hidden.
@MainActor
final class MacAppLock: ObservableObject {
    static let shared = MacAppLock()
    /// A service of its own, apart from the session vault, in this Mac's Keychain.
    static let service = "chat.bipe.app.mac.applock"

    let store = BipeAppLock(service: MacAppLock.service, accessGroup: nil)
    @Published private(set) var isLocked: Bool
    @Published private(set) var isOn: Bool
    @Published private(set) var biometricOn: Bool
    @Published private(set) var pin = ""
    @Published private(set) var wrong = false
    /// Too many wrong PINs: digits are ignored until this time.
    @Published private(set) var waitUntil: Date?
    private var asking = false
    private var waitTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    private init() {
        let store = BipeAppLock(service: MacAppLock.service, accessGroup: nil)
        isOn = store.isOn
        biometricOn = store.biometricUnlock
        isLocked = store.isOn
        if isLocked { showWait(store.waitUntil()) }
        observe()
    }

    /// Action lock: the lock screen covers every window.
    func lock() {
        refresh()
        guard isOn, !isLocked else { return }
        pin = ""
        wrong = false
        isLocked = true
        showWait(store.waitUntil())
    }

    func refresh() {
        isOn = store.isOn
        biometricOn = store.biometricUnlock
    }

    private func observe() {
        let workspace = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            observers.append(workspace.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { MacAppLock.shared.lock() }
            })
        }
        observers.append(DistributedNotificationCenter.default().addObserver(forName: Notification.Name("com.apple.screenIsLocked"),
                                                                              object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { MacAppLock.shared.lock() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didHideNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { MacAppLock.shared.lock() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                let lock = MacAppLock.shared
                if lock.isLocked, lock.offersBiometrics { Task { await lock.useBiometrics() } }
            }
        })
    }

    /// Action typeDigit, mode locked.
    func type(_ digit: String) {
        guard isLocked, waitUntil == nil, pin.count < BipeRegistries.pinLength else { return }
        PinHaptic.digit()
        wrong = false
        pin += digit
        guard pin.count == BipeRegistries.pinLength else { return }
        let typed = pin
        pin = ""
        switch store.checkPIN(typed) {
        case .match:
            PinHaptic.success()
            isLocked = false
        case .wrong:
            PinHaptic.wrong()
            wrong = true
        case let .wait(until):
            PinHaptic.wrong()
            showWait(until)
        }
    }

    func delete() {
        if !pin.isEmpty { pin.removeLast() }
    }

    private func showWait(_ until: Date?) {
        waitTask?.cancel()
        waitUntil = until
        guard let until else { return }
        waitTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, until.timeIntervalSinceNow)))
            guard !Task.isCancelled, let self else { return }
            self.showWait(self.store.waitUntil())
        }
    }

    var offersBiometrics: Bool { store.biometricUnlock && OwnerCheck.biometricKind != .none }

    /// Action useBiometrics. A fail or cancel leaves the PIN pad.
    func useBiometrics() async {
        guard isLocked, !asking, offersBiometrics else { return }
        asking = true
        defer { asking = false }
        if await OwnerCheck.biometrics(reason: BipeStrings.appLockBiometric(OwnerCheck.biometricWord)) {
            PinHaptic.success()
            store.clearFailures()
            showWait(nil)
            isLocked = false
        }
    }

    /// Action turnOn, the save step: the PIN on this Mac only (success haptic, use pin_set).
    func setPIN(_ pin: String) -> Bool {
        guard store.setPIN(pin) else { return false }
        PinHaptic.success()
        refresh()
        return true
    }

    func setBiometricUnlock(_ isOn: Bool) {
        store.setBiometricUnlock(isOn)
        refresh()
    }

    /// Action turnOff, after the owner check or the PIN check passed.
    func remove() {
        store.remove()
        refresh()
        isLocked = false
    }
}

/// A PIN field on the Mac: dots, a keypad, and the keyboard's digits and delete key. Every digit ticks.
struct MacPinPad: View {
    let title: String
    let message: String
    var error: String?
    let pin: String
    var onBiometrics: (() -> Void)?
    let onDigit: (String) -> Void
    let onDelete: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: MacDesign.gap) {
            Text(title).macFont(.title2).fontWeight(.bold)
            Text(message).macFont(.body).foregroundStyle(BipeColor.inkSecondary).multilineTextAlignment(.center)
            HStack(spacing: 14) {
                ForEach(0..<BipeRegistries.pinLength, id: \.self) { index in
                    Circle()
                        .fill(index < pin.count ? BipeColor.accentFill : Color.clear)
                        .overlay(Circle().stroke(BipeColor.inkSecondary, lineWidth: 1.5))
                        .frame(width: 14, height: 14)
                }
            }
            .accessibilityElement()
            .accessibilityLabel(title)
            .accessibilityValue(BipeStrings.a11yPinProgress(String(pin.count), String(BipeRegistries.pinLength)))
            if let error {
                Text(error).macFont(.callout).foregroundStyle(BipeColor.trustDangerInk).multilineTextAlignment(.center)
            }
            keypad
        }
        .padding(MacDesign.pageInset)
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onAppear { focused = true }
        .onKeyPress(characters: .decimalDigits, phases: .down) { press in
            onDigit(press.characters)
            return .handled
        }
        .onKeyPress(.delete) { onDelete(); return .handled }
    }

    private var keypad: some View {
        let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]]
        return VStack(spacing: 10) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 10) { ForEach(row, id: \.self) { key($0) } }
            }
            HStack(spacing: 10) {
                if let onBiometrics {
                    iconKey("fingerprint", label: BipeStrings.a11yBiometrics, action: onBiometrics)
                } else {
                    Color.clear.frame(width: 64, height: 48)
                }
                key("0")
                iconKey("backspace", label: BipeStrings.a11yPinDelete, action: onDelete)
            }
        }
    }

    private func key(_ digit: String) -> some View {
        Button { onDigit(digit) } label: {
            Text(digit).macFont(.title2).frame(width: 64, height: 48)
                .background(BipeColor.surfaceRaised, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }.buttonStyle(.plain)
    }

    private func iconKey(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(decorative: icon).renderingMode(.template).resizable().scaledToFit().frame(width: 22, height: 22)
                .frame(width: 64, height: 48).bipeTapTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Spec app-lock, mode locked: the lock screen in place of the window's content.
struct MacAppLockScreen: View {
    @ObservedObject var lock: MacAppLock

    var body: some View {
        MacPinPad(title: BipeStrings.appLockTitle, message: BipeStrings.pinEnterTitle,
                  error: lock.waitUntil != nil ? BipeStrings.errorRateLimited : lock.wrong ? BipeStrings.appLockWrong : nil,
                  pin: lock.pin,
                  onBiometrics: lock.offersBiometrics ? { Task { await lock.useBiometrics() } } : nil,
                  onDigit: lock.type, onDelete: lock.delete)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BipeColor.surfaceBase)
            .accessibilityIdentifier(BipeScreen.appLock.rawValue)
            .task { await lock.useBiometrics() }
    }
}

/// Spec app-lock, modes turnOn and biometricOffer: the PIN setup from the appLock switch.
struct MacAppLockSetupSheet: View {
    @ObservedObject var lock: MacAppLock
    let close: () -> Void
    @StateObject private var setup = PinSetupModel()
    @State private var offersBiometrics = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button(BipeStrings.appCancel, action: close).keyboardShortcut(.cancelAction)
            }.padding(MacDesign.gap)
            if offersBiometrics {
                biometricOffer
            } else {
                MacPinPad(title: setup.title, message: setup.message, error: setup.problem, pin: setup.pin,
                          onDigit: { digit in
                              PinHaptic.digit()
                              setup.type(digit, done: save)
                          }, onDelete: setup.delete)
            }
        }
        .frame(width: 420, height: 560)
        .background(BipeColor.surfaceBase)
        .accessibilityIdentifier(BipeScreen.appLock.rawValue)
        .interactiveDismissDisabled()
    }

    private func save(_ pin: String) {
        guard lock.setPIN(pin) else {
            setup.reset(problem: BipeStrings.errorGeneric)
            return
        }
        if OwnerCheck.biometricKind != .none { offersBiometrics = true } else { close() }
    }

    private var biometricOffer: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(decorative: "fingerprint").renderingMode(.template).resizable().frame(width: 56, height: 56)
                .foregroundStyle(BipeColor.accentFill)
            Text(BipeStrings.appLockBiometricOfferTitle(OwnerCheck.biometricWord)).macFont(.title2).fontWeight(.bold)
                .multilineTextAlignment(.center)
            Spacer()
            // Action acceptBiometrics: asks once to confirm, then turns biometric unlock on.
            Button {
                Task {
                    if await OwnerCheck.biometrics(reason: BipeStrings.appLockBiometric(OwnerCheck.biometricWord)) {
                        lock.setBiometricUnlock(true)
                    }
                    close()
                }
            } label: { Text(BipeStrings.appLockBiometricOfferUse(OwnerCheck.biometricWord)).frame(maxWidth: .infinity) }
                .buttonStyle(.bipeProminent)
            // Action skipBiometrics.
            Button(BipeStrings.fallbackSkip, action: close).buttonStyle(.link)
        }
        .padding(MacDesign.pageInset)
    }
}

/// Spec app-lock turnOff on a Mac with no owner check: asks for the app lock PIN once, under the same try limit.
struct MacAppLockPinCheckSheet: View {
    @ObservedObject var lock: MacAppLock
    let onPass: () -> Void
    let close: () -> Void
    @State private var pin = ""
    @State private var wrong = false
    @State private var waiting = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button(BipeStrings.appCancel, action: close).keyboardShortcut(.cancelAction)
            }.padding(MacDesign.gap)
            MacPinPad(title: BipeStrings.accountAppLock, message: BipeStrings.pinEnterTitle,
                      error: waiting ? BipeStrings.errorRateLimited : wrong ? BipeStrings.appLockWrong : nil,
                      pin: pin, onDigit: type, onDelete: { if !pin.isEmpty { pin.removeLast() } })
        }
        .frame(width: 420, height: 560)
        .background(BipeColor.surfaceBase)
    }

    private func type(_ digit: String) {
        guard pin.count < BipeRegistries.pinLength else { return }
        PinHaptic.digit()
        wrong = false
        waiting = false
        pin += digit
        guard pin.count == BipeRegistries.pinLength else { return }
        let typed = pin
        pin = ""
        switch lock.store.checkPIN(typed) {
        case .match:
            onPass()
        case .wrong:
            PinHaptic.wrong()
            wrong = true
        case .wait:
            PinHaptic.wrong()
            waiting = true
        }
    }
}

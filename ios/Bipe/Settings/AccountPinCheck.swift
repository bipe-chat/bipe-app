// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The owner check before a device approval or a PIN change (security.md rules 5 and 14 tier 3, bipe-chat/bipe#785).
/// Biometrics only: thieves watch the phone's passcode before the theft, so the passcode never passes. A phone with
/// no biometrics set up asks for the Bipe PIN, checked by the server (POST /v1/me/pin/check), where wrong tries count
/// against the account. A phone with no screen lock cannot check its owner: the approval is refused.
enum ApprovalCheck {
    enum Result {
        case passed
        case failed
        case noScreenLock
    }

    @MainActor
    static func run(reason: String, pinTitle: String, prompt: AccountPinPrompt,
                    checkPIN: @escaping @MainActor (String) async throws(BipeAPIError) -> Void) async -> Result {
        guard OwnerCheck.canCheckOwner else { return .noScreenLock }
        if OwnerCheck.biometricKind != .none {
            return await OwnerCheck.biometrics(reason: reason) ? .passed : .failed
        }
        return await prompt.ask(title: pinTitle, check: checkPIN) ? .passed : .failed
    }
}

/// One request for the Bipe PIN.
struct AccountPinRequest: Identifiable {
    let id = UUID()
    let title: String
    let check: @MainActor (String) async throws(BipeAPIError) -> Void
}

/// Asks for the Bipe PIN in a sheet and waits for the answer. The view that owns the flow shows the sheet with
/// `.accountPinPrompt(_:)`.
@MainActor
final class AccountPinPrompt: ObservableObject {
    @Published var request: AccountPinRequest?
    private var continuation: CheckedContinuation<Bool, Never>?

    /// True when the server accepted the PIN; false on cancel.
    func ask(title: String, check: @escaping @MainActor (String) async throws(BipeAPIError) -> Void) async -> Bool {
        finish(false)
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            request = AccountPinRequest(title: title, check: check)
        }
    }

    func finish(_ passed: Bool) {
        request = nil
        let continuation = continuation
        self.continuation = nil
        continuation?.resume(returning: passed)
    }
}

extension View {
    func accountPinPrompt(_ prompt: AccountPinPrompt) -> some View {
        modifier(AccountPinPromptSheet(prompt: prompt))
    }
}

private struct AccountPinPromptSheet: ViewModifier {
    @ObservedObject var prompt: AccountPinPrompt

    func body(content: Content) -> some View {
        content.sheet(item: $prompt.request, onDismiss: { prompt.finish(false) }) { request in
            AccountPinCheckView(request: request, onPass: { prompt.finish(true) }, onCancel: { prompt.finish(false) })
        }
    }
}

/// The Bipe PIN pad of the approval check. The PIN goes to POST /v1/me/pin/check only and is never kept.
struct AccountPinCheckView: View {
    let request: AccountPinRequest
    let onPass: () -> Void
    let onCancel: () -> Void
    @State private var pin = ""
    @State private var error: String?
    @State private var checking = false
    @State private var blocked = false

    var body: some View {
        NavigationStack {
            PinPadView(title: request.title,
                       message: BipeStrings.pinEnterTitle,
                       error: error,
                       pin: pin,
                       isBusy: checking,
                       onDigit: type,
                       onDelete: { if !pin.isEmpty { pin.removeLast() } })
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button {
                            onCancel()
                        } label: {
                            Text(BipeStrings.appCancel)
                        }
                    }
                }
        }
        .interactiveDismissDisabled(checking)
    }

    private func type(_ digit: String) {
        guard !checking, !blocked, pin.count < BipeRegistries.pinLength else { return }
        error = nil
        pin += digit
        guard pin.count == BipeRegistries.pinLength else { return }
        let typed = pin
        pin = ""
        checking = true
        Task {
            defer { checking = false }
            do {
                try await request.check(typed)
                BipeHaptics.play(.success)
                onPass()
            } catch {
                BipeHaptics.play(.error)
                switch error {
                case .server(.pinWrong, let triesLeft, _):
                    self.error = triesLeft.map(BipeStrings.pinWrong) ?? BipeStrings.appLockWrong
                case .server(.pinBlocked, _, _):
                    blocked = true
                    self.error = BipeStrings.pinBlockedBody(String(BipeRegistries.pinMaxWrongTries))
                case .server(.rateLimited, _, _):
                    self.error = BipeStrings.errorRateLimited
                case .network:
                    self.error = BipeStrings.errorNetwork
                default:
                    self.error = BipeStrings.errorGeneric
                }
            }
        }
    }
}

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

    /// pinReason: the pad's reason line, one of `BipeStrings.pinReason*` (ADR 0283 rule 11).
    @MainActor
    static func run(reason: String, pinTitle: String, pinReason: String, prompt: AccountPinPrompt, account: @escaping AccountRead,
                    checkPIN: @escaping @MainActor (String) async throws(BipeAPIError) -> Void) async -> Result {
        guard OwnerCheck.canCheckOwner else { return .noScreenLock }
        if OwnerCheck.biometricKind != .none {
            return await OwnerCheck.biometrics(reason: reason) ? .passed : .failed
        }
        return await prompt.ask(title: pinTitle, reason: pinReason, account: account, check: checkPIN) ? .passed : .failed
    }
}

/// Reads GET /v1/me/account for the ways out of the Bipe PIN pad (ADR 0283 rule 12).
typealias AccountRead = @MainActor () async throws(BipeAPIError) -> BipeAccountStatus

extension AccountSession {
    /// GET /v1/me/account with this account's token, for the forgot sheet of the Bipe PIN pad.
    @MainActor
    func accountRead(_ api: BipeAPIClient) -> AccountRead {
        { () async throws(BipeAPIError) -> BipeAccountStatus in
            try await BipeAPIClient.withTokenRetry(accessToken: { self.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.account(accessToken: token)
            }
        }
    }
}

/// The ways out of a forgotten Bipe PIN that an account has (ADR 0283 rules 12 and 14). A linked Apple or Google
/// account never resets the PIN (ADR 0096), so it is not a way out.
enum PinWayOut: Equatable {
    /// The verified recovery email.
    case email(String)
    case none

    init(_ account: BipeAccountStatus) {
        if let email = account.email { self = .email(email) } else { self = .none }
    }

    var text: String {
        switch self {
        case .email(let address): BipeStrings.pinForgotEmail(address, String(BipeRegistries.recoveryDelayHoursEmail))
        case .none: BipeStrings.pinForgotNone
        }
    }
}

/// One request for the Bipe PIN.
struct AccountPinRequest: Identifiable {
    let id = UUID()
    let title: String
    /// One line on why the app asks now (ADR 0283 rule 11).
    let reason: String
    let account: AccountRead
    let check: @MainActor (String) async throws(BipeAPIError) -> Void
}

/// Asks for the Bipe PIN in a sheet and waits for the answer. The view that owns the flow shows the sheet with
/// `.accountPinPrompt(_:)`.
@MainActor
final class AccountPinPrompt: ObservableObject {
    @Published var request: AccountPinRequest?
    private var continuation: CheckedContinuation<Bool, Never>?

    /// True when the server accepted the PIN; false on cancel.
    func ask(title: String, reason: String, account: @escaping AccountRead,
             check: @escaping @MainActor (String) async throws(BipeAPIError) -> Void) async -> Bool {
        finish(false)
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            request = AccountPinRequest(title: title, reason: reason, account: account, check: check)
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
    @State private var showsForgot = false

    var body: some View {
        NavigationStack {
            PinPadView(title: request.title,
                       message: request.reason,
                       error: error,
                       pin: pin,
                       isBusy: checking,
                       onForgot: { showsForgot = true },
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
        .sheet(isPresented: $showsForgot) {
            PinForgotView(account: request.account)
        }
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
            } catch let error as BipeAPIError {
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
            } catch {
                BipeHaptics.play(.error)
                self.error = BipeStrings.errorGeneric
            }
        }
    }
}

/// The forgot sheet of a Bipe PIN pad (spec account-pin-check forgot): only the ways out that the account has.
struct PinForgotView: View {
    enum Phase: Equatable {
        case loading
        case loaded(PinWayOut)
        case failed(String)
    }

    let account: AccountRead
    @Environment(\.dismiss) private var dismiss
    @State private var phase = Phase.loading

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.pinForgotTitle)
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                switch phase {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity)
                case .loaded(let way):
                    Text(way.text)
                        .font(.body)
                        .foregroundStyle(BipeColor.inkSecondary)
                case .failed(let message):
                    Text(message)
                        .font(.body)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Spacer()
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text(BipeStrings.appClose)
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await load() }
    }

    private func load() async {
        do {
            phase = .loaded(PinWayOut(try await account()))
        } catch {
            if case .network = error {
                phase = .failed(BipeStrings.errorNetwork)
            } else {
                phase = .failed(BipeStrings.errorGeneric)
            }
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The account behind a Bipe PIN pad: reads the ways out and runs the reset on this phone (spec account-pin-check).
struct PinAccount {
    let session: AccountSession
    let api: BipeAPIClient

    @MainActor
    func authorized<T>(_ request: (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }, request)
    }

    /// GET /v1/me/account.
    @MainActor
    func read() async throws(BipeAPIError) -> BipeAccountStatus {
        let api = api
        return try await authorized { (token: String) async throws(BipeAPIError) in try await api.account(accessToken: token) }
    }
}

extension AccountSession {
    func pinAccount(_ api: BipeAPIClient) -> PinAccount {
        PinAccount(session: self, api: api)
    }
}

/// The ways out of a forgotten Bipe PIN (ADR 0283 rules 12 to 14). A linked Apple or Google account never resets the
/// PIN (ADR 0096), so it is not a way out.
struct PinWaysOut: Equatable {
    /// This phone can reset the PIN: it has biometrics for the owner check (security.md rule 14).
    let phone: Bool
    /// The verified recovery email.
    let email: String?

    init(_ account: BipeAccountStatus, phone: Bool = OwnerCheck.biometricKind != .none) {
        self.phone = phone
        email = account.email
    }

    var hasNone: Bool { !phone && email == nil }
}

/// A PIN reset that this phone started (POST /v1/me/pin/reset, ADR 0283 rule 13). The claim secret stays in this
/// phone's keychain, never synced. The claim applies the new PIN after the wait.
@MainActor
enum PinResetKeeper {
    enum Outcome: Equatable {
        case waiting(Date)
        case done
        case cancelled
        case expired
    }

    private static let store = BipeCredentialStore()

    static func kept(for userId: String) -> BipeRecoveryStarted? {
        store.pinReset(for: userId)
    }

    /// Sends the new PIN and keeps the reset. This phone shows no recovery alert for its own reset.
    static func start(newPIN: String, account: PinAccount) async throws(BipeAPIError) -> BipeRecoveryStarted {
        let api = account.api
        let started = try await account.authorized { (token: String) async throws(BipeAPIError) in
            try await api.startPinReset(newPIN: newPIN, accessToken: token)
        }
        store.savePinReset(started, for: account.session.userId)
        RecoveryAlertModel.dismissOnThisPhone(userId: account.session.userId, eventIds: [started.recoveryId])
        return started
    }

    /// Claims the kept reset. nil when nothing is kept or the server cannot answer now.
    static func claim(userId: String, api: BipeAPIClient) async -> Outcome? {
        guard let kept = kept(for: userId) else { return nil }
        do {
            let claim = try await api.claimRecovery(id: kept.recoveryId, claimSecret: kept.claimSecret)
            switch claim.state {
            case .waiting: return .waiting(claim.readyAt ?? kept.readyAt)
            case .completed: store.deletePinReset(for: userId); return .done
            case .cancelled: store.deletePinReset(for: userId); return .cancelled
            case .expired: store.deletePinReset(for: userId); return .expired
            }
        } catch {
            guard case .server(.recoveryInvalid, _, _) = error else { return nil }
            store.deletePinReset(for: userId)
            return .expired
        }
    }

    /// POST /v1/me/recoveries/{id}/cancel. A 404 means the reset no longer waits.
    static func cancel(account: PinAccount) async throws(BipeAPIError) {
        let userId = account.session.userId
        guard let kept = kept(for: userId) else { return }
        let api = account.api
        do {
            try await account.authorized { (token: String) async throws(BipeAPIError) in
                try await api.cancelRecovery(id: kept.recoveryId, accessToken: token)
            }
        } catch {
            guard case .server(.notFound, _, _) = error else { throw error }
        }
        store.deletePinReset(for: userId)
    }
}

/// The forgot sheet of a Bipe PIN pad (spec account-pin-check forgot, resetHere, claim, cancelReset, addEmail): only
/// the ways out that the account has, and the reset on this phone.
struct PinForgotView: View {
    enum Phase: Equatable {
        case loading
        case ways(PinWaysOut)
        case newPin
        case sending
        case waiting(Date)
        case cancelling(Date)
        case done(hasEmail: Bool)
        case cancelled
        case expired
        case failed(String)
    }

    let account: PinAccount
    @Environment(\.dismiss) private var dismiss
    @State private var phase = Phase.loading
    @StateObject private var setup = PinSetupModel()
    @State private var showsEmail = false
    @State private var status: BipeAccountStatus?

    var body: some View {
        NavigationStack {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button {
                            dismiss()
                        } label: {
                            Text(BipeStrings.appClose)
                        }
                        .disabled(phase == .sending)
                    }
                }
        }
        .interactiveDismissDisabled(phase == .sending)
        .task { await load() }
        .sheet(isPresented: $showsEmail, onDismiss: { dismiss() }) {
            RecoveryEmailAddView(api: account.api, session: account.session, current: status?.email,
                                 pending: status?.emailPending, pinSet: true) {}
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .newPin, .sending:
            PinPadView(title: setup.step == .create ? BipeStrings.recoveryNewPinTitle : BipeStrings.pinConfirmTitle,
                       message: setup.message,
                       error: setup.problem,
                       pin: setup.pin,
                       isBusy: phase == .sending,
                       onDigit: { digit in setup.type(digit) { pin in Task { await send(pin) } } },
                       onDelete: setup.delete)
        default:
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.pinForgotTitle)
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                message
                Spacer()
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var message: some View {
        switch phase {
        case .loading, .newPin, .sending:
            ProgressView().frame(maxWidth: .infinity)
        case .ways(let ways):
            if ways.phone {
                line(BipeStrings.pinForgotPhone(String(BipeRegistries.recoveryDelayHoursEmail)))
                Button {
                    Task { await confirmOwner() }
                } label: {
                    Text(BipeStrings.pinForgotPhoneAction).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeProminent)
            }
            if let email = ways.email {
                line(BipeStrings.pinForgotEmail(email, String(BipeRegistries.recoveryDelayHoursEmail)))
            }
            if ways.hasNone {
                line(BipeStrings.pinForgotNone)
            }
        case .waiting(let readyAt), .cancelling(let readyAt):
            Text(BipeStrings.recoveryWaitingTitle)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            line(BipeStrings.recoveryWaitingPinBody(readyAt.formatted(date: .abbreviated, time: .shortened)))
            Button {
                Task { await cancel(readyAt) }
            } label: {
                Text(BipeStrings.pinResetCancel).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeBordered)
            .disabled(phase == .cancelling(readyAt))
        case .done(let hasEmail):
            line(BipeStrings.recoveryPinReady)
            if !hasEmail {
                line(BipeStrings.pinResetAddEmail)
                Button {
                    showsEmail = true
                } label: {
                    Text(BipeStrings.fallbackEmail).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeProminent)
            }
        case .cancelled:
            line(BipeStrings.pinResetCancelled)
        case .expired:
            line(BipeStrings.pinResetExpired)
        case .failed(let text):
            Text(text)
                .font(.body)
                .foregroundStyle(BipeColor.trustDangerInk)
        }
    }

    private func line(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(BipeColor.inkSecondary)
    }

    /// Action forgot: a kept reset first (claim), else the ways out from GET /v1/me/account.
    private func load() async {
        let userId = account.session.userId
        if PinResetKeeper.kept(for: userId) != nil, let outcome = await PinResetKeeper.claim(userId: userId, api: account.api) {
            await show(outcome)
            return
        }
        do {
            let read = try await account.read()
            status = read
            phase = .ways(PinWaysOut(read))
        } catch {
            phase = .failed(Self.text(error))
        }
    }

    private func show(_ outcome: PinResetKeeper.Outcome) async {
        switch outcome {
        case .waiting(let readyAt): phase = .waiting(readyAt)
        case .cancelled: phase = .cancelled
        case .expired: phase = .expired
        case .done:
            status = try? await account.read()
            phase = .done(hasEmail: status?.email != nil)
            BipeHaptics.play(.success)
        }
    }

    /// Action resetHere: biometrics only, never the passcode (security.md rule 14).
    private func confirmOwner() async {
        guard await OwnerCheck.biometrics(reason: BipeStrings.pinResetBiometric) else { return }
        setup.reset()
        phase = .newPin
    }

    private func send(_ pin: String) async {
        phase = .sending
        do {
            let started = try await PinResetKeeper.start(newPIN: pin, account: account)
            BipeHaptics.play(.success)
            phase = .waiting(started.readyAt)
        } catch {
            switch error {
            case .server(.pinWeak, let reason, _):
                setup.reset(problem: (reason.flatMap(BipePinProblem.init(rawValue:)) ?? .sequence).localizedMessage)
                phase = .newPin
            case .server(.pinResetPending, _, _):
                phase = .failed(BipeStrings.pinResetPending)
            default:
                phase = .failed(Self.text(error))
            }
            BipeHaptics.play(.error)
        }
    }

    /// Action cancelReset.
    private func cancel(_ readyAt: Date) async {
        phase = .cancelling(readyAt)
        do {
            try await PinResetKeeper.cancel(account: account)
            phase = .cancelled
        } catch {
            phase = .failed(Self.text(error))
        }
    }

    private static func text(_ error: BipeAPIError) -> String {
        switch error {
        case .network: BipeStrings.errorNetwork
        case .server(.rateLimited, _, _): BipeStrings.errorRateLimited
        default: BipeStrings.errorGeneric
        }
    }
}

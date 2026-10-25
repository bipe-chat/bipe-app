// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec recovery-reminder (bipe-chat/bipe#531): the card above the chat list for an account without a way back that
/// survives the App Store transfer. The rule is the server's (`independent_recovery`); timing is BipeRecoveryReminder.
@MainActor
final class RecoveryReminderModel: ObservableObject {
    /// The account state, for the card and for recovery-email-add. `nil` until GET /v1/me/account answers.
    @Published private(set) var account: BipeAccountStatus?
    @Published private var snoozedUntil: Date?

    let api: BipeAPIClient
    let session: AccountSession
    private let defaults = UserDefaults.standard
    private var sinceKey: String { "bipe.recoveryReminder.since.\(session.userId)" }
    private var snoozeKey: String { "bipe.recoveryReminder.snoozedUntil.\(session.userId)" }
    private var countKey: String { "bipe.recoveryReminder.count.\(session.userId)" }

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
        snoozedUntil = defaults.object(forKey: "bipe.recoveryReminder.snoozedUntil.\(session.userId)") as? Date
        // "Came to this phone": the first time this account's chat list opened here.
        if defaults.object(forKey: sinceKey) == nil { defaults.set(Date(), forKey: sinceKey) }
    }

    /// State visible. Unknown account state (offline, older server) shows no card.
    var showsCard: Bool {
        guard account?.independentRecovery == false,
              let since = defaults.object(forKey: sinceKey) as? Date else { return false }
        return BipeRecoveryReminder.isDue(now: Date(), since: since, snoozedUntil: snoozedUntil)
    }

    /// Reads the account state. Runs on open and when the app comes back, so the card goes after the link is opened.
    func load() async {
        let api = api
        if let value = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }) {
            account = value
        }
    }

    /// Action dismiss: "Agora não" hides the card for the next snooze.
    func dismiss() {
        let count = defaults.integer(forKey: countKey)
        let until = BipeRecoveryReminder.snoozedUntil(now: Date(), count: count)
        defaults.set(count + 1, forKey: countKey)
        defaults.set(until, forKey: snoozeKey)
        snoozedUntil = until
    }
}

/// The card (registries/onboarding.json prompts.recovery). Same shape as the handle prompt card.
struct RecoveryReminderCard: View {
    @ObservedObject var model: RecoveryReminderModel
    let addEmail: () -> Void

    var body: some View {
        let prompt = BipeOnboardingPrompt.recovery
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeLocalized.string(prompt.titleKey))
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            if let bodyKey = prompt.bodyKey {
                Text(BipeLocalized.string(bodyKey))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            // State syncOff (bipe-chat/bipe#202): iCloud off on this phone, so the recovery token is not synced.
            if FileManager.default.ubiquityIdentityToken == nil {
                Text(BipeStrings.fallbackSyncOffIos)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            HStack {
                Button(action: addEmail) {
                    Text(prompt.primary.title)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
                ForEach(prompt.text, id: \.titleKey) { button in
                    Button { model.dismiss() } label: {
                        Text(button.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BipeColor.accentInk)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.accentWash))
        .accessibilityIdentifier(BipeScreen.recoveryReminder.rawValue)
    }
}

/// Spec recovery-email-add: add or change the recovery e-mail on a signed-in phone, after the owner check.
struct RecoveryEmailAddView: View {
    enum Phase: Equatable {
        case confirmIdentity
        case editing
        case sending
        case sent(String)
        case failed(String)
    }

    let api: BipeAPIClient
    let session: AccountSession
    /// The verified address and the one waiting for its link, from GET /v1/me/account.
    let current: String?
    let pending: String?
    /// After 202: the caller reloads the account state.
    let onSent: () -> Void

    @State private var phase: Phase = .confirmIdentity
    @State private var email = ""
    @State private var emailInvalid = false
    @State private var pinCheck: PinCheckRequest?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .confirmIdentity:
                    BipeColor.surfaceBase.ignoresSafeArea()
                case .sent(let address):
                    VStack(spacing: 16) {
                        PhosphorIconView(icon: .check, size: 48)
                        Text(BipeStrings.emailSent(address))
                            .font(.title3.bold())
                            .foregroundStyle(BipeColor.inkPrimary)
                            .multilineTextAlignment(.center)
                        Button { dismiss() } label: { Text(BipeStrings.appClose) }
                            .buttonStyle(.bipeProminent)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
                default:
                    form
                }
            }
            .navigationTitle(BipeStrings.emailTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(BipeStrings.appCancel) }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.recoveryEmailAdd.rawValue)
        .task { await confirmIdentity() }
        .sheet(item: $pinCheck, onDismiss: {
            if phase == .confirmIdentity { dismiss() }
        }) { request in
            AppLockPinCheckView(title: request.title, onPass: request.onPass)
        }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(current ?? BipeStrings.accountEmailNone)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            if pending != nil {
                Text(BipeStrings.emailPending)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            TextField(BipeStrings.signupEmail, text: Binding(get: { email }, set: editEmail))
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10).fill(BipeColor.surfaceRaised))
                .disabled(phase == .sending)
            if emailInvalid {
                Text(BipeStrings.signupEmailInvalid)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            if case .failed(let message) = phase {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            Text(BipeStrings.emailFooter)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            Button {
                Task { await send() }
            } label: {
                Text(BipeStrings.emailSend)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            .disabled(email.isEmpty || phase == .sending)
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }

    /// Action confirmIdentity (as pin-change): the owner check, or the app lock PIN on a phone with no passcode when
    /// the lock is on; else the screen goes on.
    private func confirmIdentity() async {
        guard phase == .confirmIdentity else { return }
        email = pending ?? current ?? ""
        if OwnerCheck.canCheckOwner {
            if await OwnerCheck.owner(reason: BipeStrings.emailChangeReason) {
                phase = .editing
            } else {
                dismiss()
            }
        } else if AppLockModel.shared.store.isOn {
            pinCheck = PinCheckRequest(title: BipeStrings.emailTitle) { phase = .editing }
        } else {
            phase = .editing
        }
    }

    /// Action editEmail.
    private func editEmail(_ text: String) {
        email = text
        emailInvalid = false
        if case .failed = phase { phase = .editing }
    }

    /// Action send: PUT /v1/me/email. The address is never logged.
    private func send() async {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard BipeSignupRules.isPlausibleEmail(address) else {
            emailInvalid = true
            return
        }
        phase = .sending
        let api = api
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.setRecoveryEmail(address, accessToken: token)
            }
            BipeHaptics.play(.success)
            phase = .sent(address)
            onSent()
        } catch {
            switch error {
            case .server(.emailTaken, _, _): phase = .failed(BipeStrings.errorEmailTaken)
            case .server(.emailUnavailable, _, _): phase = .failed(BipeStrings.errorEmailUnavailable)
            case .server(.rateLimited, _, _): phase = .failed(BipeStrings.errorRateLimited)
            case .server(.badRequest, _, _):
                emailInvalid = true
                phase = .editing
            case .network: phase = .failed(BipeStrings.errorNetwork)
            default: phase = .failed(BipeStrings.errorGeneric)
            }
        }
    }
}

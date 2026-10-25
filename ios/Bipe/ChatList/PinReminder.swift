// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec pin-reminder (bipe-chat/bipe#202): whether the spaced PIN check shows now on this account.
@MainActor
final class PinReminderModel: ObservableObject {
    @Published private(set) var showsCard = false

    let session: AccountSession
    private let memory: BipePinReminderMemory
    private let slot: BipePromptSlot

    init(session: AccountSession) {
        self.session = session
        memory = BipePinReminderMemory(userID: session.userId)
        slot = BipePromptSlot(userID: session.userId)
        _ = memory.firstSeen
        refresh()
    }

    /// State due: the reminder time has come and the prompt slot allows it.
    func refresh() {
        showsCard = memory.isDue() && slot.allows(.pinReminder)
    }

    func shown() { slot.didShow(.pinReminder) }

    /// The check result moves the schedule (pinReminderNext); "Agora não" asks again after a day.
    func checked(_ result: BipeOnboardingSchedule.PinResult) {
        memory.checked(result)
        showsCard = false
    }
}

/// The card (registries/onboarding.json prompts.pinReminder). Same shape as the recovery reminder card.
struct PinReminderCard: View {
    @ObservedObject var model: PinReminderModel
    let open: () -> Void

    var body: some View {
        let prompt = BipeOnboardingPrompt.pinReminder
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeLocalized.string(prompt.titleKey))
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.pinReminderBody(String(BipeRegistries.pinLength)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            HStack {
                Button(action: open) {
                    Text(prompt.primary.title)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
                ForEach(prompt.text, id: \.titleKey) { button in
                    Button { model.checked(.later) } label: {
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
        .onAppear { model.shown() }
    }
}

/// Spec pin-reminder: one check of the PIN with POST /v1/me/pin/check.
struct PinReminderView: View {
    enum Phase { case editing, checking, right, wrong, blocked, failed }

    let session: AccountSession
    let api: BipeAPIClient
    let onResult: (BipeOnboardingSchedule.PinResult) -> Void
    let changePin: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var phase: Phase = .editing
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.pinReminderBody(String(BipeRegistries.pinLength)))
                    .foregroundStyle(BipeColor.inkSecondary)
                switch phase {
                case .editing, .checking, .failed:
                    SecureField(BipeStrings.pinReminderAction, text: Binding(get: { pin }, set: edit))
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .focused($focused)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                        .disabled(phase == .checking)
                    if phase == .checking { ProgressView().frame(maxWidth: .infinity) }
                    if phase == .failed {
                        Text(BipeStrings.errorNetwork).font(.footnote).foregroundStyle(BipeColor.trustDangerInk)
                    }
                case .right:
                    Text(BipeStrings.pinReminderRight).foregroundStyle(BipeColor.statusSuccess)
                case .wrong:
                    Text(BipeStrings.pinReminderWrong).foregroundStyle(BipeColor.trustDangerInk)
                    Button {
                        dismiss()
                        changePin()
                    } label: {
                        Text(BipeStrings.pinChangeTitle).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                case .blocked:
                    Text(BipeStrings.pinBlockedBody(String(BipeRegistries.pinMaxWrongTries)))
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Spacer()
            }
            .padding(24)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.pinReminderTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(BipeStrings.appClose) { dismiss() } }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.pinReminder.rawValue)
        .onAppear { focused = true }
    }

    private func edit(_ text: String) {
        pin = String(BipePIN.sanitize(text).prefix(BipeRegistries.pinLength))
        if phase == .failed { phase = .editing }
        guard pin.count == BipeRegistries.pinLength, phase == .editing else { return }
        Task { await check() }
    }

    /// Action submit: one check per reminder. The PIN is never logged.
    private func check() async {
        phase = .checking
        let typed = pin
        pin = ""
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> Void in
                try await api.checkPIN(typed, accessToken: token)
            }
            phase = .right
            onResult(.right)
        } catch {
            switch error {
            case .server(.pinWrong, _, _):
                phase = .wrong
                onResult(.wrong)
            case .server(.pinBlocked, _, _):
                phase = .blocked
                onResult(.wrong)
            default:
                phase = .failed
            }
        }
    }
}

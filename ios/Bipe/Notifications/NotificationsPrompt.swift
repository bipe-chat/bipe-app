// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec notifications-prompt: after the first message shows as sent, a sheet asks whether the person wants
/// to know when the reply comes. The registry prompt (BipeOnboardingPrompt.notifications) gives the buttons.
struct NotificationsPromptView: View {
    /// DM: the other person's name for the title. nil in a group.
    let recipientName: String?
    let close: () -> Void
    @State private var isAsking = false

    private let prompt = BipeOnboardingPrompt.notifications

    var body: some View {
        VStack(spacing: 16) {
            PhosphorIcon.bell.image
                .resizable()
                .frame(width: 48, height: 48)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(recipientName.map(BipeStrings.notificationsPromptTitle) ?? BipeStrings.notificationsPromptTitleGeneric)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            if prompt.bodyKey != nil {
                Text(BipeStrings.notificationsPromptBody)
                    .font(.body)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
            }
            Button {
                Task { await allow() }
            } label: {
                Text(prompt.primary.title)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            .disabled(isAsking)
            ForEach(prompt.text, id: \.titleKey) { button in
                Button {
                    notNow()
                } label: {
                    Text(button.title)
                }
                .disabled(isAsking)
            }
        }
        .padding(24)
        .tint(BipeColor.accentFill)
        .presentationDetents([.medium])
        .accessibilityIdentifier(BipeScreen.notificationsPrompt.rawValue)
    }

    /// Action allow: the system dialog; allowed registers this phone. Either way the sheet closes for good.
    private func allow() async {
        isAsking = true
        await PushRegistration.shared.requestPermission()
        isAsking = false
        NotificationsPromptView.markShown()
        close()
    }

    /// Action notNow: closes the sheet. It does not come back on this phone.
    private func notNow() {
        NotificationsPromptView.markShown()
        close()
    }

    private static let shownKey = "notificationsPromptShown"

    static func markShown() {
        UserDefaults.standard.set(true, forKey: shownKey)
    }

    /// Only when the system permission was never asked and the sheet never showed on this phone.
    @MainActor
    static func shouldShow() async -> Bool {
        guard !UserDefaults.standard.bool(forKey: shownKey) else { return false }
        await PushRegistration.shared.appBecameActive()
        return PushRegistration.shared.permission == .notAsked
    }
}

extension View {
    /// Trigger firstMessageSent (registries/onboarding.json): opens the prompt once an own message shows as sent.
    func notificationsPrompt(session: AccountSession, roomId: String, messages: [TimelineMessage]) -> some View {
        modifier(NotificationsPromptTrigger(session: session, roomId: roomId, messages: messages))
    }
}

private struct NotificationsPromptTrigger: ViewModifier {
    let session: AccountSession
    let roomId: String
    let messages: [TimelineMessage]
    @State private var isShown = false
    @State private var recipientName: String?
    @State private var openedAt = Date.now

    /// Own messages sent since the chat opened: history does not count.
    private var newSentCount: Int {
        messages.filter { $0.isOwn && $0.sendState == .sent && $0.sentAt >= openedAt }.count
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: newSentCount) { _, count in
                if count > 0 {
                    Task { await check() }
                }
            }
            .sheet(isPresented: $isShown) {
                NotificationsPromptView(recipientName: recipientName) { isShown = false }
            }
    }

    @MainActor
    private func check() async {
        guard !isShown, await NotificationsPromptView.shouldShow() else { return }
        if let room = try? session.client.getRoom(roomId: roomId), let info = try? await room.roomInfo(), info.isDm {
            // ADR 0141: the person, never the DM's m.room.name.
            let peer = info.heroes.first { $0.userId != session.userId }
            recipientName = peer?.displayName?.nilIfBlank ?? peer.map { Person.localpart(of: $0.userId) }
        }
        isShown = true
    }
}

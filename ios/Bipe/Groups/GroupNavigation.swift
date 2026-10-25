// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

private struct CloseChatKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

private struct OpenOtherChatKey: EnvironmentKey {
    static let defaultValue: @MainActor (String) -> Void = { _ in }
}

private struct BipeAPIKey: EnvironmentKey {
    static let defaultValue: BipeAPIClient? = nil
}

extension EnvironmentValues {
    /// The Bipe API client for the screens that need it (invites from group-invite-members).
    var bipeAPI: BipeAPIClient? {
        get { self[BipeAPIKey.self] }
        set { self[BipeAPIKey.self] = newValue }
    }

    /// Closes the open chat and opens another one (a new DM from chat-timeline inviteAgain).
    var openOtherChat: @MainActor (String) -> Void {
        get { self[OpenOtherChatKey.self] }
        set { self[OpenOtherChatKey.self] = newValue }
    }

    /// Closes the open chat and goes back to the chat list (group-leave and dm-delete).
    var closeChat: @MainActor () -> Void {
        get { self[CloseChatKey.self] }
        set { self[CloseChatKey.self] = newValue }
    }
}

/// A short message at the bottom of the screen, for results such as bipe_dm_invite_to_group_sent.
struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(BipeColor.surfaceRaised).shadow(radius: 4))
                    .padding(.bottom, 24)
                    .transition(.bipeMove(edge: .bottom))
                    .task(id: message) {
                        try? await Task.sleep(for: .seconds(3))
                        withBipeAnimation { self.message = nil }
                    }
            }
        }
        .bipeAnimation(.default, value: message)
    }
}

extension View {
    func toast(_ message: Binding<String?>) -> some View {
        modifier(ToastModifier(message: message))
    }
}

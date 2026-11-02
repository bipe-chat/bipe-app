// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec notifications-prompt: the entry notifications of chat-list-prompt (bipe-chat/bipe#894). This file keeps its
/// memory on this phone; the chat list shows the card (AccountAsksModel).
enum NotificationsPromptMemory {
    private static let shownKey = "notificationsPromptShown"
    private static func sentKey(_ userID: String) -> String { "bipe.notificationsPrompt.messageSent.\(userID)" }

    /// True after Ligar notificações or Agora não. The card never comes back on this phone.
    static var isDone: Bool { UserDefaults.standard.bool(forKey: shownKey) }

    static func markDone() { UserDefaults.standard.set(true, forKey: shownKey) }

    /// Trigger messageSent: this account sent a message on this phone.
    static func messageSent(userID: String) -> Bool { UserDefaults.standard.bool(forKey: sentKey(userID)) }

    static func markMessageSent(userID: String) { UserDefaults.standard.set(true, forKey: sentKey(userID)) }
}

extension View {
    /// Trigger messageSent (registries/onboarding.json): an own message shows as sent in this chat.
    func notificationsPromptTrigger(userID: String, messages: [TimelineMessage]) -> some View {
        modifier(NotificationsPromptTrigger(userID: userID, messages: messages))
    }
}

private struct NotificationsPromptTrigger: ViewModifier {
    let userID: String
    let messages: [TimelineMessage]
    @State private var openedAt = Date.now

    /// Own messages sent since the chat opened: history does not count.
    private var sentSinceOpen: Bool {
        messages.contains { $0.isOwn && $0.sendState == .sent && $0.sentAt >= openedAt }
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: sentSinceOpen) { _, sent in
                if sent { NotificationsPromptMemory.markMessageSent(userID: userID) }
            }
    }
}

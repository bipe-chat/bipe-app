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

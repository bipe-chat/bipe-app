// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

@main
struct BipeApp: App {
    /// Push token and notification taps (spec notification-service).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    /// Settings chats.appearance (bipe#676).
    @AppStorage(Appearance.key) private var appearance = Appearance.system.rawValue

    init() {
        // Before any keychain read: the items move into the app-group group (bipe-chat/bipe#531).
        KeychainMove.run()
        // Spec app-colors: the stored palette before the first view.
        AppTheme.load()
        RoundedType.applyToBars()
    }

    var body: some Scene {
        WindowGroup {
            AccountStartView()
                .modifier(AppLockGate())
                .bipeRoundedType()
                .preferredColorScheme(Appearance(rawValue: appearance)?.colorScheme)
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

@main
struct BipeApp: App {
    /// Push token and notification taps (spec notification-service).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    /// Settings chats.appearance (bipe#676).
    @AppStorage(Appearance.key) private var appearance = Appearance.system.rawValue
    /// The signed lists fetch from the core on each foreground (bipe-chat/bipe#645, docs/signed-lists.md).
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Before any SDK call (bipe-chat/bipe#1122): SDK warnings, errors and panics. Only developer builds write them,
        // to the console; a release build writes no SDK log. The SDK never logs message text or tokens at this level.
        try? initPlatform(config: TracingConfiguration(logLevel: .warn, traceLogPacks: [], extraTargets: [],
                                                       writeToStdoutOrSystem: AppConfig.isDeveloperBuild,
                                                       writeToFiles: nil, sentryConfig: nil),
                          useLightweightTokioRuntime: false)
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
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { SignedLists.shared.appBecameActive() }
        }
    }
}

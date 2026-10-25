// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Build configuration of the tester app (ADR 0147). The user never picks a server (spec account-start).
/// Also compiled into the notification service extension (project.yml): keep it free of app-only types.
enum AppConfig {
    /// Bipe's homeserver (bipe/docs/server-migration.md).
    static let homeserver = "https://matrix.bipe.net.br"

    /// The Bipe API (bipe-api, bipe/docs/server-migration.md). Contract: bipe-shared/api/openapi.yaml.
    static let apiBaseURL = URL(string: "https://api.bipe.net.br")!

    /// The public site with the legal pages (PUBLIC_SITE_URL, registries/legal.json).
    static let publicSite = URL(string: "https://bipe.chat")!

    /// The bundle ID of the app (project.yml BIPE_APP_ID, bipe-chat/bipe#602): chat.bipe.app.next for tester builds,
    /// the fork's ID for the store set at the switch. Each target's Info.plist carries it.
    static let appID = infoValue("BipeAppID", fallback: "chat.bipe.app.next")

    /// The app group (project.yml BIPE_APP_GROUP). The SDK store and caches live there.
    static let appGroup = infoValue("BipeAppGroup", fallback: "group.chat.bipe.next")

    /// The keychain access group in which the fork's tester build keeps the device account (ADR 0147).
    /// The app only reads from it. Team 992N457T8D (bipe/docs/server-migration.md), app ID chat.bipe.app.dev.
    static let forkKeychainGroup = "992N457T8D.chat.bipe.app.dev"

    /// The device name the homeserver shows in the session list.
    static let deviceName = "Bipe"

    /// Bipe's push gateway (Sygnal, bipe/docs/server-migration.md). Spec push-registration notes.
    static let pushGatewayURL = "https://push.bipe.net.br/_matrix/push/v1/notify"

    /// The Sygnal app ID of this build: the bundle ID plus `.ios.dev` for the APNs sandbox (Xcode debug
    /// builds) or `.ios.prod` for production APNs (TestFlight and ad hoc), as bipe/docs/platform-parity.md.
    /// Xcode debug builds. Developer-only paths (the beta sign-in of recovery-start) show only here.
    #if DEBUG
    static let isDeveloperBuild = true
    #else
    static let isDeveloperBuild = false
    #endif

    #if DEBUG
    static let pusherAppId = appID + ".ios.dev"
    #else
    static let pusherAppId = appID + ".ios.prod"
    #endif

    /// An expanded Info.plist value; the fallback when the key is missing or unexpanded.
    private static func infoValue(_ key: String, fallback: String) -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              !value.isEmpty, !value.contains("$(") else { return fallback }
        return value
    }
}

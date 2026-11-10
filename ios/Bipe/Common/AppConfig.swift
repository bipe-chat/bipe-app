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

    /// The MAS issuer of Bipe's homeserver.
    static let oauthIssuer = "https://account.bipe.net.br/"

    /// The static OAuth client of the iPhone app (bipe-chat/bipe#914). MAS closes dynamic registration, so the QR
    /// sign-in starts only from a static client. Same ID as bipe-infra ess/bipe-security.yaml.
    static let oauthClientID = "01M70XQV1WHH63433MNSE34SXX"

    /// The bundle ID of the app (project.yml BIPE_APP_ID, bipe-chat/bipe#602): chat.bipe.app.next for tester builds,
    /// the fork's ID for the store set at the switch. Each target's Info.plist carries it.
    static let appID = infoValue("BipeAppID", fallback: "chat.bipe.app.next")

    /// The app group (project.yml BIPE_APP_GROUP). The SDK store and caches live there.
    static let appGroup = infoValue("BipeAppGroup", fallback: "group.chat.bipe.next")

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

    /// Trust step 5, Verificar com gov.br (spec govbr-verify, bipe-chat/bipe#544): a build or install value, never a stored
    /// or remote one. On in Xcode debug builds of the tester ID set and in TestFlight installs (MZ, 3 November 2026: test
    /// the real loop before the bipe-chat/bipe#17 review). Off in the App Store until bipe-chat/bipe#17 closes.
    #if DEBUG
    static let govbrEnabled = AppConfig.isDeveloperBuild && appID != "chat.bipe.app"
    #else
    static let govbrEnabled = isTestFlight
    #endif

    /// True when TestFlight installed this Release build: its receipt is the sandbox one. The App Store gets the same binary.
    static let isTestFlight = Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"

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

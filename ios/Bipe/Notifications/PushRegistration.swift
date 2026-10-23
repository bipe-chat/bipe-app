// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UIKit
import UserNotifications

/// Spec push-registration (bipe-shared product/screens/push-registration.json). No screen: it registers this
/// phone with Bipe's Sygnal when the person allows notifications, and removes the pusher at sign-out.
@MainActor
final class PushRegistration: ObservableObject {
    enum Permission: Equatable {
        case notAsked
        case allowed
        case denied
    }

    /// Type PushRegistration, saved so a new token or language can be compared with it.
    struct Registration: Codable, Equatable {
        var userId: String
        var pushkey: String
        var appId: String
        var lang: String
        var registeredAt: Date
    }

    static let shared = PushRegistration()

    @Published private(set) var permission: Permission = .notAsked
    @Published private(set) var registration: Registration?

    private var session: AccountSession?
    private static let registrationKey = "pushRegistration"

    private init() {
        registration = Self.loadRegistration()
    }

    /// The account is ready: keep its pusher current.
    func attach(_ session: AccountSession) {
        self.session = session
        Task { await appBecameActive() }
    }

    /// Action appBecameActive: reads the permission and asks for a token when allowed. A denied phone keeps
    /// its pusher, so notifications come back when the person allows them again.
    func appBecameActive() async {
        permission = await Self.systemPermission()
        if permission == .allowed {
            // iOS hands the token to the app delegate; tokenReceived compares it with the registration.
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    /// Opens the system permission dialog. Only notifications-prompt and notification-settings call this.
    @discardableResult
    func requestPermission() async -> Bool {
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        await appBecameActive()
        return granted
    }

    /// Action tokenReceived: sets the HTTP pusher when the token, the account or the language changed.
    func tokenReceived(_ token: Data) async {
        guard permission == .allowed, let session else { return }
        let pushkey = token.map { String(format: "%02x", $0) }.joined()
        let lang = Self.appLanguage
        if let registration, registration.userId == session.userId, registration.pushkey == pushkey,
           registration.appId == AppConfig.pusherAppId, registration.lang == lang {
            return
        }
        do {
            // Content-free: event_id_only. mutable-content makes iOS run the notification service extension
            // for every push; the alert shows only if the extension cannot run.
            let defaultPayload: [String: Any] = [
                "aps": [
                    "mutable-content": 1,
                    "alert": ["title": BipeStrings.appName, "body": BipeStrings.appNotificationNewMessage],
                ],
            ]
            let payload = try JSONSerialization.data(withJSONObject: defaultPayload)
            try await session.client.setPusher(
                identifiers: PusherIdentifiers(pushkey: pushkey, appId: AppConfig.pusherAppId),
                kind: .http(data: HttpPusherData(url: AppConfig.pushGatewayURL,
                                                 format: .eventIdOnly,
                                                 defaultPayload: String(decoding: payload, as: UTF8.self))),
                appDisplayName: BipeStrings.appName,
                deviceDisplayName: UIDevice.current.model,
                profileTag: nil,
                lang: lang,
                append: false
            )
            save(Registration(userId: session.userId, pushkey: pushkey, appId: AppConfig.pusherAppId,
                              lang: lang, registeredAt: .now))
        } catch {
            NSLog("Bipe pusher registration failed")
        }
    }

    /// Action signOut: deletes the pusher of this phone before the session ends. A failure does not stop the sign-out.
    func signOut() async {
        if let session, let registration {
            try? await session.client.deletePusher(
                identifiers: PusherIdentifiers(pushkey: registration.pushkey, appId: registration.appId))
        }
        save(nil)
        session = nil
    }

    /// pt-BR or en, the languages of the generated strings.
    private static var appLanguage: String {
        Bundle.main.preferredLocalizations.first?.hasPrefix("en") == true ? "en" : "pt-BR"
    }

    private static func systemPermission() async -> Permission {
        switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
        case .notDetermined: .notAsked
        case .denied: .denied
        default: .allowed
        }
    }

    private static func loadRegistration() -> Registration? {
        UserDefaults.standard.data(forKey: registrationKey).flatMap { try? JSONDecoder().decode(Registration.self, from: $0) }
    }

    private func save(_ registration: Registration?) {
        self.registration = registration
        if let registration, let data = try? JSONEncoder().encode(registration) {
            UserDefaults.standard.set(data, forKey: Self.registrationKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.registrationKey)
        }
    }
}

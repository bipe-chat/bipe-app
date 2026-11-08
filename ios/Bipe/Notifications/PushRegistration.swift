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
        var ownerVersion: Int? = nil
        var accountRoute: String? = nil
    }

    static let shared = PushRegistration()

    @Published private(set) var permission: Permission = .notAsked
    @Published private(set) var registration: Registration?

    private var session: AccountSession?
    /// Spec interno notes, Push: the member session under the current account, with its own pusher (ADR 0284 point 21).
    private var member: AccountSession?
    /// The pushkey of the member pusher set in this process. A pusher set with append false for another user removes
    /// the member pusher on the server, so it is set again after each one.
    private var memberPushkey: String?
    private static let registrationKey = "pushRegistration"

    private init() {
        registration = Self.loadRegistration()
    }

    /// The account is ready: keep its pusher current.
    func attach(_ session: AccountSession) {
        self.session = session
        member = InternoSessions.shared.session(forWork: session.userId)
        memberPushkey = nil
        Task { await appBecameActive() }
    }

    /// The account the pusher belongs to now.
    var attachedUserId: String? { session?.userId }

    /// Spec interno: the member session of the current account came or went.
    func attachMember(_ member: AccountSession?) {
        guard self.member !== member else { return }
        self.member = member
        memberPushkey = nil
        Task { await appBecameActive() }
    }

    /// Removes the member pusher before the member session signs out. A failure does not stop the sign-out.
    func detachMember(_ member: AccountSession) async {
        guard self.member === member else { return }
        let pushkey = memberPushkey
        self.member = nil
        memberPushkey = nil
        if let pushkey {
            try? await member.client.deletePusher(identifiers: PusherIdentifiers(pushkey: pushkey, appId: AppConfig.pusherAppId))
        }
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
        guard permission == .allowed, let session,
              let accountRoutes = NotificationKeys.accountRoutes else { return }
        let route = accountRoutes.route(for: session.userId)
        let pushkey = token.map { String(format: "%02x", $0) }.joined()
        let lang = Self.appLanguage
        if let registration, registration.userId == session.userId, registration.pushkey == pushkey,
           registration.appId == AppConfig.pusherAppId, registration.lang == lang, registration.ownerVersion == 2, registration.accountRoute == route {
            await registerMember(pushkey: pushkey, lang: lang, routes: accountRoutes)
            return
        }
        do {
            try await session.client.setPusher(
                identifiers: PusherIdentifiers(pushkey: pushkey, appId: AppConfig.pusherAppId),
                kind: .http(data: Self.pusherData(route: route)),
                appDisplayName: BipeStrings.appName,
                deviceDisplayName: UIDevice.current.model,
                profileTag: nil,
                lang: lang,
                append: false
            )
            guard self.session === session else { return }
            // append false removed the member pusher on the server too.
            memberPushkey = nil
            save(Registration(userId: session.userId, pushkey: pushkey, appId: AppConfig.pusherAppId,
                              lang: lang, registeredAt: .now, ownerVersion: 2, accountRoute: route))
            await registerMember(pushkey: pushkey, lang: lang, routes: accountRoutes)
        } catch {
            NSLog("Bipe pusher registration failed")
        }
    }

    /// Spec interno notes, Push: the member session's own pusher, next to the account's (append true), with its own
    /// random route. Set once per process and after each account pusher.
    private func registerMember(pushkey: String, lang: String, routes: BipePushAccountRoutes) async {
        guard let member, memberPushkey != pushkey,
              let data = try? Self.pusherData(route: routes.route(for: member.userId)) else { return }
        do {
            try await member.client.setPusher(
                identifiers: PusherIdentifiers(pushkey: pushkey, appId: AppConfig.pusherAppId),
                kind: .http(data: data),
                appDisplayName: BipeStrings.appName,
                deviceDisplayName: UIDevice.current.model,
                profileTag: nil,
                lang: lang,
                append: true
            )
            if self.member === member { memberPushkey = pushkey }
        } catch {
            NSLog("Bipe member pusher registration failed")
        }
    }

    /// Content-free: event_id_only. mutable-content makes iOS run the notification service extension for every push;
    /// the alert shows only if the extension cannot run or gives back empty content. Passive, so that alert never rings
    /// (ADR 0254). The extension sets the level of what it shows.
    private static func pusherData(route: String) throws -> HttpPusherData {
        let defaultPayload: [String: Any] = [
            NotificationKeys.accountRoute: route,
            "aps": [
                "mutable-content": 1,
                "interruption-level": "passive",
                "alert": ["title": BipeStrings.appName, "body": BipeStrings.appNotificationNewMessage],
            ],
        ]
        let payload = try JSONSerialization.data(withJSONObject: defaultPayload)
        return HttpPusherData(url: AppConfig.pushGatewayURL, format: .eventIdOnly,
                              defaultPayload: String(decoding: payload, as: UTF8.self))
    }

    /// Action signOut: deletes the pusher of this phone before the session ends. A failure does not stop the sign-out.
    func signOut() async {
        let owner = session
        let saved = registration
        member = nil
        memberPushkey = nil
        session = nil
        save(nil)
        if let owner, let saved, saved.userId == owner.userId {
            try? await owner.client.deletePusher(
                identifiers: PusherIdentifiers(pushkey: saved.pushkey, appId: saved.appId))
        }
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

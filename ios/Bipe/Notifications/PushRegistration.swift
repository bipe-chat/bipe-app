// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UIKit
import UserNotifications

/// Spec push-registration (bipe-shared product/screens/push-registration.json). No screen: it registers this
/// phone with Bipe's Sygnal when the person allows notifications, with one pusher for each session on the phone (every
/// account and every Interno member session), and removes a session's pusher when that session signs out.
@MainActor
final class PushRegistration: ObservableObject {
    enum Permission: Equatable {
        case notAsked
        case allowed
        case denied
    }

    /// Type PushRegistration, saved per Matrix ID so a new token, language or route can be compared with it.
    struct Registration: Codable, Equatable {
        var userId: String
        var pushkey: String
        var appId: String
        var lang: String
        var registeredAt: Date
        var accountRoute: String? = nil

        /// The same pusher on the server: only registeredAt may differ.
        func isSamePusher(as other: Registration) -> Bool {
            userId == other.userId && pushkey == other.pushkey && appId == other.appId && lang == other.lang
                && accountRoute == other.accountRoute
        }
    }

    static let shared = PushRegistration()

    @Published private(set) var permission: Permission = .notAsked
    /// State registrations: the pushers this phone set, by Matrix ID.
    @Published private(set) var registrations: [String: Registration]

    /// Every session on the phone that gets a pusher, by Matrix ID: the accounts and the Interno member sessions.
    private var sessions: [String: AccountSession] = [:]
    /// The pushers set in this process, by Matrix ID. The saved registration alone does not prove the pusher is on the
    /// server: Synapse deletes a pusher when Sygnal rejects its token. So each launch sets each pusher once again.
    private var setInProcess: [String: Registration] = [:]
    /// The newest token while a registration runs; the run takes it next, so two runs never interleave.
    private var queuedToken: Data?
    private var registering = false
    private static let registrationsKey = "pushRegistrations"
    /// The single registration of builds before bipe-chat/bipe#1519. Read once, then replaced by registrationsKey.
    private static let legacyRegistrationKey = "pushRegistration"

    private init() {
        registrations = Self.loadRegistrations()
    }

    /// Action sessionAdded: an account or an Interno member session opened, or was built again. It gets its pusher.
    func add(_ session: AccountSession) {
        sessions[session.userId] = session
        setInProcess[session.userId] = nil
        Task { await appBecameActive() }
    }

    /// The pusher of this account on this phone, if it was set.
    func registration(for userId: String) -> Registration? {
        registrations[userId]
    }

    /// Action signOut: deletes the session's pusher before the session ends. The other sessions keep theirs. A failure
    /// does not stop the sign-out.
    func remove(userId: String) async {
        let session = sessions.removeValue(forKey: userId)
        setInProcess[userId] = nil
        let saved = registrations[userId]
        save(nil, for: userId)
        if let session, let saved {
            try? await session.client.deletePusher(identifiers: PusherIdentifiers(pushkey: saved.pushkey, appId: saved.appId))
        }
    }

    /// Action appBecameActive: reads the permission and asks for a token when allowed. A denied phone keeps
    /// its pushers, so notifications come back when the person allows them again.
    func appBecameActive() async {
        permission = await Self.systemPermission()
        if permission == .allowed {
            // iOS hands the token to the app delegate; tokenReceived compares it with the registrations.
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

    /// Action tokenReceived: sets the pusher of each session whose token, language or route changed, or that this
    /// process has not set yet.
    func tokenReceived(_ token: Data) async {
        queuedToken = token
        guard !registering else { return }
        registering = true
        defer { registering = false }
        while let next = queuedToken {
            queuedToken = nil
            await register(token: next)
        }
    }

    private func register(token: Data) async {
        guard permission == .allowed, let accountRoutes = NotificationKeys.accountRoutes else { return }
        let pushkey = token.map { String(format: "%02x", $0) }.joined()
        let appId = AppConfig.pusherAppId
        let lang = Self.appLanguage
        // append true keeps the pushers of the other sessions with this token (bipe-chat/bipe#1519). Only the first
        // pusher on a phone with no saved registration (a new install, or every account signed out) uses append false,
        // once: it removes pushers that an earlier install left with this token.
        var replaceOthers = registrations.isEmpty
        for userId in sessions.keys.sorted() {
            guard let session = sessions[userId] else { continue }
            let route = accountRoutes.route(for: userId)
            guard let data = try? Self.pusherData(route: route) else { continue }
            let wanted = Registration(userId: userId, pushkey: pushkey, appId: appId, lang: lang, registeredAt: .now,
                                      accountRoute: route)
            if let done = setInProcess[userId], done.isSamePusher(as: wanted) { continue }
            do {
                try await session.client.setPusher(
                    identifiers: PusherIdentifiers(pushkey: pushkey, appId: appId),
                    kind: .http(data: data),
                    appDisplayName: BipeStrings.appName,
                    deviceDisplayName: UIDevice.current.model,
                    profileTag: nil,
                    lang: lang,
                    append: !replaceOthers
                )
            } catch {
                NSLog("Bipe pusher registration failed")
                continue
            }
            replaceOthers = false
            // The session signed out or was built again while the pusher was set.
            guard sessions[userId] === session else { continue }
            let old = registrations[userId]
            setInProcess[userId] = wanted
            save(wanted, for: userId)
            // A new token or app ID is a new pusher: the one with the old token goes.
            if let old, old.pushkey != pushkey || old.appId != appId {
                try? await session.client.deletePusher(identifiers: PusherIdentifiers(pushkey: old.pushkey, appId: old.appId))
            }
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

    private static func loadRegistrations() -> [String: Registration] {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: registrationsKey),
           let saved = try? JSONDecoder().decode([String: Registration].self, from: data) {
            return saved
        }
        guard let data = defaults.data(forKey: legacyRegistrationKey),
              let legacy = try? JSONDecoder().decode(Registration.self, from: data) else { return [:] }
        return [legacy.userId: legacy]
    }

    private func save(_ registration: Registration?, for userId: String) {
        registrations[userId] = registration
        let defaults = UserDefaults.standard
        if let data = try? JSONEncoder().encode(registrations) {
            defaults.set(data, forKey: Self.registrationsKey)
        }
        defaults.removeObject(forKey: Self.legacyRegistrationKey)
    }
}

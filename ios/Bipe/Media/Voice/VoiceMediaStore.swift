// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// State media expired of spec voice-message (ADR 0164): media URLs whose download answered 404 in this session.
/// Media URLs are unique, so one set serves every account and chat.
@MainActor
final class VoiceMediaExpiry: ObservableObject {
    static let shared = VoiceMediaExpiry()

    @Published private(set) var expired: Set<String> = []

    func markExpired(_ url: String) {
        expired.insert(url)
    }

    func isExpired(_ voice: VoiceMessage) -> Bool {
        expired.contains(voice.source.url)
    }
}

/// Spec voice-message, actions register, keep, unkeep and keepScam (ADR 0164, bipe-chat/bipe#622). One store per
/// account. The server deletes a registered voice upload after its time unless an account keeps it.
@MainActor
final class VoiceMediaStore: ObservableObject {
    private static var stores: [String: VoiceMediaStore] = [:]
    private static let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)
    /// The newest registered media IDs kept on the phone. Older ones are not sent again either: their rows are long sent.
    private static let registeredLimit = 2_000

    static func store(for session: AccountSession) -> VoiceMediaStore {
        if let store = stores[session.userId] { return store }
        let store = VoiceMediaStore(session: session)
        stores[session.userId] = store
        return store
    }

    enum KeepResult: Equatable {
        case done
        case limit
        case failed
    }

    /// State kept: mxc URI to reason.
    @Published private(set) var kept: [String: BipeKeptMedia.Reason] = [:]

    private let session: AccountSession
    private var loaded = false
    private var loading = false
    private var registering: Set<String> = []
    private var scamTried: Set<String> = []

    private init(session: AccountSession) {
        self.session = session
    }

    private var registeredKey: String { "bipe.voiceMedia.registered.\(session.userId)" }

    /// The server name and media ID of an `mxc://<server>/<mediaId>` URI.
    static func parts(of url: String) -> (server: String, mediaId: String)? {
        guard url.hasPrefix("mxc://") else { return nil }
        let rest = url.dropFirst("mxc://".count).split(separator: "/", omittingEmptySubsequences: false)
        guard rest.count == 2, !rest[0].isEmpty, !rest[1].isEmpty else { return nil }
        return (String(rest[0]), String(rest[1]))
    }

    /// GET /v1/me/kept-media once per session; again after a failure.
    func load() async {
        guard !loaded, !loading else { return }
        loading = true
        defer { loading = false }
        let api = Self.api
        guard let list = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, { (token: String) async throws(BipeAPIError) -> [BipeKeptMedia] in
            try await api.keptMedia(accessToken: token)
        }) else { return }
        kept = Dictionary(list.map { ($0.mediaId, $0.reason) }, uniquingKeysWith: { first, _ in first })
        loaded = true
    }

    /// Action register: an own voice message whose event is sent. Once per media ID on this phone.
    func register(url: String, transcript: Bool) async {
        guard let parts = Self.parts(of: url), !registering.contains(url) else { return }
        var registered = UserDefaults.standard.stringArray(forKey: registeredKey) ?? []
        guard !registered.contains(url) else { return }
        registering.insert(url)
        defer { registering.remove(url) }
        let api = Self.api
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.registerVoiceMedia(server: parts.server, mediaId: parts.mediaId, transcript: transcript, accessToken: token)
            }
        } catch .server(.notFound, _, _) {
            // Not this account's upload on this server: it can never register. Do not try again.
        } catch {
            return
        }
        registered = (UserDefaults.standard.stringArray(forKey: registeredKey) ?? []) + [url]
        UserDefaults.standard.set(Array(registered.suffix(Self.registeredLimit)), forKey: registeredKey)
    }

    /// Actions keep and keepScam.
    func keep(url: String, reason: BipeKeptMedia.Reason) async -> KeepResult {
        guard let parts = Self.parts(of: url) else { return .failed }
        let api = Self.api
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.keepMedia(server: parts.server, mediaId: parts.mediaId, reason: reason, accessToken: token)
            }
            kept[url] = reason
            return .done
        } catch .server(.keepLimit, _, _) {
            return .limit
        } catch {
            return .failed
        }
    }

    /// Action keepScam: once per media ID in the session, and only when the audio is not kept yet.
    func keepForScam(url: String) async {
        await load()
        guard loaded, kept[url] == nil, !scamTried.contains(url) else { return }
        scamTried.insert(url)
        _ = await keep(url: url, reason: .scam)
    }

    /// Action unkeep.
    func unkeep(url: String) async -> Bool {
        guard let parts = Self.parts(of: url) else { return false }
        let api = Self.api
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.unkeepMedia(server: parts.server, mediaId: parts.mediaId, accessToken: token)
            }
            kept[url] = nil
            return true
        } catch {
            return false
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec group-privacy publicFeedHandle and setPublicFeed (bipe ADR 0280 point 7c, bipe-chat/bipe#1260): the handle
/// owner turns on or off the public feed that staff linked to this group. Nobody else sees the row.
@MainActor
final class GroupPublicFeedModel: ObservableObject {
    enum Phase: Equatable {
        case hidden
        case shown(handle: String, enabled: Bool)
        case working(handle: String, enabled: Bool)
    }

    @Published private(set) var phase: Phase = .hidden
    @Published var failed = false

    private let session: AccountSession
    private let roomId: String
    private let api: BipeAPIClient

    init(session: AccountSession, roomId: String, api: BipeAPIClient = BipeAPIClient(baseURL: AppConfig.apiBaseURL)) {
        self.session = session
        self.roomId = roomId
        self.api = api
    }

    /// Reads GET /v1/me/feed. Any failure, or a feed of another group, hides the row.
    func appear() async {
        let feed = try? await withToken { (token: String) async throws(BipeAPIError) -> BipeMyFeed in
            try await self.api.myFeed(accessToken: token)
        }
        if let feed, feed.roomId == roomId {
            phase = .shown(handle: feed.handle, enabled: feed.enabled)
        } else {
            phase = .hidden
        }
    }

    /// Action setPublicFeed. A second tap while it runs does nothing; a failure keeps the old value.
    func set(_ enabled: Bool) async {
        guard case let .shown(handle, current) = phase, enabled != current else { return }
        phase = .working(handle: handle, enabled: enabled)
        do {
            let feed = try await withToken { (token: String) async throws(BipeAPIError) -> BipeMyFeed in
                try await self.api.setMyFeed(enabled: enabled, accessToken: token)
            }
            phase = .shown(handle: feed.handle, enabled: feed.enabled)
        } catch {
            phase = .shown(handle: handle, enabled: current)
            failed = true
        }
    }

    private func withToken<T>(_ operation: @MainActor (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, operation)
    }
}

/// The section of group-privacy for the public feed. Empty while the row is hidden.
struct GroupPublicFeedSection: View {
    @ObservedObject var model: GroupPublicFeedModel

    var body: some View {
        switch model.phase {
        case .hidden:
            EmptyView()
        case let .shown(handle, enabled), let .working(handle, enabled):
            Section {
                Toggle(BipeStrings.groupPublicFeedTitle(handle), isOn: Binding(
                    get: { enabled },
                    set: { value in Task { await model.set(value) } }
                ))
                .disabled(model.phase == .working(handle: handle, enabled: enabled))
            } footer: {
                Text(BipeStrings.groupPublicFeedFooter(handle))
            }
        }
    }
}

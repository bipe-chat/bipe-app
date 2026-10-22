// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec support-open: opens the user's chat with Suporte Bipe (ADR 0114).
@MainActor
final class SupportOpenModel: ObservableObject {
    enum Phase: Equatable {
        case opening
        case failed(String)
        case open(roomId: String)
    }

    @Published private(set) var phase: Phase = .opening
    private let session: AccountSession
    private let api: BipeAPIClient
    private var running = false

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
    }

    /// Action open: POST /v1/me/support, join the room when needed, then chat-timeline. Only one open runs at a time.
    func open() async {
        guard !running else { return }
        running = true
        defer { running = false }
        phase = .opening
        let api = api
        do {
            let roomId = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> String in
                try await api.supportRoomID(accessToken: token)
            }
            if try session.client.getRoom(roomId: roomId)?.membership() != .joined {
                _ = try await session.client.joinRoomById(roomId: roomId)
            }
            phase = .open(roomId: roomId)
        } catch let error as BipeAPIError {
            phase = .failed(Self.message(for: error))
        } catch {
            phase = .failed(BipeStrings.supportOpenFailed)
        }
    }

    /// Action retry.
    func retry() async {
        await open()
    }

    private static func message(for error: BipeAPIError) -> String {
        switch error {
        case .network: BipeStrings.errorNetwork
        case .server(.rateLimited, _, _): BipeStrings.errorRateLimited
        default: BipeStrings.supportOpenFailed
        }
    }
}

struct SupportOpenView: View {
    @StateObject private var model: SupportOpenModel
    private let session: AccountSession
    @Environment(\.dismiss) private var dismiss

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        _model = StateObject(wrappedValue: SupportOpenModel(session: session, api: api))
    }

    var body: some View {
        Group {
            switch model.phase {
            case .open(let roomId):
                ChatTimelineView(session: session, media: MediaLoader(client: session.client), roomId: roomId)
            case .opening:
                VStack(spacing: 16) {
                    ProgressView()
                    Text(BipeStrings.appSupportOpening)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
            case .failed(let message):
                VStack(spacing: 16) {
                    PhosphorIcon.warningCircle.image
                        .resizable()
                        .frame(width: 48, height: 48)
                        .foregroundStyle(BipeColor.accentInk)
                        .accessibilityHidden(true)
                    Text(message)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Button {
                        Task { await model.retry() }
                    } label: {
                        Text(BipeStrings.appRetry)
                    }
                    .buttonStyle(.borderedProminent)
                    Button(role: .cancel) {
                        dismiss()
                    } label: {
                        Text(BipeStrings.appCancel)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
            }
        }
        .accessibilityIdentifier(BipeScreen.supportOpen.rawValue)
        .task { await model.open() }
    }
}

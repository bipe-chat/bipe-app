// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec signup-done (bipe-shared product/screens/signup-done.json).
@MainActor
final class SignupDoneModel: ObservableObject {
    enum Phase: Equatable {
        case shown
        case openingChat
    }

    @Published private(set) var phase: Phase = .shown
    let inviterUserId: String?
    let inviterHandle: String?
    private let session: AccountSession
    private var continued = false

    init(session: AccountSession, outcome: SignupOutcome) {
        self.session = session
        inviterUserId = outcome.inviterUserId
        inviterHandle = outcome.inviterHandle
    }

    /// Action appear: the success line for a short moment, then continue.
    func appear() async -> String?? {
        try? await Task.sleep(for: .seconds(1.5))
        return await continueFlow()
    }

    /// Action continue. Returns the room to open (`.some(nil)`: the chat list), or `nil` when it already ran.
    func continueFlow() async -> String?? {
        guard !continued else { return nil }
        continued = true
        guard let inviterUserId else { return .some(nil) }
        phase = .openingChat
        return .some(await directChat(with: inviterUserId))
    }

    /// The DM with the inviter: the existing one, else a new encrypted one that invites them. Never a second DM.
    private func directChat(with userId: String) async -> String? {
        let client = session.client
        if let room = try? client.getDmRoom(userId: userId) {
            return room.id()
        }
        let parameters = CreateRoomParameters(name: nil,
                                              isEncrypted: true,
                                              isDirect: true,
                                              visibility: .private,
                                              preset: .trustedPrivateChat,
                                              invite: [userId])
        guard let roomId = try? await client.createRoom(request: parameters) else { return nil }
        _ = try? await client.awaitRoomRemoteEcho(roomId: roomId)
        return roomId
    }
}

struct SignupDoneView: View {
    @StateObject private var model: SignupDoneModel
    /// The room to open: a chat ID, or `nil` for the chat list.
    let onContinue: (String?) -> Void

    init(session: AccountSession, outcome: SignupOutcome, onContinue: @escaping (String?) -> Void) {
        _model = StateObject(wrappedValue: SignupDoneModel(session: session, outcome: outcome))
        self.onContinue = onContinue
    }

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            PhosphorIcon.chatCircle.image
                .resizable()
                .frame(width: 56, height: 56)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(BipeLocalized.string(BipeOnboarding.createdKey))
                .font(.title2.bold())
                .multilineTextAlignment(.center)
                .foregroundStyle(BipeColor.inkPrimary)
            if model.phase == .openingChat {
                ProgressView()
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .contentShape(Rectangle())
        .onTapGesture {
            Task { finish(await model.continueFlow()) }
        }
        // A tap anywhere goes on; VoiceOver reads the screen as one button (bipe#495).
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { Task { finish(await model.continueFlow()) } }
        .task { finish(await model.appear()) }
        .accessibilityIdentifier(BipeScreen.signupDone.rawValue)
    }

    private func finish(_ result: String??) {
        guard let roomId = result else { return }
        onContinue(roomId)
    }
}

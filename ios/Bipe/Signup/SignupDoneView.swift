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
    /// Spec group-link-open: the group of a group link code, joined before the chat list shows.
    let groupRoomId: String?
    private let session: AccountSession
    private var continued = false

    init(session: AccountSession, outcome: SignupOutcome) {
        self.session = session
        inviterUserId = outcome.inviterUserId
        inviterHandle = outcome.inviterHandle
        groupRoomId = outcome.groupRoomId
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
        if let groupRoomId {
            // The groups service account invited the new account at sign-up (ADR 0184). The group opens first.
            phase = .openingChat
            if (try? await session.client.joinRoomById(roomId: groupRoomId)) != nil { return .some(groupRoomId) }
        }
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
    /// The check badge pops in once the screen shows.
    @State private var shown = false

    init(session: AccountSession, outcome: SignupOutcome, onContinue: @escaping (String?) -> Void) {
        _model = StateObject(wrappedValue: SignupDoneModel(session: session, outcome: outcome))
        self.onContinue = onContinue
    }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(BipeColor.accentWash)
                    .frame(width: 140, height: 140)
                    .overlay {
                        BipeMark()
                            .fill(LinearGradient(colors: [BipeColor.accentFill, BipeColor.accentFillPressed],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 76, height: 76)
                            .shadow(color: BipeColor.accentFill.opacity(0.3), radius: 12, y: 6)
                    }
                PhosphorIcon.check.image
                    .resizable()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(BipeColor.inkOnAccent)
                    .frame(width: 44, height: 44)
                    .background(BipeColor.statusSuccess, in: Circle())
                    .overlay(Circle().strokeBorder(BipeColor.surfaceBase, lineWidth: 4))
                    .offset(x: -4, y: -4)
                    .scaleEffect(shown ? 1 : 0.3)
                    .opacity(shown ? 1 : 0)
            }
            .accessibilityHidden(true)
            Text(BipeLocalized.string(BipeOnboarding.createdKey))
                .font(.largeTitle.bold())
                .multilineTextAlignment(.center)
                .foregroundStyle(BipeColor.inkPrimary)
            if model.phase == .openingChat {
                ProgressView().tint(BipeColor.accentFill)
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OnboardingBackdrop())
        .onAppear { withBipeAnimation(.spring(duration: 0.45, bounce: 0.45).delay(0.15)) { shown = true } }
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

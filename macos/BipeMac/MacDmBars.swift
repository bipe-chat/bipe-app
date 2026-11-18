// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Client.ignoredUsers of one account (spec block-person), kept current by Client.subscribeToIgnoredUsers, as in
/// iOS PeopleService. Every chat of the account reads it.
@MainActor
final class MacBlockedUsers: ObservableObject {
    @Published private(set) var ignored: Set<String> = []
    private var handle: TaskHandle?
    private var task: Task<Void, Never>?
    private weak var client: Client?

    func attach(_ client: Client) {
        reset()
        self.client = client
        let stream = IgnoredUsersStream()
        handle = client.subscribeToIgnoredUsers(listener: stream)
        task = Task { [weak self] in
            if let current = try? await client.ignoredUsers() { self?.ignored = Set(current) }
            for await ids in stream.updates { self?.ignored = Set(ids) }
        }
    }

    func reset() {
        task?.cancel(); task = nil
        handle?.cancel(); handle = nil
        client = nil
        ignored = []
    }

    func isBlocked(_ userID: String) -> Bool { ignored.contains(userID) }

    /// Spec block-person confirm in unblock mode: Client.unignoreUser. The subscription then updates every chat.
    func unblock(_ userID: String) async throws {
        guard let client else { throw CancellationError() }
        try await client.unignoreUser(userId: userID)
        ignored.remove(userID)
    }

    /// Spec block-person confirm in block mode: Client.ignoreUser. The DM stays, with the block bar (ADR 0141).
    func block(_ userID: String) async throws {
        guard let client else { throw CancellationError() }
        try await client.ignoreUser(userId: userID)
        ignored.insert(userID)
    }
}

/// Spec block-person: the confirm sheet, in block mode or in unblock mode. `close` runs after a success or a cancel.
struct MacBlockConfirm: View {
    @ObservedObject var blocked: MacBlockedUsers
    let userID: String
    let name: String
    /// true: unblock mode.
    let unblocking: Bool
    let close: () -> Void
    @State private var phase: Phase = .confirm

    /// Spec block-person phase.
    private enum Phase: Equatable { case confirm, working, failed }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(unblocking ? BipeStrings.appUnblockTitle(name) : BipeStrings.appBlockTitle(name)).macFont(.headline)
            Text(unblocking ? BipeStrings.appUnblockBody : BipeStrings.dmDeclineBlockDescription)
                .macFont(.body).fixedSize(horizontal: false, vertical: true)
            if phase == .failed {
                MacNotice(text: unblocking ? BipeStrings.appUnblockError : BipeStrings.appBlockError, danger: true)
            }
            HStack {
                Spacer()
                if phase == .working { ProgressView() }
                Button(BipeStrings.appCancel, action: close).keyboardShortcut(.cancelAction).disabled(phase == .working)
                Button(role: unblocking ? nil : .destructive) {
                    phase = .working
                    Task {
                        do {
                            if unblocking { try await blocked.unblock(userID) } else { try await blocked.block(userID) }
                            close()
                        } catch { phase = .failed }
                    }
                } label: { Text(unblocking ? BipeStrings.appUnblockConfirm : BipeStrings.appBlockConfirm) }
                .keyboardShortcut(.defaultAction).disabled(phase == .working)
            }
        }
        .padding(MacDesign.gap * 2).frame(width: 420)
        .interactiveDismissDisabled(phase == .working)
    }
}

/// Spec chat-timeline peerAway (ADR 0141): the other person of a DM left, or the account is gone.
enum MacPeerAway: Equatable {
    case none, left, accountGone
}

/// Spec block-person, the DM after a block (ADR 0141): bipe_app_blocked_bar with bipe_action_unblock_person in place
/// of the composer. Unblock opens the confirm sheet in unblock mode.
struct MacBlockedBar: View {
    @ObservedObject var blocked: MacBlockedUsers
    let userID: String
    let name: String
    @State private var confirming = false

    var body: some View {
        VStack(spacing: 8) {
            Text(BipeStrings.appBlockedBar(name)).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            Button(BipeStrings.actionUnblockPerson) { confirming = true }
                .buttonStyle(.link).macFont(.subheadline)
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(BipeColor.surfaceRaised)
        .accessibilityElement(children: .contain)
        .sheet(isPresented: $confirming) {
            MacBlockConfirm(blocked: blocked, userID: userID, name: name, unblocking: true) { confirming = false }
        }
    }
}

/// Spec chat-timeline peerAway: a lasting bar above the composer when the other person of a DM left
/// (bipe_dm_left with bipe_action_invite_again) or the account is gone (bipe_dm_account_gone, no button).
struct MacDmLeftBar: View {
    let away: MacPeerAway
    let name: String
    let inviting: Bool
    let inviteAgain: () -> Void

    var body: some View {
        if away != .none {
            HStack(spacing: 12) {
                Text(away == .accountGone ? BipeStrings.dmAccountGone : BipeStrings.dmLeft(name))
                    .macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if away == .left {
                    if inviting {
                        ProgressView().controlSize(.regular)
                    } else {
                        Button(BipeStrings.actionInviteAgain, action: inviteAgain).buttonStyle(.link).macFont(.subheadline)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(BipeColor.surfaceRaised)
            .accessibilityElement(children: .contain)
        }
    }
}

/// Spec identity-change: the bar above the composer, from the shared IdentityChangeModel.
struct MacIdentityChangeBar: View {
    @ObservedObject var model: IdentityChangeModel

    var body: some View {
        if let first = model.warnings.first {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    Image(decorative: "warning-circle").renderingMode(.template).resizable()
                        .frame(width: 20, height: 20).foregroundStyle(BipeColor.trustCautionInk)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(first.text).macFont(.footnote).foregroundStyle(BipeColor.inkPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                        if model.warnings.count > 1 {
                            Text(BipeStrings.identityChangeMore(String(model.warnings.count - 1)))
                                .macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack(spacing: 16) {
                    Button(BipeStrings.identityChangeOk) { Task { await model.acknowledge() } }
                        .buttonStyle(.link).macFont(.subheadline).fontWeight(.semibold).disabled(model.busy)
                    Button(BipeStrings.identityChangeLearnMore) { model.showsHelp = true }
                        .buttonStyle(.link).macFont(.subheadline)
                }
                .padding(.leading, 28)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustCautionWash))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(BipeColor.trustCautionLine))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .alert(BipeStrings.identityChangeLearnMore, isPresented: $model.showsHelp) {
                Button(BipeStrings.actionContinue, role: .cancel) {}
            } message: {
                Text(BipeStrings.identityChangeHelp)
            }
            .alert(BipeStrings.errorGeneric, isPresented: $model.failed) {
                Button(BipeStrings.actionContinue, role: .cancel) {}
            }
        }
    }
}

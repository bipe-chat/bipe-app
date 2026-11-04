// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec chat-timeline notes, upgraded groups (bipe#773): the old group points to the group that replaced it.
struct RoomUpgradeBar: View {
    @ObservedObject var model: ChatTimelineModel
    /// Opens the new group in place of this one.
    let open: (String) -> Void
    @State private var joining = false
    @State private var failed = false

    var body: some View {
        VStack(spacing: 10) {
            Text(BipeStrings.roomUpgradeReplaced)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button {
                Task {
                    joining = true
                    defer { joining = false }
                    if let roomId = await model.joinSuccessor() { open(roomId) } else { failed = true }
                }
            } label: {
                if joining { ProgressView() } else { Text(BipeStrings.roomUpgradeGo) }
            }
            .buttonStyle(.bipeProminent)
            .disabled(joining)
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(BipeColor.surfaceRaised)
        .alert(BipeStrings.errorGeneric, isPresented: $failed) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
    }
}

/// The line above the timeline of a new group: it opens the old group.
struct PredecessorLine: View {
    let name: String
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 8) {
                PhosphorIcon.chatCircle.image
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundStyle(BipeColor.accentInk)
                    .accessibilityHidden(true)
                Text(BipeStrings.roomUpgradeContinues(name))
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(BipeColor.surfaceRaised)
        }
        .buttonStyle(.plain)
    }
}

extension ChatTimelineModel {
    /// Joins the successor group, via the server of the old room ID when it has one. nil on failure.
    func joinSuccessor() async -> String? {
        guard let successor = successorRoomId else { return nil }
        let servers = roomId.split(separator: ":", maxSplits: 1).dropFirst().map(String.init)
        return try? await client.joinRoomByIdOrAlias(roomIdOrAlias: successor, serverNames: servers).id()
    }
}

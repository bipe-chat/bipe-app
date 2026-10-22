// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec group-leave (bipe-shared product/screens/group-leave.json).
@MainActor
final class GroupLeaveModel: ObservableObject {
    enum Warning: Equatable {
        case none
        case alone
        case lastAdmin
    }

    enum Phase: Equatable {
        case confirm
        case leaving
        case failed
    }

    @Published private(set) var warning = Warning.none
    @Published private(set) var phase = Phase.confirm

    let roomId: String
    private let session: AccountSession

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    private var room: Room? { try? session.client.getRoom(roomId: roomId) }

    /// Action appear: reads RoomInfo and the members' roles to set warning.
    func appear() async {
        guard let room, let info = try? await room.roomInfo() else { return }
        if info.joinedMembersCount <= 1 {
            warning = .alone
            return
        }
        guard let members = await GroupPeople.members(of: room, cachedOnly: true) else { return }
        let admins = members.filter { $0.membership == .join && GroupPeople.isAdmin($0) }
        if admins.count == 1, admins.first?.userId == session.userId {
            warning = .lastAdmin
        }
    }

    /// Action leave. Returns true when the user left.
    func leave() async -> Bool {
        guard let room, phase != .leaving else { return false }
        phase = .leaving
        do {
            try await room.leave()
            return true
        } catch {
            phase = .failed
            return false
        }
    }
}

/// Spec group-leave: a confirm sheet. onChooseAdmin opens group-members.
struct GroupLeaveView: View {
    @StateObject private var model: GroupLeaveModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.closeChat) private var closeChat
    private let onChooseAdmin: () -> Void

    init(session: AccountSession, roomId: String, onChooseAdmin: @escaping () -> Void) {
        _model = StateObject(wrappedValue: GroupLeaveModel(session: session, roomId: roomId))
        self.onChooseAdmin = onChooseAdmin
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.appGroupLeaveTitle)
                .font(.title3.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(bodyText)
                .foregroundStyle(BipeColor.inkSecondary)
            switch model.phase {
            case .leaving:
                HStack(spacing: 8) {
                    ProgressView()
                    Text(BipeStrings.appGroupLeaving)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            case .failed:
                Text(BipeStrings.appGroupLeaveError)
                    .foregroundStyle(BipeColor.trustDangerInk)
            case .confirm:
                EmptyView()
            }
            Spacer(minLength: 0)
            if model.warning == .lastAdmin {
                Button {
                    onChooseAdmin()
                } label: {
                    Text(BipeStrings.appGroupLeaveChooseAdmin).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            Button(role: .destructive) {
                Task {
                    if await model.leave() {
                        dismiss()
                        closeChat()
                    }
                }
            } label: {
                Text(BipeStrings.appGroupLeaveConfirm).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(BipeColor.trustDangerInk)
            Button {
                dismiss()
            } label: {
                Text(BipeStrings.appCancel).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderless)
        }
        .disabled(model.phase == .leaving)
        .padding(24)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .presentationDetents([.medium])
        .accessibilityIdentifier(BipeScreen.groupLeave.rawValue)
        .task { await model.appear() }
    }

    private var bodyText: String {
        switch model.warning {
        case .none: BipeStrings.appGroupLeaveBody
        case .alone: BipeStrings.appGroupLeaveAlone
        case .lastAdmin: BipeStrings.appGroupLeaveLastAdmin
        }
    }
}

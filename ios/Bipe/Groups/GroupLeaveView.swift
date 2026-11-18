// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec group-leave: a confirm sheet. onChooseAdmin opens group-members.
struct GroupLeaveView: View {
    @StateObject private var model: GroupLeaveModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.closeChat) private var closeChat
    private let onChooseAdmin: () -> Void

    init(session: AccountSession, roomId: String, onChooseAdmin: @escaping () -> Void) {
        _model = StateObject(wrappedValue: GroupLeaveModel(client: session.client, ownUserId: session.userId, roomId: roomId))
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
                .buttonStyle(.bipeProminent)
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
            .buttonStyle(.bipeBordered)
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

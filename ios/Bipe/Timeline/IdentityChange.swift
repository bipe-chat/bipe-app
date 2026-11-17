// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// The iOS chat screen makes the model from its account session. The model itself is shared with the Mac
/// (IdentityChangeModel.swift).
extension IdentityChangeModel {
    convenience init(session: AccountSession, roomId: String) {
        self.init(client: session.client, ownUserId: session.userId, roomId: roomId)
    }
}

/// Spec identity-change: the bar above the composer.
struct IdentityChangeBar: View {
    @ObservedObject var model: IdentityChangeModel

    var body: some View {
        if let first = model.warnings.first {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    PhosphorIcon.warningCircle.image
                        .resizable()
                        .frame(width: 20, height: 20)
                        .foregroundStyle(BipeColor.trustCautionInk)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(first.text)
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkPrimary)
                        if model.warnings.count > 1 {
                            Text(BipeStrings.identityChangeMore(String(model.warnings.count - 1)))
                                .font(.footnote)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack(spacing: 16) {
                    Button(BipeStrings.identityChangeOk) { Task { await model.acknowledge() } }
                        .font(.subheadline.weight(.semibold))
                        .disabled(model.busy)
                    Button(BipeStrings.identityChangeLearnMore) { model.showsHelp = true }
                        .font(.subheadline)
                }
                .padding(.leading, 28)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustCautionWash))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(BipeColor.trustCautionLine, lineWidth: 1))
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

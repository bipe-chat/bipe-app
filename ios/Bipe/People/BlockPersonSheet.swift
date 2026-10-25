// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec block-person: a confirm sheet that blocks a person, or unblocks a blocked one.
/// Block is the Matrix ignore list; blocking does not tell the other person.
struct BlockPersonSheet: View {
    enum Mode {
        case block
        case unblock
    }

    enum Phase: Equatable {
        case confirm
        case working
        case failed
    }

    @ObservedObject var people: PeopleService
    let userId: String
    let name: String

    @State private var phase: Phase = .confirm
    /// Fixed when the sheet opens, so the text does not flip while the ignore list updates.
    @State private var mode: Mode?
    @Environment(\.dismiss) private var dismiss

    private var currentMode: Mode {
        mode ?? (people.isBlocked(userId) ? .unblock : .block)
    }

    var body: some View {
        VStack(spacing: 16) {
            PhosphorIcon.prohibit.image
                .resizable()
                .frame(width: 40, height: 40)
                .foregroundStyle(currentMode == .block ? BipeColor.trustDangerInk : BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(currentMode == .block ? BipeStrings.appBlockTitle(name) : BipeStrings.appUnblockTitle(name))
                .font(.title3.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Text(currentMode == .block ? BipeStrings.dmDeclineBlockDescription : BipeStrings.appUnblockBody)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            if phase == .failed {
                Text(currentMode == .block ? BipeStrings.appBlockError : BipeStrings.appUnblockError)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
                    .multilineTextAlignment(.center)
            }
            VStack(spacing: 8) {
                Button(role: currentMode == .block ? .destructive : nil) {
                    Task { await confirm() }
                } label: {
                    Group {
                        if phase == .working {
                            ProgressView()
                        } else {
                            Text(currentMode == .block ? BipeStrings.appBlockConfirm : BipeStrings.appUnblockConfirm)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeProminent)
                .tint(currentMode == .block ? BipeColor.trustDangerInk : BipeColor.accentFill)
                .controlSize(.large)
                Button(BipeStrings.appCancel) { dismiss() }
                    .controlSize(.large)
            }
            .disabled(phase == .working)
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.blockPerson.rawValue)
        .presentationDetents([.medium])
        .onAppear { mode = currentMode }
    }

    /// Action confirm: Client.ignoreUser or Client.unignoreUser, then closes.
    private func confirm() async {
        phase = .working
        do {
            switch currentMode {
            case .block: try await people.block(userId)
            case .unblock: try await people.unblock(userId)
            }
            dismiss()
        } catch {
            phase = .failed
        }
    }
}

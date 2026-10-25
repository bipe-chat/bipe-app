// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec sign-out: says what the user needs to come back before Bipe signs this phone out.
struct SignOutView: View {
    enum Phase: Equatable {
        case confirm
        case signingOut
        case failed
    }

    @ObservedObject var model: SettingsModel
    let onSignedOut: () -> Void
    @State private var phase: Phase = .confirm
    @State private var isLastDevice = false
    @Environment(\.dismiss) private var dismiss

    private var waysBack: [BipeWayBack] { model.waysBack?.ways ?? [] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.appSignOutTitle)
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.appSignOutBody(String(BipeRegistries.pinLength)))
                    .foregroundStyle(BipeColor.inkPrimary)
                // The ways back by name, one per line (linguagem.md section 6).
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(waysBack, id: \.self) { way in
                        Text(way.title)
                            .foregroundStyle(BipeColor.inkPrimary)
                    }
                }
                if isLastDevice && !model.hasRecoveryKey {
                    Text(BipeStrings.appSignOutHistory)
                        .foregroundStyle(BipeColor.trustCautionInk)
                }
                if waysBack.isEmpty {
                    Text(BipeStrings.appSignOutNoWayBack)
                        .foregroundStyle(BipeColor.trustDangerInk)
                    // Action addWayBack: back to Sua conta, at Jeitos de voltar.
                    Button {
                        dismiss()
                    } label: {
                        Text(BipeStrings.appSignOutAddWay)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bipeProminent)
                }
                if phase == .failed {
                    Text(BipeStrings.errorGeneric)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                Button(role: .destructive) {
                    Task { await signOut() }
                } label: {
                    Group {
                        if phase == .signingOut {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text(BipeStrings.appSigningOut)
                            }
                        } else {
                            Text(BipeStrings.accountSignOut)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeBordered)
                .tint(BipeColor.trustDangerInk)
                .disabled(phase == .signingOut)
                // Action cancel.
                Button {
                    dismiss()
                } label: {
                    Text(BipeStrings.appCancel)
                        .frame(maxWidth: .infinity)
                }
                .disabled(phase == .signingOut)
            }
            .padding(24)
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.signOut.rawValue)
        .interactiveDismissDisabled(phase == .signingOut)
        // Action appear. No haptic: registries/haptics.json has no sign-out use yet.
        .task {
            isLastDevice = (try? await model.session.client.encryption().isLastDevice()) ?? false
        }
    }

    /// Action signOut: Client.logout, then the account's password, session and SDK store leave this phone. The synced
    /// recovery token stays, so recovery-start finds it. Then account-start.
    private func signOut() async {
        phase = .signingOut
        do {
            try await model.session.client.logout()
        } catch {
            phase = .failed
            return
        }
        dismiss()
        onSignedOut()
    }
}

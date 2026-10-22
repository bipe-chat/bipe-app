// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec account-start: no login screen (ADR 0057). Shows chat-list when the account is ready.
struct AccountStartView: View {
    @StateObject private var model = AccountStartModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.phase {
            case .ready:
                if let session = model.session {
                    ChatListView(session: session)
                }
            case .opening:
                StartMessage(icon: .chatCircle, title: BipeStrings.appName, message: BipeStrings.appStartLoading, showsProgress: true)
            case .noAccount:
                StartMessage(icon: .userCircle, title: BipeStrings.appStartNoAccountTitle, message: BipeStrings.appStartNoAccountBody)
            case .failed:
                StartMessage(icon: .warningCircle, title: BipeStrings.appName, message: BipeStrings.appStartError) {
                    Task { await model.retry() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.accountStart.rawValue)
        .tint(BipeColor.accentFill)
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.resume() }
            }
        }
    }
}

private struct StartMessage: View {
    let icon: PhosphorIcon
    let title: String
    let message: String
    var showsProgress = false
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            icon.image
                .resizable()
                .frame(width: 56, height: 56)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(title)
                .font(.title.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            if showsProgress {
                ProgressView()
            }
            if let retry {
                Button(action: retry) {
                    Label {
                        Text(BipeStrings.appRetry)
                    } icon: {
                        PhosphorIcon.arrowClockwise.image.resizable().frame(width: 18, height: 18)
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }
}

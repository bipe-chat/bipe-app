// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// The screens that the settings stack pushes.
enum SettingsRoute: Hashable {
    case group(BipeSettingsGroup)
    case support
    case notifications
}

/// The settings stack. Until the Você tab (bipe-chat/bipe#567) is on main, chat-list opens it as a sheet; the Você tab
/// then shows `SettingsRootSection` in its own list with `settingsDestinations`.
struct SettingsHomeView: View {
    @StateObject private var model: SettingsModel
    @State private var path: [SettingsRoute] = []
    let onSignedOut: () -> Void

    init(session: AccountSession, api: BipeAPIClient, onSignedOut: @escaping () -> Void) {
        _model = StateObject(wrappedValue: SettingsModel(session: session, api: api))
        self.onSignedOut = onSignedOut
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                SettingsRootSection(model: model)
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.settingsSection)
            .navigationBarTitleDisplayMode(.inline)
            .settingsDestinations(model: model, onSignedOut: onSignedOut)
        }
        .tint(BipeColor.accentFill)
    }
}

/// Spec settings-root: one row per group of BipeSettings root, with the current state as the subtitle (ADR 0112).
struct SettingsRootSection: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Section {
            ForEach(BipeSettingsGroup.root, id: \.self) { group in
                // Action openGroup. notifications opens notification-settings (bipe-chat/bipe#566).
                NavigationLink(value: group == .notifications ? SettingsRoute.notifications : SettingsRoute.group(group)) {
                    row(group)
                }
            }
        } header: {
            Text(BipeStrings.settingsSection)
        }
        .accessibilityIdentifier(BipeScreen.settingsRoot.rawValue)
        // Action appear: rows at once, summaries when they arrive.
        .task { await model.load() }
    }

    private func row(_ group: BipeSettingsGroup) -> some View {
        SettingsRootRow(title: group.title, summary: model.summary(of: group), needsAttention: model.needsAttention(group))
    }
}

private struct SettingsRootRow: View {
    let title: String
    let summary: String?
    let needsAttention: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(title)
                    .foregroundStyle(BipeColor.inkPrimary)
                if needsAttention {
                    PhosphorIcon.warningCircle.image
                        .resizable()
                        .frame(width: 16, height: 16)
                        .foregroundStyle(BipeColor.trustCautionInk)
                        .accessibilityHidden(true)
                }
            }
            if let summary {
                Text(summary)
                    .font(.subheadline)
                    .foregroundStyle(needsAttention ? BipeColor.trustCautionInk : BipeColor.inkSecondary)
            }
        }
        .padding(.vertical, 2)
    }
}

extension View {
    /// The destinations of the settings stack.
    func settingsDestinations(model: SettingsModel, onSignedOut: @escaping () -> Void) -> some View {
        navigationDestination(for: SettingsRoute.self) { route in
            switch route {
            case .group(let group):
                SettingsGroupView(model: model, group: group, onSignedOut: onSignedOut)
            case .support:
                SupportOpenView(session: model.session, api: model.api)
            case .notifications:
                NotificationSettingsView(session: model.session, api: model.api)
            }
        }
    }
}

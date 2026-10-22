// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec voce (ADR 0112): who you are first. The header shows photo and name, never the Matrix ID.
struct VoceView: View {
    @ObservedObject var model: AppTabsModel
    let chatList: ChatListModel
    let onNewAccount: () -> Void
    @StateObject private var settings: SettingsModel

    init(model: AppTabsModel, chatList: ChatListModel, api: BipeAPIClient, onNewAccount: @escaping () -> Void) {
        self.model = model
        self.chatList = chatList
        self.onNewAccount = onNewAccount
        _settings = StateObject(wrappedValue: SettingsModel(session: chatList.session, api: api))
    }

    var body: some View {
        NavigationStack {
            List {
                if let account = model.accounts.first(where: \.isCurrent) {
                    VoceHeader(account: account, media: chatList.media)
                        .listRowBackground(BipeColor.surfaceBase)
                }
                if model.accounts.count > 1 {
                    Section(BipeStrings.appAccountSwitcherTitle) {
                        AccountRows(model: model, fallbackMedia: chatList.media)
                    }
                }
                if model.canAddAccount {
                    Section {
                        Button(BipeStrings.appAccountNew, action: onNewAccount)
                            .listRowBackground(BipeColor.surfaceRaised)
                    }
                }
                SettingsRootSection(model: settings)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.tabYou)
            .settingsDestinations(model: settings) {
                Task { await model.start.signOut(userId: chatList.session.userId, alreadyLoggedOut: true) }
            }
        }
        .accessibilityIdentifier(BipeScreen.voce.rawValue)
        .task { await model.refreshProfiles() }
    }
}

private struct VoceHeader: View {
    let account: AccountSummary
    let media: MediaLoader

    var body: some View {
        VStack(spacing: 8) {
            AvatarView(name: account.displayName, mxcUrl: account.avatarUrl, loader: media, size: 88,
                       userId: account.userId, isCompany: account.isCompany)
            Text(account.displayName)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            if let handle = account.handle {
                Text(verbatim: "@\(handle)")
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                if let url = URL(string: "https://bipe.chat/@\(handle)") {
                    ShareLink(item: url) { Text(BipeStrings.profileShare) }
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec account-switcher (ADR 0086, 0135). The same account rows appear in Você.
struct AccountSwitcherView: View {
    @ObservedObject var model: AppTabsModel
    let fallbackMedia: MediaLoader
    let onNewAccount: () -> Void

    var body: some View {
        NavigationStack {
            List {
                AccountRows(model: model, fallbackMedia: fallbackMedia)
                if model.canAddAccount {
                    Button(action: onNewAccount) {
                        Label { Text(BipeStrings.appAccountNew) } icon: { PhosphorIcon.plus.image }
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                }
            }
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.appAccountSwitcherTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { model.showsAccountSwitcher = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await model.refreshProfiles() }
        .accessibilityIdentifier(BipeScreen.accountSwitcher.rawValue)
    }
}

struct AccountRows: View {
    @ObservedObject var model: AppTabsModel
    let fallbackMedia: MediaLoader

    var body: some View {
        ForEach(model.accounts) { account in
            Button { model.switchAccount(to: account.userId) } label: {
                HStack(spacing: 12) {
                    AvatarView(name: account.displayName, mxcUrl: account.avatarUrl,
                               loader: model.media(for: account.userId) ?? fallbackMedia,
                               userId: account.userId, isCompany: account.isCompany)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(account.displayName).foregroundStyle(BipeColor.inkPrimary)
                        if let handle = account.handle {
                            Text(verbatim: "@\(handle)").font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    Spacer()
                    if account.hasUnread {
                        Circle().fill(BipeColor.accentFill).frame(width: 8, height: 8).accessibilityHidden(true)
                    }
                    if account.isCurrent {
                        PhosphorIcon.check.image.resizable().frame(width: 24, height: 24)
                            .foregroundStyle(BipeColor.accentInk).accessibilityHidden(true)
                    }
                }
                .padding(.vertical, 4)
            }
            .accessibilityAddTraits(account.isCurrent ? .isSelected : [])
            .accessibilityLabel(account.isCurrent ? BipeStrings.appAccountCurrentA11y(account.displayName) : account.displayName)
            .listRowBackground(BipeColor.surfaceRaised)
        }
    }
}

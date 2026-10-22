// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec voce (ADR 0112): who you are first. The header shows photo and name, never the Matrix ID.
struct VoceView: View {
    @ObservedObject var model: AppTabsModel
    let chatList: ChatListModel

    var body: some View {
        NavigationStack {
            List {
                if let account = model.accounts.first(where: \.isCurrent) {
                    VoceHeader(account: account, media: chatList.media)
                        .listRowBackground(BipeColor.surfaceBase)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.tabYou)
        }
        .accessibilityIdentifier(BipeScreen.voce.rawValue)
    }
}

private struct VoceHeader: View {
    let account: AccountSummary
    let media: MediaLoader

    var body: some View {
        VStack(spacing: 8) {
            AvatarView(name: account.displayName, mxcUrl: account.avatarUrl, loader: media, size: 88)
            Text(account.displayName)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            if let handle = account.handle {
                Text(verbatim: "@\(handle)")
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}

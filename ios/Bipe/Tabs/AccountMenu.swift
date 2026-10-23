// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Spec account-switcher (ADR 0086, 0135): the native context menu a long press on Você opens. Each account shows its
/// photo or initial, name and @handle, with the system check on the current one; then Nova conta while the cap allows.
@MainActor
enum AccountMenu {
    static func make(model: AppTabsModel, onNewAccount: @escaping () -> Void) -> UIMenu {
        let accounts = model.accounts.map { account in
            UIAction(title: account.displayName,
                     subtitle: account.handle.map { "@\($0)" },
                     image: model.menuAvatar(of: account),
                     state: account.isCurrent ? .on : .off) { _ in
                model.switchAccount(to: account.userId)
            }
        }
        var children: [UIMenuElement] = [UIMenu(options: .displayInline, children: accounts)]
        if model.canAddAccount {
            children.append(UIAction(title: BipeStrings.appAccountNew, image: UIImage(systemName: "plus")) { _ in
                onNewAccount()
            })
        }
        return UIMenu(title: BipeStrings.appAccountSwitcherTitle, children: children)
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

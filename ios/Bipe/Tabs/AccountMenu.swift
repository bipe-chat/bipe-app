// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Spec account-switcher (ADR 0086, 0135): the native context menu a long press on Você opens. Each account shows its
/// photo or initial, name and @handle, with the system check on the current one; then Nova conta (spec account-new).
@MainActor
enum AccountMenu {
    static func make(model: AppTabsModel, onNewAccount: @escaping () -> Void) -> UIMenu {
        // One inline group per owner and its company accounts (bipe-chat/bipe#444).
        var groups: [[UIAction]] = []
        for account in model.accounts {
            let action = UIAction(title: account.displayName,
                                  subtitle: account.ownerName.map(BipeStrings.accountCompanyOwner) ?? account.handle.map { "@\($0)" },
                                  image: model.menuAvatar(of: account),
                                  state: account.isCurrent ? .on : .off) { _ in
                model.switchAccount(to: account.userId)
            }
            if account.ownerId != nil, !groups.isEmpty {
                groups[groups.count - 1].append(action)
            } else {
                groups.append([action])
            }
        }
        var children: [UIMenuElement] = groups.map { UIMenu(options: .displayInline, children: $0) }
        children.append(UIAction(title: BipeStrings.appAccountNew, image: UIImage(systemName: "plus")) { _ in
            onNewAccount()
        })
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
                        if let owner = account.ownerName {
                            Text(BipeStrings.accountCompanyOwner(owner)).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                        } else if let handle = account.handle {
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
                // A company account sits under its owner (bipe-chat/bipe#444).
                .padding(.leading, account.ownerId == nil ? 0 : 24)
            }
            .accessibilityAddTraits(account.isCurrent ? .isSelected : [])
            .accessibilityLabel(account.isCurrent ? BipeStrings.appAccountCurrentA11y(account.displayName) : account.displayName)
        }
    }
}

/// Spec account-switcher in the popover a hold on the Você tab opens when the iOS tab bar holds back the native menu:
/// the same accounts, then Nova conta. A switch or Nova conta closes it.
struct AccountSwitcherPopover: View {
    @ObservedObject var model: AppTabsModel
    let fallbackMedia: MediaLoader
    let onNewAccount: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                AccountRows(model: model, fallbackMedia: fallbackMedia)
                Button {
                    dismiss()
                    onNewAccount()
                } label: {
                    Label { Text(BipeStrings.appAccountNew) } icon: { Image(systemName: "plus") }
                }
                .listRowBackground(BipeColor.surfaceRaised)
            } header: {
                Text(BipeStrings.appAccountSwitcherTitle)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase)
        .onChange(of: model.accounts.first(where: \.isCurrent)?.userId) { _, _ in dismiss() }
    }
}

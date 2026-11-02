// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

struct AccountRows: View {
    @ObservedObject var model: AppTabsModel
    let fallbackMedia: MediaLoader
    /// A tap on the current account: no switch, the switcher only closes.
    var onCurrent: () -> Void = {}

    var body: some View {
        ForEach(model.accounts) { account in
            Button {
                if account.isCurrent { onCurrent() } else { model.switchAccount(to: account.userId) }
            } label: {
                HStack(spacing: 12) {
                    AvatarView(name: account.displayName, mxcUrl: account.avatarUrl,
                               loader: model.media(for: account.userId) ?? fallbackMedia, size: 32,
                               userId: account.userId, isCompany: account.isCompany)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(account.displayName).foregroundStyle(BipeColor.inkPrimary).lineLimit(1)
                        if let owner = account.ownerName {
                            Text(BipeStrings.accountCompanyOwner(owner)).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
                                .lineLimit(1)
                        } else if let handle = account.handleLine {
                            Text(verbatim: handle).font(.footnote).foregroundStyle(BipeColor.inkSecondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 8)
                    if account.hasUnread {
                        Circle().fill(BipeColor.accentFill).frame(width: 8, height: 8).accessibilityHidden(true)
                    }
                    if account.isCurrent {
                        PhosphorIcon.check.image.resizable().frame(width: 20, height: 20)
                            .foregroundStyle(BipeColor.accentInk).accessibilityHidden(true)
                    }
                }
                // A company account sits under its owner (bipe-chat/bipe#444).
                .padding(.leading, account.ownerId == nil ? 16 : 40)
                .padding(.trailing, 16)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(account.isCurrent ? .isSelected : [])
            .accessibilityLabel(account.isCurrent ? BipeStrings.appAccountCurrentA11y(account.displayName) : account.displayName)
        }
    }
}

/// Spec account-switcher: the panel a hold on Você opens. It sits right on top of the tab bar and is as wide as it, so it
/// reads as part of the bar (MZ, 2 Nov 2026; it replaces the native menu of ADR 0135, which floated high and had wide
/// rows). Every account, then Nova conta. A tap outside, a switch or Nova conta closes it.
struct AccountSwitcherPanel: View {
    @ObservedObject var model: AppTabsModel
    let fallbackMedia: MediaLoader
    /// From the panel's bottom edge to the bottom of the screen: just above the tab bar.
    let bottomInset: CGFloat
    let onNewAccount: () -> Void
    let close: () -> Void
    @State private var shows = false

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(shows ? 0.08 : 0)
                .contentShape(Rectangle())
                .onTapGesture { hide() }
                .accessibilityHidden(true)
            if shows {
                panel
                    .padding(.horizontal, 20)
                    .padding(.bottom, bottomInset)
                    .transition(UIAccessibility.isReduceMotionEnabled ? .opacity : .scale(scale: 0.92, anchor: .bottom).combined(with: .opacity))
            }
        }
        .ignoresSafeArea()
        .onAppear { withBipeAnimation(.snappy(duration: 0.25)) { shows = true } }
        .onChange(of: model.accounts.first(where: \.isCurrent)?.userId) { _, _ in hide() }
        .accessibilityAction(.escape) { hide() }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(BipeStrings.appAccountSwitcherTitle)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 2)
                .accessibilityAddTraits(.isHeader)
            AccountRows(model: model, fallbackMedia: fallbackMedia, onCurrent: { hide() })
            Divider().padding(.leading, 60)
            Button { hide(then: onNewAccount) } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus").font(.body.weight(.medium)).frame(width: 32, height: 32)
                    Text(BipeStrings.appAccountNew)
                    Spacer()
                }
                .foregroundStyle(BipeColor.inkPrimary)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.bottom, 6)
        .modifier(PanelSurface())
    }

    private func hide(then next: @escaping () -> Void = {}) {
        guard shows else { return }
        withBipeAnimation(.snappy(duration: 0.2)) { shows = false } completion: {
            close()
            next()
        }
    }
}

/// The tab bar's own glass on iOS 26 and later, else the system material (solid under Reduce Transparency).
private struct PanelSurface: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.glassEffect(.regular, in: .rect(cornerRadius: 28))
        } else {
            content.bipeMaterial(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

struct MacAccountView: View {
    @ObservedObject var workspace: MacWorkspace
    @ObservedObject var account: MacAccount
    @Environment(\.scenePhase) private var scenePhase
    private var badges: MacTabAttention { MacTabAttention(account.roomRows.map(\.attention)) }

    /// A segmented control has no badge, so the count follows the title.
    private func title(_ destination: MacDestination) -> String {
        let count: Int
        switch destination {
        case .chats: count = account.isCompany ? badges.chats + badges.groups : badges.chats
        case .groups: count = badges.groups
        case .work, .you: count = 0
        }
        return count > 0 ? "\(destination.title) \(count.formatted())" : destination.title
    }

    var body: some View {
        Group {
            if account.phase != .signedIn { MacLinkView(account: account) }
            else {
                // One window chrome for every destination: account at the leading edge, destinations in the middle.
                switch account.destination {
                case .chats: MacConversationsView(account: account, list: account.roomList, scope: .chats).id(MacDestination.chats)
                case .groups: MacConversationsView(account: account, list: account.roomList, scope: .groups).id(MacDestination.groups)
                case .work: MacWorkView(account: account)
                case .you: MacYouView(account: account)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigation) { MacAccountSwitcher(workspace: workspace, account: account) }
            if account.phase == .signedIn {
                ToolbarItem(placement: .principal) {
                    Picker(BipeStrings.settingsSection, selection: $account.destination) {
                        ForEach(MacDestination.visible(isCompany: account.isCompany)) { destination in
                            Text(title(destination)).tag(destination)
                        }
                    }.pickerStyle(.segmented).labelsHidden().fixedSize()
                }
            }
        }
        .background(BipeColor.surfaceBase)
        .safeAreaInset(edge: .top) {
            if account.storageFailed { MacNotice(text: BipeStrings.errorGeneric, danger: true).padding() }
        }
        .onChange(of: account.isCompany) { _, _ in
            if !MacDestination.visible(isCompany: account.isCompany).contains(account.destination) { account.destination = .chats }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await account.roomList.refreshFavouritesOrder() } }
        }
    }
}

private struct MacAccountSwitcher: View {
    @ObservedObject var workspace: MacWorkspace
    @ObservedObject var account: MacAccount
    @State private var showsAccounts = false

    var body: some View {
        Button { showsAccounts.toggle() } label: {
            MacAvatar(name: account.displayName, isCompany: account.isCompany, media: account.media,
                      url: account.avatarURL, userID: account.userId, size: 32)
                .overlay(alignment: .topTrailing) {
                    if workspace.otherAccountUnread {
                        Circle().fill(BipeColor.accentFill).frame(width: 9, height: 9)
                            .overlay(Circle().stroke(BipeColor.surfaceBase, lineWidth: 2))
                    }
                }
                .bipeTapTarget()
        }.buttonStyle(.plain).help(BipeStrings.webAccountSwitcherTitle)
            .accessibilityLabel(BipeStrings.a11ySwitchAccount)
            .popover(isPresented: $showsAccounts, arrowEdge: .top) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(BipeStrings.webAccountSwitcherTitle).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 6)
                    ForEach(workspace.accounts, id: \.userId) { entry in
                        Button {
                            showsAccounts = false
                            workspace.select(entry)
                        } label: { MacAccountRow(account: entry, current: entry === account) }
                            .buttonStyle(.plain).padding(.horizontal, 16)
                    }
                    Divider().padding(.leading, 60)
                    Button {
                        showsAccounts = false
                        workspace.addAccount()
                    } label: {
                        HStack(spacing: 12) {
                            Image(decorative: "plus").resizable().scaledToFit().frame(width: 20, height: 20).frame(width: 32, height: 32)
                            Text(BipeStrings.appAccountNew)
                            Spacer()
                        }.padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }.padding(.bottom, 6).frame(width: 300).macFont(.body)
            }
    }
}

private struct MacAccountRow: View {
    @ObservedObject var account: MacAccount
    let current: Bool
    var body: some View {
        HStack(spacing: 12) {
            MacAvatar(name: account.displayName, isCompany: account.isCompany, media: account.media,
                      url: account.avatarURL, userID: account.userId, size: 32)
            VStack(alignment: .leading, spacing: 3) {
                Text(account.displayName).macFont(.body).lineLimit(1)
                if let handle = account.handle { Text("@" + handle).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary) }
            }
            Spacer(minLength: 0)
            if account.hasUnread, !current {
                Circle().fill(BipeColor.accentFill).frame(width: 8, height: 8).accessibilityLabel(BipeStrings.chatUnreadState)
            }
            if current { Image(systemName: "checkmark").foregroundStyle(BipeColor.accentInk).accessibilityLabel(BipeStrings.appAccountCurrentA11y(account.displayName)) }
        }.padding(.vertical, 6).contentShape(Rectangle())
    }
}

private struct MacYouView: View {
    @ObservedObject var account: MacAccount
    @AppStorage(MacReceiptPolicy.key) private var readReceipts = true
    @AppStorage(BipeSettingsRow.chatsMediaQuality.rawValue) private var mediaQuality = "standard"
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: MacDesign.sectionGap) {
                    Text(BipeStrings.tabYou).macFont(.largeTitle).fontWeight(.bold)
                    MacCard {
                        HStack(spacing: MacDesign.gap) {
                            MacAvatar(name: account.displayName, isCompany: account.isCompany, media: account.media,
                                      url: account.avatarURL, userID: account.userId, size: 64)
                            VStack(alignment: .leading, spacing: 6) {
                                Text(account.displayName).macFont(.title2).fontWeight(.semibold)
                                if let handle = account.handle { Text("@" + handle).foregroundStyle(BipeColor.inkSecondary).textSelection(.enabled) }
                            }
                            Spacer()
                            Button(BipeStrings.macRefresh) { Task { await account.refreshProfile() } }.buttonStyle(.bipeBordered)
                        }
                    }
                    MacCard {
                        NavigationLink { MacColorsView() } label: {
                            HStack {
                                Text(BipeSettingsRow.chatsColors.title)
                                Spacer()
                                Image(systemName: "chevron.right").accessibilityHidden(true)
                            }.padding(.vertical, 8)
                        }.buttonStyle(.plain)
                    }
                    MacCard {
                        VStack(alignment: .leading, spacing: MacDesign.gap) {
                            Text(BipeStrings.settingsPrivacy).macFont(.headline)
                            Toggle(BipeStrings.privacyReadReceipts, isOn: $readReceipts)
                            Text(BipeStrings.privacyReadReceiptsFooter).macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
                            Picker(BipeStrings.chatsMediaQuality, selection: $mediaQuality) {
                                Text(BipeStrings.chatsMediaQualityStandard).tag("standard")
                                Text(BipeStrings.chatsMediaQualityHigh).tag("high")
                            }
                        }
                    }
                }.padding(MacDesign.pageInset).frame(maxWidth: 800, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
            }.background(BipeColor.surfaceBase)
        }
    }
}

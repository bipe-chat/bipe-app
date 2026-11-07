// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

struct MacAccountView: View {
    @ObservedObject var workspace: MacWorkspace
    @ObservedObject var account: MacAccount
    @State private var destination = MacDestination.chats
    @State private var selected: String?
    @State private var search = ""
    @State private var unreadOnly = false

    private var rows: [MacRoomSummary] {
        account.roomRows.filter {
            destination.includes(isDm: $0.isDm, isCompany: account.isCompany)
                && (!unreadOnly || $0.unread > 0 || $0.markedUnread)
                && (search.isEmpty || $0.name.localizedStandardContains(search))
        }
    }

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                MacBrand(compact: true).frame(maxWidth: .infinity, alignment: .leading).padding()
                Picker(BipeStrings.appName, selection: $destination) {
                    ForEach(MacDestination.visible(isCompany: account.isCompany)) { item in
                        Text(item.title).tag(item)
                    }
                }.pickerStyle(.segmented).labelsHidden().padding(.horizontal).padding(.bottom, 12)
                if destination == .chats || destination == .groups {
                    HStack {
                        Text(destination.title).font(.title2).fontWeight(.bold)
                        Spacer()
                        Toggle(BipeStrings.appChatListFilterUnread, isOn: $unreadOnly).toggleStyle(.button)
                    }.padding(.horizontal).padding(.bottom, 12)
                    List(selection: $selected) {
                        ForEach(rows) { entry in
                            HStack(spacing: 12) {
                                MacAvatar(name: entry.name)
                                Text(entry.name).font(.body).lineLimit(2)
                                Spacer(minLength: 0)
                                if entry.unread > 0 {
                                    Text(entry.unread.formatted()).font(.caption).fontWeight(.semibold)
                                        .padding(6).background(BipeColor.accentWash, in: Capsule())
                                        .accessibilityLabel(BipeStrings.appChatListUnread(entry.unread.formatted()))
                                } else if entry.markedUnread {
                                    Circle().fill(BipeColor.accentFill).frame(width: 8, height: 8)
                                        .accessibilityLabel(BipeStrings.appChatListFilterUnread)
                                }
                            }.padding(.vertical, 8).tag(entry.id)
                        }
                        if account.roomRows.count >= 100 {
                            Button(BipeStrings.actionShowMore) { account.loadMoreRooms() }
                        }
                    }.searchable(text: $search, prompt: BipeStrings.appSearchPlaceholder).overlay {
                        if rows.isEmpty {
                            Text(!search.isEmpty ? BipeStrings.appSearchNoResults(search)
                                 : unreadOnly ? BipeStrings.appChatListNoUnread
                                 : destination == .groups ? BipeStrings.appGroupsEmpty : BipeStrings.appChatListEmpty)
                                .foregroundStyle(BipeColor.inkSecondary).padding()
                        }
                    }
                } else {
                    Spacer()
                }
            }
            .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 380)
            .safeAreaInset(edge: .bottom) { accountMenu.padding().background(BipeColor.surfaceRaised) }
        } detail: {
            if account.phase != .signedIn {
                MacLinkView(account: account)
            } else if destination == .work {
                MacWorkView(account: account)
            } else if destination == .you {
                MacYouView(workspace: workspace, account: account)
            } else if let entry = account.roomRows.first(where: { $0.id == selected }) {
                MacChatView(room: entry.room, name: entry.name, drafts: account.drafts, media: account.media).id(entry.id)
            } else {
                VStack(spacing: MacDesign.gap) {
                    MacBrand()
                    Text(rows.isEmpty ? BipeStrings.appChatListEmpty : destination.title).foregroundStyle(BipeColor.inkSecondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity).background(BipeColor.surfaceBase)
            }
        }
        .safeAreaInset(edge: .top) {
            if account.storageFailed { MacNotice(text: BipeStrings.errorGeneric, danger: true).padding() }
        }
        .onChange(of: destination) { _, _ in selected = nil; search = "" }
        .onChange(of: account.isCompany) { _, _ in
            if !MacDestination.visible(isCompany: account.isCompany).contains(destination) { destination = .chats; selected = nil }
        }
    }

    private var accountMenu: some View {
        Menu {
            ForEach(workspace.accounts, id: \.userId) { entry in
                Button { workspace.select(entry) } label: {
                    MacAccountMenuLabel(account: entry, current: entry === account)
                }
            }
            Divider()
            Button(BipeStrings.appAccountNew) { workspace.addAccount() }
        } label: {
            HStack(spacing: 10) {
                MacAvatar(name: account.displayName, isCompany: account.isCompany)
                VStack(alignment: .leading, spacing: 3) {
                    Text(account.displayName).font(.headline).lineLimit(1)
                    if let handle = account.handle { Text("@" + handle).font(.caption).foregroundStyle(BipeColor.inkSecondary) }
                }
                Spacer(minLength: 0)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.menuStyle(.borderlessButton).accessibilityLabel(BipeStrings.a11ySwitchAccount)
    }
}

private struct MacAccountMenuLabel: View {
    @ObservedObject var account: MacAccount
    let current: Bool
    var body: some View {
        HStack {
            Text(account.displayName)
            if let handle = account.handle { Text("@" + handle) }
            if current { Image(systemName: "checkmark") }
            if account.hasUnread { Text(BipeStrings.appChatListFilterUnread) }
        }
    }
}

private struct MacYouView: View {
    @ObservedObject var workspace: MacWorkspace
    @ObservedObject var account: MacAccount
    @AppStorage(MacReceiptPolicy.key) private var readReceipts = true
    @AppStorage(BipeSettingsRow.chatsMediaQuality.rawValue) private var mediaQuality = "standard"
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MacDesign.sectionGap) {
                Text(BipeStrings.tabYou).font(.largeTitle).fontWeight(.bold)
                MacCard {
                    HStack(spacing: MacDesign.gap) {
                        MacAvatar(name: account.displayName, isCompany: account.isCompany)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(account.displayName).font(.title2).fontWeight(.semibold)
                            if let handle = account.handle { Text("@" + handle).foregroundStyle(BipeColor.inkSecondary).textSelection(.enabled) }
                        }
                        Spacer()
                        Button(BipeStrings.macRefresh) { Task { await account.refreshProfile() } }.buttonStyle(.bipeBordered)
                    }
                }
                MacCard {
                    VStack(alignment: .leading, spacing: MacDesign.gap) {
                        Text(BipeStrings.macAccountsTitle).font(.headline)
                        ForEach(workspace.accounts, id: \.userId) { entry in
                            Button { workspace.select(entry) } label: { MacAccountRow(account: entry, current: entry === account) }
                                .buttonStyle(.plain)
                        }
                        Divider()
                        Button(BipeStrings.appAccountNew) { workspace.addAccount() }.buttonStyle(.bipeBordered)
                    }
                }
                MacCard {
                    VStack(alignment: .leading, spacing: MacDesign.gap) {
                        Text(BipeStrings.settingsPrivacy).font(.headline)
                        Toggle(BipeStrings.privacyReadReceipts, isOn: $readReceipts)
                        Text(BipeStrings.privacyReadReceiptsFooter).font(.callout).foregroundStyle(BipeColor.inkSecondary)
                        Picker(BipeStrings.chatsMediaQuality, selection: $mediaQuality) {
                            Text(BipeStrings.chatsMediaQualityStandard).tag("standard")
                            Text(BipeStrings.chatsMediaQualityHigh).tag("high")
                        }
                    }
                }
            }.padding(MacDesign.pageInset).frame(maxWidth: 800, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }.background(BipeColor.surfaceBase)
    }
}

private struct MacAccountRow: View {
    @ObservedObject var account: MacAccount
    let current: Bool
    var body: some View {
        HStack(spacing: 12) {
            MacAvatar(name: account.displayName, isCompany: account.isCompany)
            VStack(alignment: .leading, spacing: 4) {
                Text(account.displayName).fontWeight(.semibold)
                if let handle = account.handle { Text("@" + handle).font(.caption).foregroundStyle(BipeColor.inkSecondary) }
            }
            Spacer()
            if account.hasUnread { Text(BipeStrings.appChatListFilterUnread).font(.caption).foregroundStyle(BipeColor.accentInk) }
            if current { Image(systemName: "checkmark").accessibilityLabel(BipeStrings.appAccountCurrentA11y(account.displayName)) }
        }.padding(.vertical, 6).contentShape(Rectangle())
    }
}

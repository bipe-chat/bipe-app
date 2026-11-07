// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI
import UserNotifications

/// The state that settings-root and settings-group read on the Mac. One model for the You tab, so a group shows what
/// the list already read.
@MainActor
final class MacSettingsModel: ObservableObject {
    let account: MacAccount
    @Published private(set) var whoCanMessage: BipeWhoCanMessage?
    @Published private(set) var status: BipeAccountStatus?
    @Published private(set) var hasRecoveryKey = false
    @Published private(set) var notificationsOn: Bool?

    init(account: MacAccount) { self.account = account }

    /// `nil` until GET /v1/me/account answers. The Mac has no token store of its own, so the server's flag counts
    /// (spec settings-root, Mac note).
    var waysBack: BipeWaysBack? {
        guard let status else { return nil }
        return BipeWaysBack(hasEmail: status.email != nil,
                            hasLinkedAccount: !(status.links ?? []).isEmpty,
                            hasRecoveryKey: hasRecoveryKey,
                            hasSyncedToken: status.recoveryTokenSet)
    }

    /// Spec settings-root, action appear: each summary fills in when it arrives.
    func load() async {
        hasRecoveryKey = account.client?.encryption().recoveryState() == .enabled
        async let messaging: Void = loadMessaging()
        async let accountStatus: Void = loadAccount()
        async let notifications: Void = loadNotifications()
        _ = await (messaging, accountStatus, notifications)
    }

    private func loadMessaging() async {
        let api = account.api
        if let value = try? await account.authorized({ (token: String) async throws(BipeAPIError) -> BipeWhoCanMessage in
            try await api.whoCanMessage(accessToken: token)
        }) { whoCanMessage = value }
    }

    private func loadAccount() async {
        let api = account.api
        if let value = try? await account.authorized({ (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }) { status = value }
    }

    private func loadNotifications() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notificationsOn = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    }

    /// PUT /v1/me/messaging. `false` when the save failed; the value stays as it was.
    func setWhoCanMessage(_ value: BipeWhoCanMessage) async -> Bool {
        let api = account.api
        guard let saved = try? await account.authorized({ (token: String) async throws(BipeAPIError) -> BipeWhoCanMessage in
            try await api.setWhoCanMessage(value, accessToken: token)
        }) else { return false }
        whoCanMessage = saved
        return true
    }

    /// The subtitle of a root row. `nil` when the value has not arrived. chats reads the theme and palette in the view.
    func summary(of group: BipeSettingsGroup) -> String? {
        switch group {
        case .privacy: whoCanMessage?.settingsSummary
        case .account: waysBack?.summary
        case .notifications: notificationsOn.map { $0 ? BipeStrings.settingsNotificationsOn : BipeStrings.macNotificationsOff }
        case .help: BipeStrings.helpVersion(MacSettingsText.version)
        case .profile, .chats: nil
        }
    }

    func needsAttention(_ group: BipeSettingsGroup) -> Bool {
        group == .account && waysBack?.needsWarning == true
    }
}

enum MacSettingsText {
    /// "1.0.1 (446)" (spec settings-root).
    static var version: String {
        let info = Bundle.main.infoDictionary
        let name = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        return "\(name) (\(build))"
    }

    static func theme(_ stored: String) -> String {
        switch stored {
        case "light": BipeStrings.chatsAppearanceLight
        case "dark": BipeStrings.chatsAppearanceDark
        default: BipeStrings.chatsAppearanceSystem
        }
    }

    static func palette(_ stored: String) -> String {
        (BipePalette(rawValue: stored) ?? .standard).title
    }
}

struct MacYouView: View {
    @ObservedObject var workspace: MacWorkspace
    @ObservedObject var account: MacAccount
    @StateObject private var settings: MacSettingsModel

    init(workspace: MacWorkspace, account: MacAccount) {
        self.workspace = workspace
        self.account = account
        _settings = StateObject(wrappedValue: MacSettingsModel(account: account))
    }

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
                    MacSettingsRootSection(model: settings)
                }.padding(MacDesign.pageInset).frame(maxWidth: 800, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
            }.background(BipeColor.surfaceBase)
                .navigationDestination(for: BipeSettingsGroup.self) { group in
                    // Action openGroup: notifications opens notification-settings.
                    if group == .notifications {
                        MacNotificationSettingsView(account: account)
                    } else {
                        MacSettingsGroupView(model: settings, workspace: workspace, group: group)
                    }
                }
        }
    }
}

/// Spec settings-root: one row per group of BipeSettings root, with the icon tile, the title, the current state as the
/// subtitle, and a chevron.
private struct MacSettingsRootSection: View {
    @ObservedObject var model: MacSettingsModel
    @AppStorage(BipeSettingsRow.chatsAppearance.rawValue) private var appearance = "system"
    @AppStorage(AppTheme.paletteKey) private var palette = BipePalette.standard.rawValue
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.settingsSection).macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
            MacCard(inset: 0) {
                VStack(spacing: 0) {
                    ForEach(BipeSettingsGroup.root, id: \.self) { group in
                        NavigationLink(value: group) {
                            MacSettingsRootRow(group: group, summary: summary(of: group), needsAttention: model.needsAttention(group))
                        }.buttonStyle(.plain)
                        if group != BipeSettingsGroup.root.last { Divider().padding(.leading, 57) }
                    }
                }
            }
        }
        // Action appear: rows at once, summaries when they arrive. A row's screen can change a summary.
        .task { await model.load() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.load() } }
        }
    }

    private func summary(of group: BipeSettingsGroup) -> String? {
        guard group == .chats else { return model.summary(of: group) }
        return "\(MacSettingsText.theme(appearance)) · \(MacSettingsText.palette(palette))"
    }
}

private struct MacSettingsRootRow: View {
    let group: BipeSettingsGroup
    let summary: String?
    let needsAttention: Bool

    var body: some View {
        HStack(spacing: 12) {
            if let icon = group.icon { MacSettingsIconTile(icon: icon) }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(group.title).macFont(.body).foregroundStyle(BipeColor.inkPrimary)
                    if needsAttention {
                        Image(decorative: "warning-circle").renderingMode(.template).resizable()
                            .frame(width: 16, height: 16).foregroundStyle(BipeColor.trustCautionInk)
                            .accessibilityHidden(true)
                    }
                }
                if let summary {
                    Text(summary).macFont(.subheadline)
                        .foregroundStyle(needsAttention ? BipeColor.trustCautionInk : BipeColor.inkSecondary)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").foregroundStyle(BipeColor.glyphSecondary).accessibilityHidden(true)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}

/// The icon tile of a settings group (spec settings-root): a glyph in ink.onAccent on accent.fill, in a rounded square.
/// Decorative: the row title says what the row is.
private struct MacSettingsIconTile: View {
    let icon: String

    var body: some View {
        Image(decorative: icon).renderingMode(.template).resizable().scaledToFit()
            .frame(width: 18, height: 18)
            .foregroundStyle(BipeColor.inkOnAccent)
            .frame(width: 29, height: 29)
            .background(BipeColor.accentFill, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// Spec settings-group on the Mac: the rows of registries/settings.json the Mac can do, in the shared order. A row with
/// no handler stays hidden, so the screen has no dead row.
private struct MacSettingsGroupView: View {
    @ObservedObject var model: MacSettingsModel
    @ObservedObject var workspace: MacWorkspace
    let group: BipeSettingsGroup
    @AppStorage(MacReceiptPolicy.key) private var readReceipts = true
    @AppStorage(BipeSettingsRow.chatsMediaQuality.rawValue) private var mediaQuality = "standard"
    @AppStorage(BipeSettingsRow.chatsAppearance.rawValue) private var appearance = "system"
    @AppStorage(AppTheme.paletteKey) private var palette = BipePalette.standard.rawValue
    @State private var showsSignOut = false
    @State private var failed = false
    private var language: String { Locale.current.language.languageCode?.identifier == "pt" ? "pt" : "en" }

    private func hasHandler(_ row: BipeSettingsRow) -> Bool {
        if row.kind == .url { return row.url(language: language) != nil }
        switch row {
        case .privacyMessaging, .privacyReadReceipts,
             .accountWaysBackStatus, .accountEmail, .accountLinkedAccounts, .accountRecoveryKey,
             .accountLinkedDevices, .accountSignOut,
             .chatsAppearance, .chatsColors, .chatsShowMedia, .chatsMediaQuality: return true
        default: return false
        }
    }

    private var sections: [(section: BipeSettingsSection, rows: [BipeSettingsRow])] {
        group.sections.compactMap { section in
            let rows = section.rows.filter(hasHandler)
            return rows.isEmpty ? nil : (section, rows)
        }
    }

    var body: some View {
        Form {
            if group == .account, let warning = model.waysBack?.warning {
                Section { MacNotice(text: warning) }
            }
            ForEach(sections, id: \.section.key) { visible in
                Section {
                    ForEach(visible.rows, id: \.self) { row($0) }
                } header: {
                    if let title = visible.section.title { Text(title) }
                } footer: {
                    footer(of: visible.section)
                }
            }
        }
        .formStyle(.grouped)
        .macFont(.body)
        .navigationTitle(group.title)
        .accessibilityIdentifier(BipeScreen.settingsGroup.rawValue)
        .task { await model.load() }
        .sheet(isPresented: $showsSignOut) { MacSignOutView(model: model, workspace: workspace) }
        .alert(BipeStrings.errorGeneric, isPresented: $failed) {}
    }

    @ViewBuilder private func footer(of section: BipeSettingsSection) -> some View {
        if group == .help && section.key == "about" {
            VStack(alignment: .leading, spacing: 8) {
                if let footer = section.footer { Text(footer) }
                Text(BipeStrings.helpVersion(MacSettingsText.version))
            }
        } else if group == .account && section.key == "waysBack" {
            Text(BipeStrings.macAccountChangeOnPhone)
        } else if let footer = section.footer {
            Text(footer)
        }
    }

    /// The linked providers, for the linkedAccounts row (ADR 0096).
    private var linkedSummary: String? {
        model.status.map { status in
            let links = status.links ?? []
            guard !links.isEmpty else { return BipeStrings.accountEmailNone }
            return links.map { $0.provider == .apple ? BipeStrings.linkedApple : BipeStrings.linkedGoogle }.joined(separator: ", ")
        }
    }

    /// A read-only row: the change needs the phone (ADR 0266 rule 6).
    @ViewBuilder private func valueRow(_ row: BipeSettingsRow, _ value: String?) -> some View {
        if let value { LabeledContent(row.title, value: value) } else { Text(row.title) }
    }

    @ViewBuilder private func row(_ row: BipeSettingsRow) -> some View {
        switch row {
        case .privacyMessaging:
            Picker(row.title, selection: Binding(get: { model.whoCanMessage ?? .everyone }, set: { value in
                Task { if !(await model.setWhoCanMessage(value)) { failed = true } }
            })) {
                Text(BipeStrings.messagingEveryone).tag(BipeWhoCanMessage.everyone)
                Text(BipeStrings.messagingVerified).tag(BipeWhoCanMessage.verified)
                Text(BipeStrings.messagingContacts).tag(BipeWhoCanMessage.contacts)
            }.disabled(model.whoCanMessage == nil)
        case .privacyReadReceipts:
            Toggle(row.title, isOn: Binding(get: { readReceipts }, set: {
                readReceipts = $0
                SyncedSettings.changed(BipeSyncedSettings.readReceipts)
            }))
            if let footer = row.footer { Text(footer).macFont(.callout).foregroundStyle(BipeColor.inkSecondary) }
        case .accountWaysBackStatus:
            valueRow(row, model.waysBack.map { $0.count.formatted() })
        case .accountEmail:
            valueRow(row, model.status.map { $0.email ?? BipeStrings.accountEmailNone })
        case .accountLinkedAccounts:
            valueRow(row, linkedSummary)
        case .accountRecoveryKey:
            valueRow(row, model.hasRecoveryKey ? BipeStrings.accountRecoveryKeySaved : BipeStrings.accountRecoveryKeyMissing)
        case .accountLinkedDevices:
            NavigationLink { MacLinkedDevicesView(account: model.account) } label: { Text(row.title) }
        case .accountSignOut:
            Button(role: .destructive) { showsSignOut = true } label: {
                Text(row.title).foregroundStyle(BipeColor.trustDangerInk)
            }.buttonStyle(.plain)
        case .chatsAppearance:
            Picker(row.title, selection: $appearance) {
                Text(BipeStrings.chatsAppearanceSystem).tag("system")
                Text(BipeStrings.chatsAppearanceLight).tag("light")
                Text(BipeStrings.chatsAppearanceDark).tag("dark")
            }
        case .chatsColors:
            NavigationLink { MacColorsView() } label: {
                LabeledContent(row.title, value: MacSettingsText.palette(palette))
            }
        case .chatsShowMedia:
            MacShowMediaRow(media: model.account.media, title: row.title)
        case .chatsMediaQuality:
            Picker(row.title, selection: $mediaQuality) {
                Text(BipeStrings.chatsMediaQualityStandard).tag("standard")
                Text(BipeStrings.chatsMediaQualityHigh).tag("high")
            }
        default:
            if let url = row.url(language: language) { Link(row.title, destination: url) }
        }
    }
}

private struct MacShowMediaRow: View {
    @ObservedObject var media: MacMedia
    let title: String

    var body: some View {
        Picker(title, selection: Binding(get: { media.policy ?? .off }, set: { media.setPolicy($0) })) {
            Text(BipeStrings.chatsShowMediaAlways).tag(MediaPreviews.on)
            Text(BipeStrings.chatsShowMediaPrivate).tag(MediaPreviews.private)
            Text(BipeStrings.chatsShowMediaNever).tag(MediaPreviews.off)
        }.disabled(media.policy == nil)
    }
}

/// Spec linked-devices, Mac note: the other devices of the account, read-only. Signing one out needs the phone.
private struct MacLinkedDevicesView: View {
    enum Phase { case loading, loaded, failed }
    let account: MacAccount
    @State private var phase = Phase.loading
    @State private var devices: [BipeMatrixDevice] = []

    var body: some View {
        Form {
            Section {
                switch phase {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity)
                case .failed:
                    Text(BipeStrings.linkedDevicesError).foregroundStyle(BipeColor.inkSecondary)
                    Button(BipeStrings.appRetry) { Task { await load() } }.buttonStyle(.bipeBordered)
                case .loaded:
                    if devices.isEmpty {
                        Text(BipeStrings.linkedDevicesEmpty).foregroundStyle(BipeColor.inkSecondary)
                    }
                    ForEach(devices) { device in
                        HStack(spacing: 12) {
                            Image(decorative: "desktop").renderingMode(.template).resizable()
                                .frame(width: 22, height: 22).foregroundStyle(BipeColor.glyphSecondary)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.name(device))
                                if let lastUsed = device.lastUsed {
                                    Text(BipeStrings.linkedDevicesLastUsed(lastUsed.formatted(.relative(presentation: .named))))
                                        .macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                        }
                    }
                }
            } footer: {
                Text(BipeStrings.macLinkedDevicesFooter)
            }
        }
        .formStyle(.grouped)
        .macFont(.body)
        .navigationTitle(BipeStrings.linkedDevicesTitle)
        .accessibilityIdentifier(BipeScreen.linkedDevices.rawValue)
        .task { await load() }
    }

    /// Action appear: the devices of the account without this Mac, newest use first.
    private func load() async {
        phase = .loading
        guard let client = account.client, let homeserver = URL(string: client.homeserver()) else {
            phase = .failed
            return
        }
        let matrix = BipeMatrixAccountAPI(homeserver: homeserver)
        do {
            let ownID = try? client.deviceId()
            devices = try await account.authorized { (token: String) async throws(BipeAPIError) -> [BipeMatrixDevice] in
                try await matrix.devices(accessToken: token)
            }.filter { $0.deviceId != ownID }.sorted { ($0.lastSeenTs ?? 0) > ($1.lastSeenTs ?? 0) }
            phase = .loaded
        } catch {
            phase = .failed
        }
    }

    private static func name(_ device: BipeMatrixDevice) -> String {
        guard let name = device.displayName, !name.isEmpty else { return BipeStrings.linkedDevicesUnknownName }
        return name
    }
}

/// Spec sign-out, Mac note: says what it takes to come back, then ends this account on this Mac. Other accounts stay.
private struct MacSignOutView: View {
    @ObservedObject var model: MacSettingsModel
    let workspace: MacWorkspace
    @State private var isLastDevice = false
    @State private var signingOut = false
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.macSignOutTitle).macFont(.title2).fontWeight(.semibold)
            Text(BipeStrings.macSignOutBody).macFont(.body)
            if isLastDevice && !model.hasRecoveryKey {
                Text(BipeStrings.macSignOutHistory).macFont(.body).foregroundStyle(BipeColor.trustCautionInk)
            }
            if isLastDevice && model.waysBack?.count == 0 {
                Text(BipeStrings.appSignOutNoWayBack).macFont(.body).foregroundStyle(BipeColor.trustDangerInk)
            }
            if failed {
                Text(BipeStrings.errorGeneric).macFont(.body).foregroundStyle(BipeColor.trustDangerInk)
            }
            HStack {
                Spacer()
                Button(BipeStrings.appCancel) { dismiss() }
                    .buttonStyle(.bipeBordered).keyboardShortcut(.cancelAction).disabled(signingOut)
                Button(role: .destructive) { Task { await signOut() } } label: {
                    Text(signingOut ? BipeStrings.appSigningOut : BipeStrings.accountSignOut)
                }.buttonStyle(.bipeProminent).disabled(signingOut)
            }
        }
        .padding(MacDesign.sectionGap)
        .frame(width: 460)
        .accessibilityIdentifier(BipeScreen.signOut.rawValue)
        .task { isLastDevice = (try? await model.account.client?.encryption().isLastDevice()) ?? false }
    }

    private func signOut() async {
        signingOut = true
        failed = false
        do {
            try await workspace.signOut(model.account)
            dismiss()
        } catch {
            failed = true
            signingOut = false
        }
    }
}

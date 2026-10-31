// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SafariServices
import SwiftUI
import UIKit

/// Spec settings-group: renders any group of registries/settings.json. Every row says its current value (ADR 0112).
struct SettingsGroupView: View {
    @ObservedObject var model: SettingsModel
    let group: BipeSettingsGroup
    let onSignedOut: () -> Void
    /// Opens account-new, the same flow as the account switcher (ADR 0135). Nil where no tabs host it: the add row
    /// is then left out.
    let onNewAccount: (() -> Void)?

    @AppStorage(SettingsKeys.hideRequestPhotos) private var hideRequestPhotos = true
    @AppStorage(SettingsKeys.sharePresence) private var sharePresence = true
    @AppStorage(SettingsKeys.showMedia) private var showMedia = ShowMedia.always.rawValue
    @AppStorage(Appearance.key) private var appearance = Appearance.system.rawValue
    @AppStorage(BipeSettingsRow.chatsMediaQuality.rawValue) private var mediaQuality = MediaQuality.standard.rawValue
    /// chats.liveLocation: the update distance of live location (spec settings-group notes).
    @AppStorage(LiveLocationRule.distanceKey) private var liveDistance = BipeRegistries.mapsLiveDefaultDistanceMetres
    /// chats.colors: the row value follows the palette (spec app-colors).
    private let theme = BipeTheme.shared
    @StateObject private var export: DataExportModel
    @State private var page: WebPage?
    @State private var showsSignOut = false
    @State private var showsDelete = false
    @State private var cancellingDeletion = false
    @State private var showsRecoveryKey = false
    @State private var showsEmail = false
    @State private var showsLockSetup = false
    @State private var pinCheck: PinCheckRequest?
    @State private var notice: String?
    /// Spec encryption-status, from AppTabsView. The oldMessages row opens its sheet.
    @Environment(\.encryptionStatus) private var encryption

    init(model: SettingsModel, group: BipeSettingsGroup, onSignedOut: @escaping () -> Void,
         onNewAccount: (() -> Void)? = nil) {
        self.model = model
        self.group = group
        self.onSignedOut = onSignedOut
        self.onNewAccount = onNewAccount
        _export = StateObject(wrappedValue: DataExportModel(model: model))
    }

    var body: some View {
        List {
            // The company account's owner on this phone (spec settings-group notes, bipe-chat/bipe#444).
            if group == .account, let owner = AccountList.ownerName(of: model.session.userId) {
                Section {
                    Text(BipeStrings.accountCompanyOwner(owner))
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            // Spec account-delete: the banner while a deletion this phone asked for waits.
            if group == .account, let deletion = model.account?.deletion, deletion.fromThisDevice {
                Section {
                    DeletionBanner(deletion: deletion, isCancelling: cancellingDeletion) {
                        Task { await cancelDeletion(deletion) }
                    }
                }
            }
            if group == .account, let warning = model.waysBack?.warning {
                Section {
                    Text(warning)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.trustCautionInk)
                        .listRowBackground(BipeColor.trustCautionWash)
                }
            }
            ForEach(sections, id: \.section.key) { visible in
                let section = visible.section
                Section {
                    ForEach(visible.rows, id: \.self) { row in
                        rowView(row)
                    }
                } header: {
                    if let title = section.title {
                        Text(title)
                    }
                } footer: {
                    footer(of: section)
                }
            }
        }
        .navigationTitle(group.title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.settingsGroup.rawValue)
        .sheet(item: $page) { page in
            SafariPage(url: page.url).ignoresSafeArea()
        }
        .sheet(isPresented: $showsLockSetup) {
            AppLockSetupView { model.refreshLock() }
        }
        .sheet(item: $pinCheck) { request in
            AppLockPinCheckView(title: request.title, onPass: request.onPass)
        }
        .dataExport(export)
        .sheet(isPresented: $showsRecoveryKey, onDismiss: { Task { await model.load() } }) {
            // Spec recovery-key-save, from the recovery key row.
            RecoveryKeySaveView(session: model.session) { showsRecoveryKey = false }
        }
        .sheet(isPresented: $showsEmail, onDismiss: { Task { await model.load() } }) {
            // Spec recovery-email-add, from the e-mail row of Ways back (bipe-chat/bipe#531).
            RecoveryEmailAddView(api: model.api, session: model.session, current: model.account?.email,
                                 pending: model.account?.emailPending) {}
        }
        .sheet(isPresented: $showsDelete, onDismiss: { Task { await model.load() } }) {
            AccountDeleteView(model: model)
        }
        .sheet(isPresented: $showsSignOut) {
            SignOutView(model: model, onSignedOut: onSignedOut)
                .presentationDetents([.medium, .large])
        }
        .overlay(alignment: .bottom) {
            if let notice {
                NoticeView(text: notice)
                    .task {
                        try? await Task.sleep(for: .seconds(2))
                        self.notice = nil
                    }
            }
        }
    }

    /// Sections in registry order, without hidden rows. A section whose rows are all hidden is left out.
    private var sections: [(section: BipeSettingsSection, rows: [BipeSettingsRow])] {
        group.sections.compactMap { section in
            let rows = section.rows.filter { $0.isShownOnIOS() && isAvailable($0) }
            return rows.isEmpty ? nil : (section, rows)
        }
    }

    /// Rows that exist only in some states.
    private func isAvailable(_ row: BipeSettingsRow) -> Bool {
        switch row {
        // Exists only while the app lock is on and the phone has biometrics (spec settings-group notes).
        case .accountBiometricUnlock: model.appLockOn && OwnerCheck.biometricKind != .none
        // While a deletion waits, the banner (or the alert on the other devices) takes its place.
        case .accountDelete: model.account?.deletion == nil
        case .accountAdd: onNewAccount != nil
        // Shown while the model says needsApproval; OldMessagesRow follows it (bipe-chat/bipe#793).
        case .accountOldMessages: encryption != nil
        default: true
        }
    }

    @ViewBuilder
    private func footer(of section: BipeSettingsSection) -> some View {
        if section.key == "pin" {
            Text(BipeStrings.accountPinFooter(String(BipeRegistries.pinLength)))
        } else if group == .help {
            VStack(alignment: .leading, spacing: 8) {
                if let footer = section.footer {
                    Text(footer)
                }
                // Action longPressVersion: copies the Matrix device ID, which never shows as text (ADR 0112).
                Text(BipeStrings.helpVersion(AppVersion.display))
                    .onLongPressGesture { copyDeviceId() }
                    // The copy without the long press (bipe#499).
                    .accessibilityAction(named: BipeStrings.a11yCopyDeviceId) { copyDeviceId() }
            }
        } else if let footer = section.footer {
            Text(footer)
        }
    }

    @ViewBuilder
    private func rowView(_ row: BipeSettingsRow) -> some View {
        switch row {
        case .profileNamePhoto:
            NavigationLink(value: PeopleRoute.profileEdit) { SettingsRowLabel(title: row.title) }
        case .profileHandle:
            NavigationLink(value: PeopleRoute.profileLink) { SettingsRowLabel(title: row.title) }
        case .profilePublicProfile:
            NavigationLink(value: PeopleRoute.publicProfile) { SettingsRowLabel(title: row.title) }
        case .privacyBlocked:
            NavigationLink(value: PeopleRoute.blockedList) { SettingsRowLabel(title: row.title) }
        case .privacyContacts:
            NavigationLink(value: PeopleRoute.contacts) { SettingsRowLabel(title: row.title) }
        case .privacyMessaging:
            NavigationLink {
                MessagingPickerView(model: model)
            } label: {
                SettingsRowLabel(title: row.title, value: model.whoCanMessage?.settingsSummary, footer: row.footer)
            }
        case .privacyRequestPhotos:
            Toggle(isOn: $hideRequestPhotos) { SettingsRowLabel(title: row.title, footer: row.footer) }
        case .privacyPresence:
            Toggle(isOn: $sharePresence) { SettingsRowLabel(title: row.title, footer: row.footer) }
        case .privacyDiscovery:
            // Spec contacts notes: the switch needs a linked number; without one the row says bipe_phone_not_linked.
            Toggle(isOn: Binding(get: { model.discoverableByPhone ?? false }, set: { isOn in
                Task {
                    if await model.setDiscoverableByPhone(isOn) {
                        BipeHaptics.play(.tick)
                    }
                }
            })) {
                SettingsRowLabel(title: row.title,
                                 footer: model.phone == .notLinked ? BipeStrings.phoneNotLinked : row.footer)
            }
            .disabled(model.discoverableByPhone == nil || model.phone == nil || model.phone == .notLinked)
        case .privacyAssistantSend:
            // ADR 0210: off by default; on lets the user's own assistant send as the user, with a label in the chat.
            Toggle(isOn: Binding(get: { model.assistantSend ?? false }, set: { isOn in
                Task {
                    if await model.setAssistantSend(isOn) {
                        BipeHaptics.play(.tick)
                    }
                }
            })) {
                SettingsRowLabel(title: row.title, footer: row.footer)
            }
            .disabled(model.assistantSend == nil)
        case .accountChangePin:
            NavigationLink {
                PinChangeView(model: model)
            } label: {
                SettingsRowLabel(title: row.title)
            }
        case .accountAppLock:
            Toggle(isOn: Binding(get: { model.appLockOn }, set: { isOn in
                if isOn {
                    showsLockSetup = true
                } else {
                    Task { await turnLockOff() }
                }
            })) {
                SettingsRowLabel(title: row.title)
            }
        case .accountBiometricUnlock:
            Toggle(isOn: Binding(get: { model.biometricUnlockOn }, set: { isOn in
                Task { await setBiometricUnlock(isOn) }
            })) {
                SettingsRowLabel(title: BipeStrings.accountBiometricUnlock(OwnerCheck.biometricWord))
            }
        case .accountLinkedDevices:
            NavigationLink {
                LinkedDevicesView(model: model)
            } label: {
                SettingsRowLabel(title: row.title)
            }
        case .accountLinkedAccounts:
            NavigationLink {
                LinkedAccountsView(settings: model)
            } label: {
                SettingsRowLabel(title: row.title, value: linkedSummary)
            }
        case .accountTheftProtection:
            NavigationLink {
                TheftProtectionView(settings: model)
            } label: {
                SettingsRowLabel(title: row.title)
            }
        case .helpReportProblem:
            NavigationLink {
                ProblemReportView(model: model)
            } label: {
                SettingsRowLabel(title: row.title)
            }
        case .chatsColors:
            NavigationLink {
                AppColorsView()
            } label: {
                SettingsRowLabel(title: row.title, value: theme.palette.title, footer: row.footer)
            }
        // bipe#676: appearance and media quality, kept on the phone.
        case .chatsAppearance:
            NavigationLink {
                ChoiceListView(title: row.title,
                               options: Appearance.allCases.map { (id: $0.rawValue, title: $0.title) },
                               selection: $appearance)
            } label: {
                SettingsRowLabel(title: row.title, value: Appearance(rawValue: appearance)?.title)
            }
        case .chatsMediaQuality:
            NavigationLink {
                ChoiceListView(title: row.title,
                               options: MediaQuality.allCases.map { (id: $0.rawValue, title: $0.title) },
                               selection: $mediaQuality)
            } label: {
                SettingsRowLabel(title: row.title, value: MediaQuality(rawValue: mediaQuality)?.title)
            }
        case .chatsShowMedia:
            NavigationLink {
                ChoiceListView(title: row.title,
                               options: ShowMedia.allCases.map { (id: $0.rawValue, title: $0.title) },
                               selection: $showMedia)
            } label: {
                SettingsRowLabel(title: row.title, value: ShowMedia(rawValue: showMedia)?.title)
            }
        case .chatsLiveLocation:
            NavigationLink {
                ChoiceListView(title: row.title,
                               options: BipeRegistries.mapsLiveDistancesMetres.map { (id: String($0), title: Self.distanceLabel($0)) },
                               selection: Binding(get: { String(LiveLocationRule.distance) },
                                                  set: { liveDistance = Int($0) ?? BipeRegistries.mapsLiveDefaultDistanceMetres }))
            } label: {
                SettingsRowLabel(title: row.title, value: Self.distanceLabel(LiveLocationRule.distance), footer: row.footer)
            }
        case .chatsDataUse:
            NavigationLink {
                DataUseView(session: model.session)
            } label: {
                SettingsRowLabel(title: row.title)
            }
        case .helpLicenses:
            NavigationLink {
                OpenSourceLicencesView()
            } label: {
                SettingsRowLabel(title: row.title)
            }
        case .helpSupport:
            NavigationLink(value: SettingsRoute.support) { SettingsRowLabel(title: row.title) }
        case .accountOldMessages:
            if let encryption {
                OldMessagesRow(model: encryption) { tapButton(row) }
            }
        default:
            switch row.kind {
            case .switch:
                // Saved on the server or in the lock store; wired by the screen that owns it.
                Toggle(isOn: .constant(false)) { SettingsRowLabel(title: row.title, footer: row.footer) }
                    .disabled(true)
            case .info:
                SettingsRowLabel(title: row.title, value: value(of: row), footer: row.footer,
                                 isWarning: row == .privacyNumber && model.phone?.needsAttention == true)
            default:
                tapButton(row)
            }
        }
    }

    /// A link, choice, url, action or destructive row: a button that runs tapRow.
    private func tapButton(_ row: BipeSettingsRow) -> some View {
        Button {
            tap(row)
        } label: {
            HStack {
                SettingsRowLabel(title: row.title,
                                 value: value(of: row),
                                 footer: row.footer,
                                 isDestructive: row.kind == .destructive)
                if row.kind == .link || row.kind == .choice {
                    Spacer()
                    PhosphorIcon.caretRight.image
                        .resizable()
                        .frame(width: 14, height: 14)
                        .foregroundStyle(BipeColor.glyphSecondary)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// The linked providers, for the linkedAccounts row (ADR 0096).
    private var linkedSummary: String? {
        guard let links = model.account?.links, !links.isEmpty else { return nil }
        return links.map { $0.provider == .apple ? BipeStrings.linkedApple : BipeStrings.linkedGoogle }.joined(separator: ", ")
    }

    /// The current value of a row, formatted (spec settings-group, SettingsRow.value).
    private func value(of row: BipeSettingsRow) -> String? {
        switch row {
        case .privacyReadReceipts: BipeStrings.privacyReadReceiptsValue
        case .accountWaysBackStatus: model.waysBack?.summary
        case .privacyNumber: model.phone?.summary(number: model.phoneNumber)
        case .accountEmail: model.account.map { $0.email ?? BipeStrings.accountEmailNone }
        case .accountRecoveryKey: model.hasRecoveryKey ? BipeStrings.accountRecoveryKeySaved : BipeStrings.accountRecoveryKeyMissing
        case .accountThisDevice: UIDevice.current.model
        default: nil
        }
    }

    /// Action tapRow.
    private func tap(_ row: BipeSettingsRow) {
        if row.kind == .url, let url = row.url(language: AppVersion.language) {
            page = WebPage(url: url)
            return
        }
        switch row {
        case .accountSignOut:
            showsSignOut = true
        case .accountDelete:
            showsDelete = true
        case .accountRecoveryKey:
            showsRecoveryKey = true
        case .accountOldMessages:
            // Spec encryption-status, action openSheet: the same sheet as the chat list banner.
            encryption?.openSheet()
        case .accountEmail:
            showsEmail = true
        case .accountAdd:
            // account-new shows the cap on the personal choice when the phone is full, as from the switcher.
            onNewAccount?()
        case .privacyExport:
            Task { await export.start() }
        case .chatsLanguage:
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        default:
            // The screens of the other rows come with their own specs (see settings-group notes).
            break
        }
    }

    /// Spec account-delete, action cancelPending: the owner check, then the cancel. A phone with no passcode cancels
    /// without the check: a cancel only keeps the account.
    private func cancelDeletion(_ deletion: BipeAccountDeletion) async {
        if OwnerCheck.canCheckOwner {
            guard await OwnerCheck.owner(reason: BipeStrings.deletionCancelReason) else { return }
        }
        cancellingDeletion = true
        let cancelled = await model.cancelDeletion(deletion)
        cancellingDeletion = false
        notice = cancelled ? BipeStrings.deletionCancelled : BipeStrings.deletionCancelError
    }

    /// Spec app-lock, action turnOff: the owner check, or the app lock PIN on a phone with no passcode.
    private func turnLockOff() async {
        let remove = {
            AppLockModel.shared.store.remove()
            AppLockModel.shared.unlockForGood()
            model.refreshLock()
        }
        if OwnerCheck.canCheckOwner {
            if await OwnerCheck.owner(reason: BipeStrings.accountAppLockOffReason) {
                remove()
            }
        } else {
            pinCheck = PinCheckRequest(title: BipeStrings.accountAppLock, onPass: remove)
        }
        model.refreshLock()
    }

    /// The biometricUnlock switch: turning it on asks for biometrics once.
    private func setBiometricUnlock(_ isOn: Bool) async {
        if isOn {
            guard await OwnerCheck.biometrics(reason: BipeStrings.appLockBiometric(OwnerCheck.biometricWord)) else {
                model.refreshLock()
                return
            }
        }
        AppLockModel.shared.store.setBiometricUnlock(isOn)
        model.refreshLock()
    }

    /// "A cada 50 m" (bipe_chats_live_location_distance).
    private static func distanceLabel(_ metres: Int) -> String {
        BipeStrings.chatsLiveLocationDistance("\(metres) m")
    }

    private func copyDeviceId() {
        guard let deviceId = try? model.session.client.deviceId() else { return }
        UIPasteboard.general.string = deviceId
        BipeHaptics.play(.tap)
        notice = BipeStrings.helpDeviceIdCopied
    }
}

/// Spec settings-group, row oldMessages: shown only while this phone needs approval for old messages
/// (EncryptionStatusModel.needsApproval). Skip on the banner does not hide it. It goes when the phone is verified.
private struct OldMessagesRow<Content: View>: View {
    @ObservedObject var model: EncryptionStatusModel
    @ViewBuilder let label: () -> Content

    var body: some View {
        if model.needsApproval {
            label()
        }
    }
}

/// Keys of the settings that live on this phone.
enum SettingsKeys {
    static let hideRequestPhotos = "bipe.settings.hideRequestPhotos"
    static let sharePresence = "bipe.settings.sharePresence"
    static let showMedia = "bipe.settings.showMedia"
}

/// The chats.appearance choice (bipe#676): system, light or dark for the whole app, on this phone.
enum Appearance: String, CaseIterable {
    case system
    case light
    case dark

    static let key = BipeSettingsRow.chatsAppearance.rawValue

    var title: String {
        switch self {
        case .system: BipeStrings.chatsAppearanceSystem
        case .light: BipeStrings.chatsAppearanceLight
        case .dark: BipeStrings.chatsAppearanceDark
        }
    }

    /// nil follows the phone.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

extension MediaQuality {
    var title: String {
        switch self {
        case .standard: BipeStrings.chatsMediaQualityStandard
        case .high: BipeStrings.chatsMediaQualityHigh
        }
    }
}

/// The chats.showMedia choice: when chats load photos and videos by themselves.
enum ShowMedia: String, CaseIterable {
    case always
    case `private`
    case never

    var title: String {
        switch self {
        case .always: BipeStrings.chatsShowMediaAlways
        case .private: BipeStrings.chatsShowMediaPrivate
        case .never: BipeStrings.chatsShowMediaNever
        }
    }
}

/// A picker of one choice that lives on this phone.
struct ChoiceListView: View {
    let title: String
    let options: [(id: String, title: String)]
    @Binding var selection: String

    var body: some View {
        List {
            ForEach(options, id: \.id) { option in
                Button {
                    selection = option.id
                    BipeHaptics.play(.tick)
                } label: {
                    HStack {
                        Text(option.title)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Spacer()
                        if selection == option.id {
                            PhosphorIcon.check.image
                                .resizable()
                                .frame(width: 18, height: 18)
                                .foregroundStyle(BipeColor.accentInk)
                                .accessibilityHidden(true)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == option.id ? .isSelected : [])
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The app version and the app language.
enum AppVersion {
    /// The version name (version.json, ADR 0163), for the remote banner's min_app_version.
    static var name: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    /// The version name, build and commit, for bipe_help_version: "26.10.2 (141) 5481bba" (tools/version.mjs).
    static var display: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        let commit = info?["BipeCommit"] as? String ?? ""
        return commit.isEmpty ? "\(version) (\(build))" : "\(version) (\(build)) \(commit)"
    }

    /// "pt" or "en", for the url rows of registries/settings.json.
    static var language: String {
        Bundle.main.preferredLocalizations.first?.hasPrefix("en") == true ? "en" : "pt"
    }
}

struct SettingsRowLabel: View {
    let title: String
    var value: String?
    var footer: String?
    var isDestructive = false
    /// The caution colour and mark on the value (spec phone-link-status).
    var isWarning = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .foregroundStyle(isDestructive ? BipeColor.trustDangerInk : BipeColor.inkPrimary)
            if let value {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    if isWarning {
                        PhosphorIcon.warningCircle.image
                            .resizable()
                            .frame(width: 14, height: 14)
                            .foregroundStyle(BipeColor.trustCautionInk)
                            .accessibilityHidden(true)
                    }
                    Text(value)
                        .font(.subheadline)
                        .foregroundStyle(isWarning ? BipeColor.trustCautionInk : BipeColor.inkSecondary)
                }
            }
            if let footer {
                Text(footer)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
        .padding(.vertical, 2)
    }
}

/// The who-can-message picker of the privacy group (PUT /v1/me/messaging).
private struct MessagingPickerView: View {
    @ObservedObject var model: SettingsModel
    @State private var saving = false
    @State private var failed = false

    var body: some View {
        List {
            ForEach(BipeWhoCanMessage.allCases, id: \.self) { option in
                Button {
                    Task { await save(option) }
                } label: {
                    HStack {
                        SettingsRowLabel(title: Self.title(option), footer: Self.detail(option))
                        Spacer()
                        if model.whoCanMessage == option {
                            PhosphorIcon.check.image
                                .resizable()
                                .frame(width: 18, height: 18)
                                .foregroundStyle(BipeColor.accentInk)
                                .accessibilityHidden(true)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(saving)
                .accessibilityAddTraits(model.whoCanMessage == option ? .isSelected : [])
            }
            if failed {
                Text(BipeStrings.errorGeneric)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
        }
        .navigationTitle(BipeStrings.messagingTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func save(_ option: BipeWhoCanMessage) async {
        saving = true
        failed = !(await model.setWhoCanMessage(option))
        if !failed {
            BipeHaptics.play(.tick)
        }
        saving = false
    }

    private static func title(_ option: BipeWhoCanMessage) -> String {
        switch option {
        case .everyone: BipeStrings.messagingEveryone
        case .verified: BipeStrings.messagingVerified
        case .contacts: BipeStrings.messagingContacts
        }
    }

    private static func detail(_ option: BipeWhoCanMessage) -> String {
        switch option {
        case .everyone: BipeStrings.messagingEveryoneBody
        case .verified: BipeStrings.messagingVerifiedBody
        case .contacts: BipeStrings.messagingContactsBody
        }
    }
}

struct WebPage: Identifiable {
    let url: URL
    var id: URL { url }
}

/// The in-app browser for the url rows.
struct SafariPage: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

/// A short notice at the bottom of the screen.
struct NoticeView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(BipeColor.inkOnAccent)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(BipeColor.inkPrimary))
            .padding(.bottom, 24)
            .transition(.opacity)
    }
}

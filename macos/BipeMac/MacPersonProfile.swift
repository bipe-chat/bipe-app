// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import SwiftUI

/// The person whose profile sheet shows, and the DM it opened from (spec person-profile fromDm).
struct MacProfileTarget: Identifiable, Equatable {
    let userID: String
    /// The DM the sheet opened from; nil from elsewhere.
    let dmRoomID: String?
    var id: String { userID }
}

/// Spec person-profile on the Mac (bipe-chat/bipe#1225). The trust rules come from the shared PersonTrust.
@MainActor
final class MacPersonProfile: ObservableObject {
    enum Phase: Equatable { case loading, loaded, failed }

    let userID: String
    let fromDm: Bool
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var name: String
    @Published private(set) var avatarURL: String?
    @Published private(set) var handle: String?
    @Published private(set) var bio: String?
    @Published private(set) var socials: [SocialLink] = []
    /// Client.getDmRoom through the ADR 0141 rule: the newest DM with the person.
    @Published private(set) var dmRoomID: String?
    /// ADR 0049 facts lines; empty when the call failed.
    @Published private(set) var factLines: [String] = []
    @Published private(set) var identity: BipeTrustSources.Identity?
    @Published private(set) var identityRead = false
    @Published private(set) var loadedProofs: [BipePersonProof] = []
    /// Groups where the account may invite (action addToGroup); nil until read.
    @Published private(set) var inviteTargets: [InviteTarget]?
    /// A one-line notice after an action (invite sent, refused, already a member, a failed message).
    @Published var notice: String?
    @Published private(set) var opening = false

    init(target: MacProfileTarget) {
        userID = target.userID
        fromDm = target.dmRoomID != nil
        dmRoomID = target.dmRoomID
        name = BipeChatRowName.person(userID: target.userID, displayName: nil, handle: nil)
    }

    func trust(account: MacAccount) -> PersonTrust? {
        guard let seals = account.seals else { return nil }
        return PersonTrust(userId: userID, ownUserId: account.userId, displayName: name, seals: seals,
                           identity: identity, identityRead: identityRead, loadedProofs: loadedProofs)
    }

    /// Action appear: shows what it has and fills in the rest.
    func appear(account: MacAccount) async {
        guard let client = account.client else { phase = .failed; return }
        phase = .loading
        if dmRoomID == nil { dmRoomID = try? await MacDmSelection.roomID(with: userID, client: client) }
        do {
            let profile = try await client.getProfile(userId: userID)
            handle = account.handles?.handle(for: userID)
            name = BipeChatRowName.person(userID: userID, displayName: profile.displayName, handle: handle)
            avatarURL = profile.avatarUrl
        } catch {
            phase = .failed
            return
        }
        phase = .loaded
        await readIdentity(client: client)
        if let lines = await account.profileFactLines(userID) { factLines = lines }
        if let seals = account.seals { loadedProofs = await seals.proofs(for: userID) }
        if let handles = account.handles {
            if handle == nil, let resolved = await handles.resolve(userID) {
                handle = resolved
                name = BipeChatRowName.person(userID: userID, displayName: name, handle: resolved)
            }
            if let profile = await handles.publicProfile(for: userID) {
                let text = profile.bio?.trimmingCharacters(in: .whitespacesAndNewlines)
                bio = text?.isEmpty == false ? text : nil
                socials = SocialLink.links(profile.socials)
            }
        }
        inviteTargets = await InviteTarget.all(client: client)
    }

    /// Action retryFacts (and part of appear): reads the person's identity again.
    func readIdentity(client: Client) async {
        identity = await PersonTrust.identity(client: client, userId: userID)
        identityRead = true
    }

    /// Action message: the DM, created when missing (ADR 0141 power levels). Returns the room to open.
    func message(account: MacAccount) async -> String? {
        guard let client = account.client, !opening else { return nil }
        opening = true
        defer { opening = false }
        do {
            if let existing = try await MacDmSelection.roomID(with: userID, client: client) { return existing }
            let roomID = try await NativeDm.create(with: userID, client: client)
            dmRoomID = roomID
            return roomID
        } catch NativeDm.CreateError.inviteRefused {
            notice = BipeStrings.appGroupInviteRefused(name)
        } catch NativeDm.CreateError.limitExceeded {
            notice = await ReachNotice.load(api: account.api, accessToken: { try? account.client?.session().accessToken })
        } catch {
            notice = BipeStrings.errorGeneric
        }
        return nil
    }

    /// Action addToGroup, step 2: invites the person to the chosen group.
    func invite(to target: InviteTarget, account: MacAccount) async {
        guard let client = account.client else { return }
        switch await target.invite(userID, client: client) {
        case .sent: notice = BipeStrings.dmInviteToGroupSent
        case .alreadyMember: notice = BipeStrings.dmInviteToGroupAlreadyMember
        case .refused: notice = BipeStrings.appGroupInviteRefused(name)
        case .limitExceeded: notice = await ReachNotice.load(api: account.api, accessToken: { try? account.client?.session().accessToken })
        case .failed: notice = BipeStrings.errorGeneric
        }
    }
}

/// Spec person-profile: the sheet with photo, name, @, trust marks, facts, bio and socials, and the actions
/// message, share, add to group, notifications (from a DM), block and report.
struct MacPersonProfileSheet: View {
    @ObservedObject var account: MacAccount
    @ObservedObject var blocked: MacBlockedUsers
    @StateObject private var model: MacPersonProfile
    /// Opens a chat of this account in the window.
    let openChat: (String) -> Void
    /// fromDm only: opens chat-notifications for the DM.
    let openNotifications: ((String) -> Void)?
    /// The toast of a sent report.
    let reported: (String) -> Void
    /// dm-delete ran from report-chat: the room and its marker.
    let cleared: (String, UInt64) -> Void
    let close: () -> Void
    /// One sheet at a time: block-person or report-chat.
    private enum Sheet: Identifiable { case block, report; var id: Self { self } }
    @State private var sheet: Sheet?

    init(account: MacAccount, target: MacProfileTarget, openChat: @escaping (String) -> Void,
         openNotifications: ((String) -> Void)? = nil, reported: @escaping (String) -> Void,
         cleared: @escaping (String, UInt64) -> Void, close: @escaping () -> Void) {
        self.account = account
        blocked = account.blocked
        _model = StateObject(wrappedValue: MacPersonProfile(target: target))
        self.openChat = openChat
        self.openNotifications = openNotifications
        self.reported = reported
        self.cleared = cleared
        self.close = close
    }

    private var trust: PersonTrust? { model.trust(account: account) }
    private var isOwn: Bool { model.userID == account.userId }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(model.fromDm ? BipeStrings.dmDetailsTitle : model.name).macFont(.headline).lineLimit(1)
                Spacer()
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.cancelAction)
            }.padding(MacDesign.gap)
            Divider()
            ScrollView {
                VStack(spacing: MacDesign.gap) {
                    switch model.phase {
                    case .loading:
                        header
                        ProgressView().padding()
                    case .failed:
                        MacNotice(text: BipeStrings.appPersonNotFound, danger: true)
                        Button(BipeStrings.appRetry) { Task { await model.appear(account: account) } }
                    case .loaded:
                        header
                        factsList
                        if let bio = model.bio { bioBlock(bio) }
                        socialsBlock
                        if let notice = model.notice { MacNotice(text: notice) }
                        if !isOwn { actions }
                    }
                }
                .padding(MacDesign.pageInset)
                .frame(maxWidth: .infinity)
            }
        }
        .frame(width: 460, height: 620)
        .background(BipeColor.surfaceBase)
        .environment(\.bipeCompanySeals, account.seals)
        .accessibilityIdentifier(BipeScreen.personProfile.rawValue)
        .task { await model.appear(account: account) }
        .sheet(item: $sheet) { shown in
            switch shown {
            case .block:
                MacBlockConfirm(blocked: blocked, userID: model.userID, name: model.name,
                                unblocking: blocked.isBlocked(model.userID)) { sheet = nil }
            case .report:
                reportSheet
            }
        }
    }

    @ViewBuilder private var reportSheet: some View {
            if let roomID = model.dmRoomID, let client = account.client, let room = try? client.getRoom(roomId: roomID) {
                MacReportChatSheet(room: room, client: client, isDm: true, otherUserID: model.userID, blocked: blocked,
                                   sent: { text in reported(text) },
                                   cleared: { room, marker in cleared(room, marker) },
                                   close: { sheet = nil })
            }
    }

    private var header: some View {
        VStack(spacing: 8) {
            MacAvatar(name: model.name, isCompany: trust?.isCompany ?? false, media: account.media, url: model.avatarURL,
                      userID: model.userID, size: 96)
            HStack(spacing: 6) {
                Text(model.name).macFont(.title2).fontWeight(.bold).multilineTextAlignment(.center)
                if BipeOfficialAccounts.isOfficial(model.userID) { MacOfficialBadge(style: .footnote) }
                if trust?.facts.identity == "verified" { MacSealMark(userID: model.userID, size: 22) }
            }
            if let handle = model.handle {
                Text("@\(handle)").macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary).textSelection(.enabled)
            }
            if model.userID == BipeRegistries.groupsAccountID {
                Text(BipeStrings.groupsServiceAbout).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    /// Spec person-profile, list of facts (ADR 0275 points 12 to 16): warnings, identity, the facts, then the confirmation.
    @ViewBuilder private var factsList: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let trust {
                if trust.reservedNameWarning {
                    markLine("warning", BipeStrings.warningReservedName, BipeColor.trustCautionInk)
                }
                ForEach(trust.marks.filter { $0.kind != .confirmedInPerson }) { mark in markLine(mark) }
                ForEach(trust.proofLines) { proof in
                    markLine(proof.isFill ? "seal-check-fill" : "seal-check", proof.line, BipeColor.trustVerifiedInk)
                }
                if trust.showsUnverifiedLine {
                    markLine("user-circle", BipeStrings.bannerUnverifiedTitle, BipeColor.trustNeutralInk)
                }
            }
            if !isOwn {
                ForEach(Array(model.factLines.enumerated()), id: \.offset) { _, line in
                    Text(line).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            if let trust {
                ForEach(trust.marks.filter { $0.kind == .confirmedInPerson }) { mark in markLine(mark) }
                trustFacts(trust)
            }
            if blocked.isBlocked(model.userID) {
                markLine("prohibit", BipeStrings.appPersonBlocked, BipeColor.trustDangerInk)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Spec facts: what each fact means, the contact not confirmed yet, a change and an unknown state, each apart.
    @ViewBuilder private func trustFacts(_ trust: PersonTrust) -> some View {
        let facts = trust.facts
        if facts.identity == "verified" { caption(BipeStrings.trustIdentityBody) }
        if trust.personSeal == .suspended { caption(BipeStrings.badgeSuspendedBody) }
        if facts.contact == "confirmed" {
            caption(BipeStrings.trustContactBody)
        } else if facts.change != "unknown" {
            markLine("user-circle", BipeStrings.trustContactUnconfirmed, BipeColor.trustNeutralInk)
        }
        if facts.change == "identity" || facts.change == "device" || facts.change == "device_and_identity" {
            VStack(alignment: .leading, spacing: 4) {
                markLine("warning", BipeStrings.bannerSecurityChange, BipeColor.trustCautionInk)
                Text(BipeStrings.trustChangeConfirmAction).macFont(.footnote)
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 10).fill(BipeColor.trustCautionWash))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(BipeColor.trustCautionLine))
            .accessibilityElement(children: .combine)
        }
        if trust.lifecycleUnknown {
            markLine("info", BipeStrings.trustLifecycleUnknown, BipeColor.trustNeutralInk)
            Button(BipeStrings.trustLifecycleRetryAction) {
                Task { if let client = account.client { await model.readIdentity(client: client) } }
            }.buttonStyle(.link).macFont(.footnote)
        }
        if facts.identity == "verified" || facts.contact == "confirmed" { caption(BipeStrings.trustWarningAbsenceBody) }
    }

    private func bioBlock(_ bio: String) -> some View {
        VStack(spacing: 4) {
            Text(bio).macFont(.body).multilineTextAlignment(.center).textSelection(.enabled)
            Text(BipeStrings.publicProfileNotVerified).macFont(.caption).foregroundStyle(BipeColor.inkSecondary)
        }
    }

    /// Spec socials (ADR 0228): one row per network; a click opens the link in the system browser, never in the app.
    @ViewBuilder private var socialsBlock: some View {
        if !model.socials.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(BipeStrings.publicProfileSocials).macFont(.headline)
                ForEach(model.socials) { link in
                    Button { NSWorkspace.shared.open(link.url) } label: {
                        HStack(spacing: 8) {
                            Text("\(link.platform.name) · @\(link.handle)")
                            Spacer(minLength: 8)
                            Image(decorative: "link").renderingMode(.template).resizable().frame(width: 16, height: 16)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                Text(BipeStrings.publicProfileSocialsNotVerified).macFont(.caption).foregroundStyle(BipeColor.inkSecondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !model.fromDm {
                Button {
                    Task { if let room = await model.message(account: account) { close(); openChat(room) } }
                } label: { Label(BipeStrings.appPersonMessage, image: "chat-circle") }
                    .disabled(model.opening)
            }
            if let link = model.handle.flatMap(BipeProfileLink.url(handle:)) {
                ShareLink(item: link) { Label(BipeStrings.profileShare, image: "share-network") }
            }
            if let targets = model.inviteTargets {
                Menu {
                    if targets.isEmpty { Text(BipeStrings.dmInviteToGroupEmpty) }
                    ForEach(targets) { target in
                        Button(target.name) { Task { await model.invite(to: target, account: account) } }
                    }
                } label: { Label(BipeStrings.dmInviteToGroup, image: "user-plus") }
                    .fixedSize()
            }
            if let roomID = model.dmRoomID, model.fromDm, let openNotifications {
                Button { openNotifications(roomID) } label: { Label(BipeStrings.settingsNotifications, image: "bell") }
            }
            Divider()
            Button(role: .destructive) { sheet = .block } label: {
                Label(blocked.isBlocked(model.userID) ? BipeStrings.actionUnblockPerson : BipeStrings.actionBlockPerson,
                      image: "prohibit")
            }
            // Spec report: without a DM there is nothing to report, and the row is hidden.
            if model.dmRoomID != nil {
                Button(role: .destructive) { sheet = .report } label: { Label(BipeStrings.dmReport, image: "flag") }
            }
        }
        .buttonStyle(.borderless)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func markLine(_ mark: TrustMark) -> some View {
        switch mark.kind {
        case .verifiedName, .company, .confirmedInPerson: markLine("seal-check", mark.text, BipeColor.trustVerifiedInk)
        case .companyRevoked: markLine("prohibit", mark.text, BipeColor.trustDangerInk)
        case .companySuspended, .nameSuspended: markLine("seal-warning", mark.text, BipeColor.trustCautionInk)
        }
    }

    private func markLine(_ icon: String, _ text: String, _ tint: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(decorative: icon).renderingMode(.template).resizable().frame(width: 16, height: 16).foregroundStyle(tint)
            Text(text).macFont(.subheadline).foregroundStyle(BipeColor.inkPrimary).fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private func caption(_ text: String) -> some View {
        Text(text).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary).fixedSize(horizontal: false, vertical: true)
    }
}

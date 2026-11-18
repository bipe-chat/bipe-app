// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec group-details on the Mac: name, description and member count, editing of name and description.
/// The members come from the shared GroupMembersModel, the leave rules from the shared GroupLeaveModel.
@MainActor
final class MacGroupDetailsModel: ObservableObject {
    enum Phase: Equatable { case viewing, editing, saving }

    let room: Room
    @Published private(set) var name = ""
    @Published private(set) var description: String?
    @Published private(set) var memberCount = 0
    @Published private(set) var canEdit = false
    @Published private(set) var phase = Phase.viewing
    @Published var showsSaveError = false
    @Published var editName = ""
    @Published var editDescription = ""

    init(room: Room) { self.room = room }

    /// Action appear: RoomInfo and the power levels, then the RoomInfo updates.
    func appear() async {
        if let levels = try? await room.getPowerLevels() {
            canEdit = levels.canOwnUserSendState(stateEvent: .roomName)
        }
        if let info = try? await room.roomInfo() { apply(info) }
        let stream = RoomInfoStream()
        let handle = room.subscribeToRoomInfoUpdates(listener: stream)
        defer { handle.cancel() }
        for await info in stream.updates { apply(info) }
    }

    func edit() {
        guard canEdit, phase == .viewing else { return }
        editName = name
        editDescription = description ?? ""
        phase = .editing
    }

    func cancelEdit() { phase = .viewing }

    var canSave: Bool { phase == .editing && !editName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// Action save: sends only what changed.
    func save() async {
        guard canSave else { return }
        phase = .saving
        do {
            let newName = editName.trimmingCharacters(in: .whitespacesAndNewlines)
            if newName != name { try await room.setName(name: newName) }
            let newDescription = editDescription.trimmingCharacters(in: .whitespacesAndNewlines)
            if newDescription != (description ?? "") { try await room.setTopic(topic: newDescription) }
            phase = .viewing
        } catch {
            phase = .editing
            showsSaveError = true
        }
    }

    private func apply(_ info: RoomInfo) {
        let own = room.ownUserId()
        let heroes: [(userID: String, displayName: String?)]? = info.rawName == nil
            ? info.heroes.filter { $0.userId != own }.map { ($0.userId, $0.displayName) } : nil
        name = BipeChatRowName.resolve(roomID: info.id, sdkName: info.displayName, peerName: nil, peerID: nil,
                                       unnamed: BipeStrings.groupCommonNoRoomName, groupHeroes: heroes)
        description = info.topic.flatMap { $0.isEmpty ? nil : $0 }
        memberCount = Int(info.joinedMembersCount)
    }
}

/// Spec group-details, group-members, group-leave and report-chat (group) on the Mac (bipe-chat/bipe#1163, #1225).
struct MacGroupDetailsSheet: View {
    @ObservedObject var account: MacAccount
    let face: MacAvatarDescriptor?
    @StateObject private var details: MacGroupDetailsModel
    @StateObject private var members: GroupMembersModel
    @StateObject private var leave: GroupLeaveModel
    let openProfile: (String) -> Void
    let openNotifications: (String) -> Void
    /// The toast of a sent report.
    let reported: (String) -> Void
    /// The account left the group.
    let left: (String) -> Void
    let close: () -> Void

    /// One sheet or confirm at a time.
    private enum Sheet: Identifiable {
        case report, leave
        case member(GroupMembersModel.MemberAction, GroupMember)
        var id: String {
            switch self {
            case .report: "report"
            case .leave: "leave"
            case .member(let action, let member): "\(action)-\(member.id)"
            }
        }
    }
    @State private var sheet: Sheet?
    @State private var query = ""

    init(account: MacAccount, room: Room, client: Client, face: MacAvatarDescriptor?,
         openProfile: @escaping (String) -> Void, openNotifications: @escaping (String) -> Void,
         reported: @escaping (String) -> Void, left: @escaping (String) -> Void, close: @escaping () -> Void) {
        self.account = account
        self.face = face
        _details = StateObject(wrappedValue: MacGroupDetailsModel(room: room))
        _members = StateObject(wrappedValue: GroupMembersModel(client: client, ownUserId: account.userId, roomId: room.id()))
        _leave = StateObject(wrappedValue: GroupLeaveModel(client: client, ownUserId: account.userId, roomId: room.id()))
        self.openProfile = openProfile
        self.openNotifications = openNotifications
        self.reported = reported
        self.left = left
        self.close = close
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(BipeStrings.appGroupDetailsTitle).macFont(.headline)
                Spacer()
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.cancelAction)
            }.padding(MacDesign.gap)
            Divider()
            List {
                Section { header }
                Section {
                    Button { openNotifications(details.room.id()) } label: { Label(BipeStrings.settingsNotifications, image: "bell") }
                }
                membersSections
                Section {
                    Button(role: .destructive) { sheet = .report } label: { Label(BipeStrings.appReportGroup, image: "flag") }
                    Button(role: .destructive) { sheet = .leave } label: { Label(BipeStrings.appGroupLeaveAction, image: "sign-out") }
                }
            }
            .listStyle(.inset)
        }
        .frame(width: 520, height: 680)
        .background(BipeColor.surfaceBase)
        .environment(\.bipeCompanySeals, account.seals)
        .accessibilityIdentifier(BipeScreen.groupDetails.rawValue)
        .task { await details.appear() }
        .task { await members.appear() }
        .task { await leave.appear() }
        .onChange(of: query) { _, value in members.setQuery(value) }
        .alert(BipeStrings.appSaveError, isPresented: $details.showsSaveError) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .alert(BipeStrings.appGroupMemberError, isPresented: $members.showsError) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .sheet(item: $sheet) { shown in
            switch shown {
            case .report:
                if let client = account.client {
                    MacReportChatSheet(room: details.room, client: client, isDm: false, otherUserID: nil, blocked: account.blocked,
                                       sent: { reported($0) }, cleared: { _, _ in },
                                       close: {
                                           sheet = nil
                                           // Spec report-chat alsoLeave: a left group closes too.
                                           if details.room.membership() != .joined { left(details.room.id()) }
                                       })
                }
            case .leave:
                leaveConfirm
            case .member(let action, let member):
                memberConfirm(action, member)
            }
        }
    }

    // MARK: Header and edit

    @ViewBuilder private var header: some View {
        VStack(spacing: 8) {
            MacAvatar(name: details.name, media: account.media, url: face?.url, userID: nil, members: face?.members ?? [], size: 72)
            if details.phase == .viewing {
                Text(details.name).macFont(.title2).fontWeight(.bold).multilineTextAlignment(.center)
                if let description = details.description {
                    Text(description).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center).textSelection(.enabled)
                }
                Text(BipeStrings.appGroupDetailsPeople(details.memberCount.formatted()))
                    .macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                if details.canEdit { Button(BipeStrings.appEdit) { details.edit() }.buttonStyle(.link) }
            } else {
                TextField(BipeStrings.appGroupName, text: $details.editName).textFieldStyle(.roundedBorder)
                TextField(BipeStrings.appGroupDescriptionPlaceholder, text: $details.editDescription, axis: .vertical)
                    .lineLimit(2...5).textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    if details.phase == .saving { ProgressView() }
                    Button(BipeStrings.appCancel) { details.cancelEdit() }.disabled(details.phase == .saving)
                    Button(BipeStrings.appSave) { Task { await details.save() } }
                        .keyboardShortcut(.defaultAction).disabled(!details.canSave)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    // MARK: Members (spec group-members)

    @ViewBuilder private var membersSections: some View {
        Section {
            TextField(BipeStrings.appPeopleSearch, text: $query).textFieldStyle(.roundedBorder)
            if members.customRoles {
                Text(BipeStrings.appGroupRolesCustom).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
            }
            if members.loading { ProgressView().frame(maxWidth: .infinity) }
        } header: {
            Text(BipeStrings.appGroupMembersTitle)
        }
        if !members.joined.isEmpty {
            Section(BipeStrings.appGroupMembersJoined) { ForEach(members.joined) { memberRow($0) } }
        }
        if !members.invited.isEmpty {
            Section(BipeStrings.appGroupMembersInvited) { ForEach(members.invited) { memberRow($0) } }
        }
        if !members.banned.isEmpty {
            Section(BipeStrings.appGroupMembersBanned) { ForEach(members.banned) { memberRow($0) } }
        }
        Section(BipeStrings.appGroupRolesTitle) {
            ForEach([BipeStrings.appGroupRoleAdminBody, BipeStrings.appGroupRoleModeratorBody, BipeStrings.appGroupRoleMemberBody],
                    id: \.self) { text in
                Text(text).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
            }
        }
    }

    private func memberRow(_ member: GroupMember) -> some View {
        Menu {
            if !member.isYou {
                Button(BipeStrings.appGroupMemberProfile) { openProfile(member.person.userId) }
            }
            ForEach(members.actions(for: member), id: \.self) { action in
                Button(role: Self.isDestructive(action) ? .destructive : nil) {
                    if action == .remove || action == .ban || action == .unban {
                        sheet = .member(action, member)
                    } else {
                        Task { await members.perform(action, on: member) }
                    }
                } label: { Text(Self.title(of: action)) }
            }
        } label: {
            MacGroupMemberRow(member: member, media: account.media)
        }
        .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden)
        .disabled(member.isYou)
    }

    // MARK: Confirms

    private func memberConfirm(_ action: GroupMembersModel.MemberAction, _ member: GroupMember) -> some View {
        let name = member.person.displayName
        return VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(Self.title(of: action)).macFont(.headline)
            Text(action == .ban ? BipeStrings.appGroupMemberBanBody(name)
                 : action == .unban ? BipeStrings.appGroupMemberUnbanBody(name) : BipeStrings.appGroupMemberRemoveBody(name))
                .macFont(.body).fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(BipeStrings.appCancel) { sheet = nil }.keyboardShortcut(.cancelAction)
                Button(role: action == .unban ? nil : .destructive) {
                    sheet = nil
                    Task { await members.perform(action, on: member) }
                } label: { Text(Self.title(of: action)) }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MacDesign.gap * 2).frame(width: 420)
    }

    /// Spec group-leave: the confirm with its warning; a last admin can choose another admin first.
    private var leaveConfirm: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.appGroupLeaveTitle).macFont(.headline)
            Text(leave.warning == .alone ? BipeStrings.appGroupLeaveAlone
                 : leave.warning == .lastAdmin ? BipeStrings.appGroupLeaveLastAdmin : BipeStrings.appGroupLeaveBody)
                .macFont(.body).fixedSize(horizontal: false, vertical: true)
            if leave.phase == .failed { MacNotice(text: BipeStrings.appGroupLeaveError, danger: true) }
            HStack {
                if leave.warning == .lastAdmin {
                    // Action chooseAdmin: back to the member list, where a role can be given.
                    Button(BipeStrings.appGroupLeaveChooseAdmin) { sheet = nil }
                }
                Spacer()
                if leave.phase == .leaving { ProgressView() }
                Button(BipeStrings.appCancel) { sheet = nil }.keyboardShortcut(.cancelAction).disabled(leave.phase == .leaving)
                Button(role: .destructive) {
                    Task {
                        if await leave.leave() {
                            sheet = nil
                            left(details.room.id())
                        }
                    }
                } label: { Text(BipeStrings.appGroupLeaveConfirm) }
                    .keyboardShortcut(.defaultAction).disabled(leave.phase == .leaving)
            }
        }
        .padding(MacDesign.gap * 2).frame(width: 440)
        .interactiveDismissDisabled(leave.phase == .leaving)
    }

    static func title(of action: GroupMembersModel.MemberAction) -> String {
        switch action {
        case .viewProfile: BipeStrings.appGroupMemberProfile
        case .makeAdmin: BipeStrings.appGroupMemberMakeAdmin
        case .removeAdmin: BipeStrings.appGroupMemberRemoveAdmin
        case .makeModerator: BipeStrings.appGroupMemberMakeModerator
        case .removeModerator: BipeStrings.appGroupMemberRemoveModerator
        case .remove: BipeStrings.appGroupMemberRemove
        case .ban: BipeStrings.appGroupMemberBan
        case .unban: BipeStrings.appGroupMemberUnban
        case .cancelInvite: BipeStrings.appGroupMemberCancelInvite
        }
    }

    static func isDestructive(_ action: GroupMembersModel.MemberAction) -> Bool {
        action == .remove || action == .ban || action == .cancelInvite
    }
}

/// One row of spec group-members: photo, name (with the @ when another member shows the same name, bipe#825),
/// the official badge, and the role or "you".
struct MacGroupMemberRow: View {
    let member: GroupMember
    @ObservedObject var media: MacMedia
    @Environment(\.bipeHandles) private var handles

    var body: some View {
        HStack(spacing: 12) {
            MacAvatar(name: member.person.displayName, media: media, url: member.person.avatarUrl, userID: member.person.userId, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(member.nameAmbiguous
                         ? MemberNames.label(name: member.person.displayName, userId: member.person.userId, handles: handles)
                         : member.person.displayName)
                        .lineLimit(1)
                    if BipeOfficialAccounts.isOfficial(member.person.userId) { MacOfficialBadge() }
                    MacSealMark(userID: member.person.userId, size: 14)
                }
                if member.person.userId == BipeRegistries.groupsAccountID {
                    Text(BipeStrings.groupsServiceAbout).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer()
            if member.isYou {
                Text(BipeStrings.appYou).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
            } else if member.role != .member {
                Text(member.isAdmin ? BipeStrings.appGroupAdmin : BipeStrings.appGroupModerator)
                    .macFont(.footnote).foregroundStyle(BipeColor.accentInk)
            }
        }
        .contentShape(Rectangle())
        // A name the SDK has not resolved to an @ yet: resolve it once for the label.
        .task(id: member.nameAmbiguous) {
            if member.nameAmbiguous, let handles, handles.handle(for: member.person.userId) == nil {
                _ = await handles.resolve(member.person.userId)
            }
        }
    }
}

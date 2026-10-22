// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// What person-profile opens outside the people screens. A route the app does not have yet hides its row.
struct PersonProfileRoutes {
    /// Opens chat-timeline for the room.
    var openChat: (String) -> Void
    /// Opens group-create with the person picked.
    var newGroup: ((String) -> Void)?
    /// fromDm: opens chat-notifications for the DM.
    var notifications: ((String) -> Void)?
    /// fromDm: opens dm-delete for the DM.
    var deleteChat: ((String) -> Void)?
}

/// Spec person-profile. From a DM header it is also the DM's info screen (ADR 0141).
struct PersonProfileView: View {
    @StateObject private var model: PersonProfileModel
    @ObservedObject private var people: PeopleService
    private let routes: PersonProfileRoutes

    @State private var showsBlock = false
    @State private var showsReport = false
    @State private var showsSeal = false
    @State private var inviteTargets: [InviteTarget]?
    @State private var isOpeningChat = false

    init(people: PeopleService, userId: String, fromDm: Bool, routes: PersonProfileRoutes) {
        _model = StateObject(wrappedValue: PersonProfileModel(people: people, userId: userId, fromDm: fromDm))
        self.people = people
        self.routes = routes
    }

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(model.fromDm ? BipeStrings.dmDetailsTitle : model.person.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier(BipeScreen.personProfile.rawValue)
            .task { await model.appear() }
            .toast($model.notice)
            .sheet(isPresented: $showsBlock) {
                BlockPersonSheet(people: people, userId: model.userId, name: model.person.displayName)
            }
            .sheet(isPresented: $showsReport) {
                if let roomId = model.dmRoomId {
                    ReportChatView(people: people, roomId: roomId, isDm: true, otherUserId: model.userId) { message in
                        model.notice = message
                    }
                }
            }
            .sheet(isPresented: $showsSeal) {
                CompanySealSheet(userId: model.userId, state: model.seal)
            }
            .sheet(item: Binding(get: { inviteTargets.map(InviteTargetList.init) }, set: { if $0 == nil { inviteTargets = nil } })) { list in
                InviteTargetPicker(targets: list.targets) { target in
                    inviteTargets = nil
                    Task { await model.invite(to: target) }
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 16) {
                Text(BipeStrings.appPersonNotFound)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) {
                    Task { await model.appear() }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            List {
                Section {
                    header
                }
                .listRowBackground(Color.clear)
                actions
                if model.fromDm, let roomId = model.dmRoomId {
                    dmRows(roomId: roomId)
                }
                safety
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }

    private var header: some View {
        VStack(spacing: 10) {
            AvatarView(name: model.person.displayName, mxcUrl: model.person.avatarUrl, loader: people.media,
                       size: 96, isSquare: model.isCompany)
            HStack(spacing: 6) {
                Text(model.person.displayName)
                    .font(.title2.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                if model.seal.isVerified {
                    Button {
                        showsSeal = true
                    } label: {
                        PhosphorIcon.sealCheck.image
                            .resizable()
                            .frame(width: 22, height: 22)
                            .foregroundStyle(BipeColor.trustVerifiedInk)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(BipeStrings.badgeCompany)
                }
            }
            if let handle = model.person.handle {
                Text("@\(handle)")
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if model.reservedNameWarning {
                MarkLine(icon: .warning, text: BipeStrings.warningReservedName, tint: BipeColor.trustCautionInk)
            }
            ForEach(model.marks) { mark in
                markLine(mark)
            }
            if people.isBlocked(model.userId) {
                MarkLine(icon: .prohibit, text: BipeStrings.appPersonBlocked, tint: BipeColor.trustDangerInk)
            }
            if let bio = model.bio {
                VStack(spacing: 4) {
                    Text(bio)
                        .font(.body)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Text(BipeStrings.publicProfileNotVerified)
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private func markLine(_ mark: TrustMark) -> some View {
        switch mark.kind {
        case .company:
            Button {
                showsSeal = true
            } label: {
                MarkLine(icon: .sealCheck, text: mark.text, tint: BipeColor.trustVerifiedInk)
            }
            .buttonStyle(.plain)
        case .verifiedName:
            MarkLine(icon: .sealCheck, text: mark.text, tint: BipeColor.trustVerifiedInk)
        case .confirmedInPerson:
            MarkLine(icon: .handshake, text: mark.text, tint: BipeColor.trustVerifiedInk)
        case .companyRevoked, .companySuspended, .nameSuspended:
            MarkLine(icon: .sealWarning, text: mark.text, tint: BipeColor.trustCautionInk)
        }
    }

    private var actions: some View {
        Section {
            Button {
                guard !isOpeningChat else { return }
                isOpeningChat = true
                Task {
                    if let roomId = await model.message() {
                        routes.openChat(roomId)
                    }
                    isOpeningChat = false
                }
            } label: {
                IconLabel(icon: .chatText, text: BipeStrings.appPersonMessage)
            }
            .disabled(isOpeningChat)
            if let link = model.shareLink {
                ShareLink(item: link) {
                    IconLabel(icon: .shareNetwork, text: BipeStrings.profileShare)
                }
            }
            Button {
                Task { inviteTargets = await model.inviteTargets() }
            } label: {
                IconLabel(icon: .userPlus, text: BipeStrings.dmInviteToGroup)
            }
            if let newGroup = routes.newGroup {
                Button {
                    newGroup(model.userId)
                } label: {
                    IconLabel(icon: .usersThree, text: BipeStrings.appPersonNewGroup)
                }
            }
        }
    }

    @ViewBuilder
    private func dmRows(roomId: String) -> some View {
        if routes.notifications != nil || routes.deleteChat != nil {
            Section {
                if let notifications = routes.notifications {
                    Button {
                        notifications(roomId)
                    } label: {
                        IconLabel(icon: .bell, text: BipeStrings.settingsNotifications)
                    }
                }
                if let deleteChat = routes.deleteChat {
                    Button {
                        deleteChat(roomId)
                    } label: {
                        IconLabel(icon: .trash, text: BipeStrings.dmDeleteAction, tint: BipeColor.trustDangerInk)
                    }
                }
            }
        }
    }

    private var safety: some View {
        Section {
            Button {
                showsBlock = true
            } label: {
                if people.isBlocked(model.userId) {
                    IconLabel(icon: .prohibit, text: BipeStrings.actionUnblockPerson)
                } else {
                    IconLabel(icon: .prohibit, text: BipeStrings.actionBlockPerson, tint: BipeColor.trustDangerInk)
                }
            }
            // Without a DM there is nothing to report: the row is hidden.
            if model.dmRoomId != nil {
                Button {
                    showsReport = true
                } label: {
                    IconLabel(icon: .flag, text: BipeStrings.dmReport, tint: BipeColor.trustDangerInk)
                }
            }
        }
    }
}

/// One trust mark or warning under the name.
private struct MarkLine: View {
    let icon: PhosphorIcon
    let text: String
    let tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            icon.image
                .resizable()
                .frame(width: 16, height: 16)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
            Text(text)
                .font(.footnote)
                .foregroundStyle(tint)
                .multilineTextAlignment(.leading)
        }
    }
}

private struct InviteTargetList: Identifiable {
    let targets: [InviteTarget]
    var id: String { targets.map(\.roomId).joined(separator: ",") + "#\(targets.count)" }
}

/// Action addToGroup: the user's groups where they may invite.
private struct InviteTargetPicker: View {
    let targets: [InviteTarget]
    let pick: (InviteTarget) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if targets.isEmpty {
                    Text(BipeStrings.dmInviteToGroupEmpty)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(targets) { target in
                        Button {
                            pick(target)
                        } label: {
                            IconLabel(icon: .usersThree, text: target.name)
                        }
                    }
                }
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.dmInviteToGroup)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

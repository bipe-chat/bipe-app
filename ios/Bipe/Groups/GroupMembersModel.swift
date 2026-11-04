// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type GroupMember of spec group-members.
struct GroupMember: Identifiable, Equatable {
    enum Membership: Equatable {
        case joined
        case invited
        case banned
    }

    var person: Person
    var membership: Membership
    var role: GroupRole
    var isYou: Bool
    /// A creator (room version 12) has no role actions.
    var isCreator: Bool
    var level: Int64
    /// Spec group-members nameAmbiguous: another member shows the same name (bipe-chat/bipe#825).
    var nameAmbiguous = false

    var id: String { person.userId }
    var isAdmin: Bool { role == .admin }
}

/// Spec group-members (bipe-shared product/screens/group-members.json).
@MainActor
final class GroupMembersModel: ObservableObject {
    /// The rows of the action sheet (group-members notes).
    enum MemberAction: Hashable {
        case viewProfile
        case makeAdmin
        case removeAdmin
        case makeModerator
        case removeModerator
        case remove
        case ban
        case unban
        case cancelInvite
    }

    @Published private(set) var query = ""
    @Published private(set) var joined: [GroupMember] = []
    @Published private(set) var invited: [GroupMember] = []
    /// State banned (bipe#675): shown only when canBan.
    @Published private(set) var banned: [GroupMember] = []
    @Published private(set) var canInvite = false
    @Published private(set) var canKick = false
    @Published private(set) var canBan = false
    @Published private(set) var canChangeRoles = false
    /// Another Matrix app set permissions off the three roles (bipe-chat/bipe#733).
    @Published private(set) var customRoles = false
    @Published private(set) var loading = true
    @Published var showsError = false

    let roomId: String
    let session: AccountSession
    private var all: [GroupMember] = []
    private var ownLevel: Int64 = 0
    private var customLevels = false

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    private var room: Room? { try? session.client.getRoom(roomId: roomId) }

    /// Action appear: the cached list at once, then the list from the server.
    func appear() async {
        guard let room else { return }
        if let levels = try? await room.getPowerLevels() {
            canInvite = levels.canOwnUserInvite()
            canKick = levels.canOwnUserKick()
            canBan = levels.canOwnUserBan()
            canChangeRoles = levels.canOwnUserSendState(stateEvent: .roomPowerLevels)
            let values = levels.values()
            customLevels = [values.kick, values.ban, values.redact].contains { $0 != BipeGroupPowerLevels.moderator }
        }
        if let cached = await GroupPeople.members(of: room, cachedOnly: true), !cached.isEmpty {
            apply(cached)
            loading = false
        }
        if let members = await GroupPeople.members(of: room) {
            apply(members)
        }
        loading = false
    }

    /// Action setQuery.
    func setQuery(_ query: String) {
        self.query = query
        rebuild()
    }

    /// The rows of the action sheet of a person, each only when allowed.
    func actions(for member: GroupMember) -> [MemberAction] {
        guard !member.isYou else { return [] }
        // viewProfile joins the list with the profile screen (bipe-chat/bipe#567).
        var actions: [MemberAction] = []
        switch member.membership {
        case .joined:
            // Matrix refuses to kick, ban or change a person at or above the user's level.
            let below = !member.isCreator && member.level < ownLevel
            if canChangeRoles && below {
                switch member.role {
                case .admin: actions += [.makeModerator, .removeAdmin]
                case .moderator: actions += [.makeAdmin, .removeModerator]
                case .member: actions += [.makeAdmin, .makeModerator]
                }
            }
            if canKick && below { actions.append(.remove) }
            if canBan && below { actions.append(.ban) }
        case .invited:
            if canKick { actions.append(.cancelInvite) }
        case .banned:
            if canBan { actions.append(.unban) }
        }
        return actions
    }

    /// Actions makeAdmin, removeAdmin, makeModerator, removeModerator, remove, ban and cancelInvite. Remove and ban are confirmed by the view first.
    func perform(_ action: MemberAction, on member: GroupMember) async {
        guard let room else { return }
        do {
            switch action {
            case .viewProfile:
                return
            case .makeAdmin:
                try await setLevel(of: member, to: BipeGroupPowerLevels.admin, in: room)
            case .makeModerator:
                try await setLevel(of: member, to: BipeGroupPowerLevels.moderator, in: room)
            case .removeAdmin, .removeModerator:
                try await setLevel(of: member, to: BipeGroupPowerLevels.member, in: room)
            case .remove, .cancelInvite:
                try await room.kickUser(userId: member.person.userId, reason: nil)
            case .ban:
                try await room.banUser(userId: member.person.userId, reason: nil)
            case .unban:
                try await room.unbanUser(userId: member.person.userId, reason: nil)
            }
            if let members = await GroupPeople.members(of: room) {
                apply(members)
            }
        } catch {
            showsError = true
        }
    }

    private func setLevel(of member: GroupMember, to level: Int64, in room: Room) async throws {
        try await room.updatePowerLevelsForUsers(updates: [UserPowerLevelUpdate(userId: member.person.userId, powerLevel: level)])
    }

    private func apply(_ members: [RoomMember]) {
        if let own = members.first(where: { $0.userId == session.userId }) {
            ownLevel = GroupPeople.level(own.powerLevel)
        }
        let flagged = Set(members.filter(\.isNameAmbiguous).map(\.userId))
        all = members.compactMap { member in
            let membership: GroupMember.Membership
            switch member.membership {
            case .join: membership = .joined
            case .invite: membership = .invited
            case .ban: membership = .banned
            default: return nil
            }
            return GroupMember(person: Person(member: member),
                               membership: membership,
                               role: GroupPeople.role(member),
                               isYou: member.userId == session.userId,
                               isCreator: member.suggestedRoleForPowerLevel == .creator,
                               level: GroupPeople.level(member.powerLevel))
        }
        let lookalikes = MemberNames.ambiguous(Dictionary(all.map { ($0.person.userId, $0.person.displayName) },
                                                          uniquingKeysWith: { first, _ in first }))
        for index in all.indices where flagged.contains(all[index].person.userId) || lookalikes.contains(all[index].person.userId) {
            all[index].nameAmbiguous = true
        }
        let roleLevels: Set<Int64> = [BipeGroupPowerLevels.admin, BipeGroupPowerLevels.moderator, BipeGroupPowerLevels.member]
        customRoles = customLevels || all.contains { !$0.isCreator && !roleLevels.contains($0.level) }
        rebuild()
    }

    private func rebuild() {
        let visible = all.filter { $0.person.matches(query) }
        let byName: (GroupMember, GroupMember) -> Bool = {
            $0.person.displayName.localizedStandardCompare($1.person.displayName) == .orderedAscending
        }
        joined = visible.filter { $0.membership == .joined }.sorted { lhs, rhs in
            lhs.role != rhs.role ? lhs.role.rank < rhs.role.rank : byName(lhs, rhs)
        }
        invited = visible.filter { $0.membership == .invited }.sorted(by: byName)
        banned = canBan ? visible.filter { $0.membership == .banned }.sorted(by: byName) : []
    }
}

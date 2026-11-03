// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Group adapters share the native people model, so every screen uses the same display-name rules.
extension Person {
    init(member: RoomMember) {
        self.init(userId: member.userId, displayName: member.displayName, avatarUrl: member.avatarUrl)
    }

    /// Filter of group-invite-members and group-members: by name or @, ignoring case and accents.
    func matches(_ query: String) -> Bool {
        let query = query.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "@"))
        guard !query.isEmpty else { return true }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        return displayName.range(of: query, options: options) != nil
            || (handle?.range(of: query, options: options) != nil)
            || userId.range(of: query, options: options) != nil
    }
}

final class RoomInfoStream: RoomInfoListener {
    let updates: AsyncStream<RoomInfo>
    private let continuation: AsyncStream<RoomInfo>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func call(roomInfo: RoomInfo) {
        continuation.yield(roomInfo)
    }
}

enum GroupPeople {
    static let chunkSize: UInt32 = 200

    /// Every member of a room, read through the members iterator until it is empty.
    static func members(of room: Room, cachedOnly: Bool = false) async -> [RoomMember]? {
        let iterator: RoomMembersIterator
        do {
            iterator = cachedOnly ? try await room.membersNoSync() : try await room.members()
        } catch {
            return nil
        }
        var members: [RoomMember] = []
        while let chunk = iterator.nextChunk(chunkSize: chunkSize), !chunk.isEmpty {
            members.append(contentsOf: chunk)
        }
        return members
    }

    /// The user's contacts (spec group-invite-members notes): the other person of each DM and the members of the
    /// user's groups. Invite pairs and address-book matches come with the Bipe API client.
    static func contacts(client: Client, ownUserId: String) async -> [Person] {
        var people: [String: Person] = [:]
        for room in client.rooms() {
            guard let info = try? await room.roomInfo(), info.membership == .joined, !info.isSpace else { continue }
            if info.isDm {
                for hero in info.heroes where hero.userId != ownUserId {
                    people[hero.userId] = people[hero.userId] ?? Person(userId: hero.userId, displayName: hero.displayName, avatarUrl: hero.avatarUrl)
                }
            } else if let members = await members(of: room, cachedOnly: true) {
                for member in members where member.membership == .join && member.userId != ownUserId && !member.isServiceMember {
                    if people[member.userId] == nil || people[member.userId]?.displayName == member.userId {
                        people[member.userId] = Person(member: member)
                    }
                }
            }
        }
        return people.values.sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }
    }

    /// The own user's current power level in the room, compared with a member's (group-members notes).
    static func level(_ powerLevel: PowerLevel) -> Int64 {
        switch powerLevel {
        case .infinite: Int64.max
        case .value(let value): value
        }
    }

    static func isAdmin(_ member: RoomMember) -> Bool {
        role(member) == .admin
    }

    /// Spec group-members GroupMember.role: a level between two roles shows the lower one.
    static func role(_ member: RoomMember) -> GroupRole {
        if member.suggestedRoleForPowerLevel == .creator { return .admin }
        let level = level(member.powerLevel)
        if level >= BipeGroupPowerLevels.admin { return .admin }
        if level >= BipeGroupPowerLevels.moderator { return .moderator }
        return .member
    }

    /// The m.room.power_levels of a new group (spec group-create notes, bipe-chat/bipe#733). The users map stays
    /// empty, so the server keeps the creator at the top.
    static var newGroupPowerLevels: PowerLevels {
        PowerLevels(usersDefault: Int32(BipeGroupPowerLevels.usersDefault),
                    eventsDefault: Int32(BipeGroupPowerLevels.eventsDefault),
                    stateDefault: Int32(BipeGroupPowerLevels.stateDefault),
                    ban: Int32(BipeGroupPowerLevels.ban),
                    kick: Int32(BipeGroupPowerLevels.kick),
                    redact: Int32(BipeGroupPowerLevels.redact),
                    invite: Int32(BipeGroupPowerLevels.invite),
                    notifications: NotificationPowerLevels(room: Int32(BipeGroupPowerLevels.notificationsRoom)),
                    users: [:],
                    events: BipeGroupPowerLevels.events.mapValues { Int32($0) })
    }
}

/// The three roles of a group (spec group-members, bipe-chat/bipe#733).
enum GroupRole: Equatable {
    case admin
    case moderator
    case member

    /// Sort order in the members list: admins first.
    var rank: Int {
        switch self {
        case .admin: 0
        case .moderator: 1
        case .member: 2
        }
    }
}

/// How an invite went (group-invite-members PersonPick status refused and failed).
enum InviteOutcome {
    case sent
    case refused
    case failed

    /// A refused invite is the server's who-can-message module (ADR 0038): the spam checker answers M_FORBIDDEN.
    init(error: Error?) {
        guard let error else {
            self = .sent
            return
        }
        if let clientError = error as? ClientError, case .MatrixApi(_, let code, _, _) = clientError, code == "M_FORBIDDEN" {
            self = .refused
        } else {
            self = .failed
        }
    }
}

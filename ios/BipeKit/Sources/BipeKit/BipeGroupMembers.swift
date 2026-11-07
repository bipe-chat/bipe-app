// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Spec timeline-rows and group-members nameAmbiguous (bipe-chat/bipe#825): members whose names look the same.
public enum MemberNames {
    /// The user IDs whose name, after `NameNormalizer` (security rule 8), another member also has.
    /// A look-alike "Mãe" with a Cyrillic "а" counts as the same name.
    public static func ambiguous(_ names: [String: String]) -> Set<String> {
        var byName: [String: [String]] = [:]
        for (userId, name) in names {
            let key = NameNormalizer.normalize(name)
            guard !key.isEmpty else { continue }
            byName[key, default: []].append(userId)
        }
        return Set(byName.values.filter { $0.count > 1 }.joined())
    }

    /// The name with what tells the member apart: "Maria @maria1" for a Bipe user once the @ is known, the name alone
    /// while the @ loads and for a user of another server. Never the Matrix ID (People names).
    @MainActor public static func label(name: String, userId: String, handles: BipeHandleResolver?) -> String {
        guard let handle = handles?.handle(for: userId), name != "@" + handle else { return name }
        return name + " @" + handle
    }
}

/// The three roles of a group (spec group-members, bipe-chat/bipe#733).
public enum GroupRole: Equatable, Sendable {
    case admin
    case moderator
    case member

    /// A level between two roles is the lower one.
    public init(level: Int64) {
        if level >= BipeGroupPowerLevels.admin {
            self = .admin
        } else if level >= BipeGroupPowerLevels.moderator {
            self = .moderator
        } else {
            self = .member
        }
    }

    /// Sort order in the members list: admins first.
    public var rank: Int {
        switch self {
        case .admin: 0
        case .moderator: 1
        case .member: 2
        }
    }

    /// The admins and moderators of a group by user ID, for the sender line (spec chat-timeline senderRole).
    /// Creators (room version 12) are not in the power levels and count as admins.
    public static func staff(levels: [String: Int64], creators: [String]) -> [String: GroupRole] {
        var roles = levels.compactMapValues { level -> GroupRole? in
            let role = GroupRole(level: level)
            return role == .member ? nil : role
        }
        for creator in creators {
            roles[creator] = .admin
        }
        return roles
    }

    /// The text after the name on the sender line, in the accent colour. A member has none.
    public var senderBadge: String? {
        switch self {
        case .admin: BipeStrings.appRoleBadgeAdmin
        case .moderator: BipeStrings.appRoleBadgeModerator
        case .member: nil
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type StateLine of spec timeline-rows (bipe-chat/bipe#661): a group membership or group profile change.
struct StateLine: Equatable {
    enum Change: Equatable {
        case joined
        case left
        case invited
        case removed
        case banned
        case renamed
        case nameRemoved
        case photoChanged
        case photoRemoved
        case descriptionChanged
        case descriptionRemoved
    }

    var change: Change
    /// The sender's user ID.
    var actorId: String
    /// Membership changes only: the people of the folded line, oldest first, each once.
    var targetIds: [String]
    /// renamed only.
    var newName: String?

    var isMembership: Bool {
        switch change {
        case .joined, .left, .invited, .removed, .banned: true
        default: false
        }
    }
}

/// Spec timeline-rows stateLine: which group events become lines, how they fold and what they say.
enum StateLines {
    /// The line of an event, or nil when the event is not a stateLine. The caller skips DMs (ADR 0141).
    static func line(_ event: EventTimelineItem) -> StateLine? {
        switch event.content {
        case .roomMembership(let userId, _, let change, _):
            let mapped: StateLine.Change
            switch change {
            case .joined?, .invitationAccepted?: mapped = .joined
            case .left?: mapped = .left
            case .invited?, .knockAccepted?: mapped = .invited
            case .kicked?: mapped = .removed
            case .banned?, .kickedAndBanned?: mapped = .banned
            default: return nil
            }
            return StateLine(change: mapped, actorId: event.sender, targetIds: [userId])
        case .state(_, .roomName(let name)):
            guard let name = name?.nilIfBlank else { return StateLine(change: .nameRemoved, actorId: event.sender, targetIds: []) }
            return StateLine(change: .renamed, actorId: event.sender, targetIds: [], newName: name)
        case .state(_, .roomAvatar(let url)):
            return StateLine(change: url?.nilIfBlank == nil ? .photoRemoved : .photoChanged, actorId: event.sender, targetIds: [])
        case .state(_, .roomTopic(let topic)):
            return StateLine(change: topic?.nilIfBlank == nil ? .descriptionRemoved : .descriptionChanged,
                             actorId: event.sender, targetIds: [])
        default:
            return nil
        }
    }

    /// The previous line with this one folded in, or nil when they do not fold: the same membership change, and for
    /// invited, removed and banned the same sender.
    static func folded(_ line: StateLine, into previous: StateLine) -> StateLine? {
        guard line.isMembership, line.change == previous.change else { return nil }
        if line.change != .joined, line.change != .left, line.actorId != previous.actorId { return nil }
        var result = previous
        for userId in line.targetIds where !result.targetIds.contains(userId) {
            result.targetIds.append(userId)
        }
        return result
    }

    static func text(_ line: StateLine, name: (String) -> String) -> String {
        let names = line.targetIds.map(name)
        let people: String
        switch names.count {
        case 0: people = ""
        case 1: people = names[0]
        case 2: people = BipeStrings.appStateNamesTwo(names[0], names[1])
        default: people = BipeStrings.appStateNamesMore(names[0], names[1], String(names.count - 2))
        }
        let actor = name(line.actorId)
        switch line.change {
        case .joined: return names.count > 1 ? BipeStrings.appStateJoinedMany(people) : BipeStrings.appStateJoinedOne(people)
        case .left: return names.count > 1 ? BipeStrings.appStateLeftMany(people) : BipeStrings.appStateLeftOne(people)
        case .invited: return BipeStrings.appStateInvited(actor, people)
        case .removed: return BipeStrings.appStateRemoved(actor, people)
        case .banned: return BipeStrings.appStateBanned(actor, people)
        case .renamed: return BipeStrings.appStateRenamed(actor, line.newName ?? "")
        case .nameRemoved: return BipeStrings.appStateNameRemoved(actor)
        case .photoChanged: return BipeStrings.appStatePhotoChanged(actor)
        case .photoRemoved: return BipeStrings.appStatePhotoRemoved(actor)
        case .descriptionChanged: return BipeStrings.appStateDescriptionChanged(actor)
        case .descriptionRemoved: return BipeStrings.appStateDescriptionRemoved(actor)
        }
    }
}

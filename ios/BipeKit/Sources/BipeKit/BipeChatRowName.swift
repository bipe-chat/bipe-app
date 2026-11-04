// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

public enum BipeChatRowName {
    /// Missing heroes must not discard an explicit/computed SDK room name.
    /// A DM passes its m.room.name (RoomInfo.rawName) as `dmRoomName`: a DM never shows it (ADR 0141).
    /// After the names: the peer's @handle, else `unnamed`. Never the Matrix ID or its localpart (People names).
    /// A group without m.room.name passes its heroes as `groupHeroes`: the SDK builds that name from display names,
    /// which can be localparts, so when a hero has no real name the row names the heroes by the people-names rule.
    public static func resolve(roomID: String, sdkName: String?, peerName: String?, peerID: String?, unnamed: String,
                               dmRoomName: String? = nil, peerHandle: String? = nil,
                               groupHeroes: [(userID: String, displayName: String?)]? = nil) -> String {
        if let groupHeroes, groupHeroes.contains(where: { realName($0.displayName, userID: $0.userID) == nil }) {
            return groupHeroes.map { person(userID: $0.userID, displayName: $0.displayName, handle: nil) }.joined(separator: ", ")
        }
        let roomName = dmRoomName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let shownSDKName = !roomName.isEmpty && sdkName?.trimmingCharacters(in: .whitespacesAndNewlines) == roomName ? nil : sdkName
        for value in [shownSDKName, peerName] {
            if let name = value?.trimmingCharacters(in: .whitespacesAndNewlines),
               !name.isEmpty, name != roomID, !isMatrixID(name) {
                if let peerID, name == localpart(of: peerID) { continue }
                return name
            }
        }
        if let handle = peerHandle ?? peerID.flatMap(BipeHandleCache.handle(for:)), !handle.isEmpty { return "@" + handle }
        return unnamed
    }

    /// Spec People names (bipe-shared product/screens README, MZ 2 November 2026): the saved contact name, then the
    /// display name when it is a real name, then the @handle, then `placeholder`. Never the Matrix ID or its localpart.
    public static func person(userID: String, contactName: String? = nil, displayName: String?, handle: String?,
                              placeholder: String = BipeStrings.personNoName) -> String {
        if let contactName = contactName?.trimmingCharacters(in: .whitespacesAndNewlines), !contactName.isEmpty {
            return contactName
        }
        if let name = realName(displayName, userID: userID) { return name }
        if let handle = handle ?? BipeHandleCache.handle(for: userID), !handle.isEmpty { return "@" + handle }
        return placeholder
    }

    /// The display name when it is a name: not empty, not a Matrix ID, not the localpart of `userID`. Synapse starts a
    /// new account's display name as its localpart, and only Profile edit sets a real one.
    public static func realName(_ displayName: String?, userID: String) -> String? {
        guard let name = displayName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty,
              name != userID, !isMatrixID(name), name != localpart(of: userID) else { return nil }
        return name
    }

    /// The localpart of a Matrix ID, for comparison only: never a name to show.
    static func localpart(of userID: String) -> String {
        let trimmed = userID.hasPrefix("@") ? String(userID.dropFirst()) : userID
        return trimmed.split(separator: ":", maxSplits: 1).first.map(String.init) ?? trimmed
    }

    /// "@localpart:server", "!room:server" or "#alias:server": text that is an ID, not a name.
    static func isMatrixID(_ text: String) -> Bool {
        guard let first = text.first, first == "@" || first == "!" || first == "#" else { return false }
        return text.contains(":") && !text.contains(" ")
    }
}

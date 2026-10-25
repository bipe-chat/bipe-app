// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

public enum BipeChatRowName {
    /// Missing heroes must not discard an explicit/computed SDK room name.
    /// A DM passes its m.room.name (RoomInfo.rawName) as `dmRoomName`: a DM never shows it (ADR 0141).
    public static func resolve(roomID: String, sdkName: String?, peerName: String?, peerID: String?, unnamed: String,
                               dmRoomName: String? = nil) -> String {
        let roomName = dmRoomName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let shownSDKName = !roomName.isEmpty && sdkName?.trimmingCharacters(in: .whitespacesAndNewlines) == roomName ? nil : sdkName
        for value in [shownSDKName, peerName] {
            if let name = value?.trimmingCharacters(in: .whitespacesAndNewlines),
               !name.isEmpty, name != roomID, name != peerID { return name }
        }
        if let peerID {
            let local = peerID.hasPrefix("@") ? String(peerID.dropFirst()) : peerID
            if let name = local.split(separator: ":", maxSplits: 1).first, !name.isEmpty { return String(name) }
        }
        return unnamed
    }
}

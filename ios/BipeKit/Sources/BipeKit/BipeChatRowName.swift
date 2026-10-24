// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

public enum BipeChatRowName {
    /// Missing heroes must not discard an explicit/computed SDK room name.
    public static func resolve(roomID: String, sdkName: String?, peerName: String?, peerID: String?, unnamed: String) -> String {
        for value in [sdkName, peerName] {
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

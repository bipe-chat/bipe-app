// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Notification kind chooses a destination; an invite never enters an unjoined timeline.
public enum BipeNotificationDestination: Equatable, Sendable {
    case chat, requests, groups, inbox

    public init(kind: String?) {
        switch kind {
        case "message": self = .chat
        case "chatRequest": self = .requests
        case "groupInvite": self = .groups
        default: self = .inbox
        }
    }
}

public struct BipeNotificationOpen: Equatable, Sendable {
    public let target: BipeNotificationTarget
    public let destination: BipeNotificationDestination
    public var accountUserId: String { target.accountUserId }
    public var roomId: String { target.roomId }

    public init(target: BipeNotificationTarget, kind: String?) {
        self.target = target
        destination = BipeNotificationDestination(kind: kind)
    }
}

/// Plain previews only. Callers pass a caption, never a media URL or the SDK's computed filename.
public enum BipeNotificationPreview {
    public static func text(_ value: String?) -> String? {
        guard let clean = value?.trimmingCharacters(in: .whitespacesAndNewlines), !clean.isEmpty else { return nil }
        return clean
    }

    public static func attachment(label: String, caption: String?) -> String {
        guard let caption = text(caption) else { return label }
        return "\(label): \(caption)"
    }
}

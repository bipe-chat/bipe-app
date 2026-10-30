// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

extension MessageType {
    /// Only the Matrix HTML format is rendered; unknown formats keep the plain body.
    var bipeHTML: String? {
        let value: FormattedBody?
        switch self {
        case .text(let content): value = content.formatted
        case .notice(let content): value = content.formatted
        case .emote(let content): value = content.formatted
        default: value = nil
        }
        return value?.format == .html ? value?.body : nil
    }
}

extension TimelineItemContent {
    /// The plain text of a message, for the chat list preview. nil for anything the slice does not show.
    var previewText: String? {
        // Spec incoming-call: a call shows as the call kind; ChatListModel shows a missed one as missed.
        if case .rtcNotification(let intent, _, let activeMembers, _, _) = self {
            if !activeMembers.isEmpty { return BipeStrings.callOngoing }
            return intent == "video" ? BipeStrings.callVideo : BipeStrings.callVoice
        }
        guard case .msgLike(let content) = self else { return nil }
        switch content.kind {
        case .message(let message):
            // Media previews use the kind labels (spec timeline-media notes, voice-message notes).
            switch MediaContent(message.msgType) {
            case .media(let media): return media.kindLabel
            case .voice(let voice): return voice.isVoice ? BipeStrings.appMediaVoice : voice.filename
            case nil:
                // Spec location-view notes: the chat list says "Location".
                if case .location = message.msgType { return BipeStrings.appLocationTitle }
                return message.body
            }
        case .liveLocation:
            return BipeStrings.appLocationLiveTitle
        case .sticker:
            return BipeStrings.appMediaSticker
        case .unableToDecrypt:
            return BipeStrings.appTimelineUndecryptable
        default:
            return nil
        }
    }
}

extension ProfileDetails {
    func displayName(or userId: String) -> String {
        if case .ready(let displayName, _, _, _, _) = self, let displayName, !displayName.isEmpty {
            return displayName
        }
        return userId
    }

    /// The SDK's flag: another member of the room has the same display name.
    var isNameAmbiguous: Bool {
        if case .ready(_, let ambiguous, _, _, _) = self { return ambiguous }
        return false
    }
}

/// Spec timeline-rows and group-members nameAmbiguous (bipe-chat/bipe#825): members whose names look the same.
enum MemberNames {
    /// The user IDs whose name, after `NameNormalizer` (security rule 8), another member also has.
    /// A look-alike "Mãe" with a Cyrillic "а" counts as the same name.
    static func ambiguous(_ names: [String: String]) -> Set<String> {
        var byName: [String: [String]] = [:]
        for (userId, name) in names {
            let key = NameNormalizer.normalize(name)
            guard !key.isEmpty else { continue }
            byName[key, default: []].append(userId)
        }
        return Set(byName.values.filter { $0.count > 1 }.joined())
    }

    /// The name with what tells the member apart: "Maria @maria1" for a Bipe user once the @ is known,
    /// the Matrix ID for a user of another server, the name alone while the @ loads.
    @MainActor static func label(name: String, userId: String, handles: BipeHandleResolver?) -> String {
        if BipeServers.isBipeUserID(userId) {
            guard let handle = handles?.handle(for: userId) else { return name }
            return name + " @" + handle
        }
        return name + " " + userId
    }
}

extension Date {
    /// SDK timestamps are milliseconds since 1970.
    init(milliseconds: UInt64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}

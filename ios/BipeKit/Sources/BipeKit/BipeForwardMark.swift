// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Forward marks (bipe-chat/bipe#83; registries/voice-media.json, test-vectors/forward-marks.json). A forward adds the
/// top-level object `forwardKey` with an integer count to the copied content. A missing or malformed mark counts as 0.
/// The sender's app sets the mark, so a modified app can drop or fake it.
public enum BipeForwardMark {
    /// The largest count the app keeps. A larger count reads as this one.
    public static let maxCount = 2_147_483_647

    /// The source line of a voice message or audio file (spec voice-message).
    public enum Source: String, Sendable {
        case forwarded
        case forwardedManyTimes
        case recorded
        case file
    }

    /// The forward count of message content: the count of the mark when it is an integer of 1 or more, else 0.
    public static func count(content: [String: Any]) -> Int {
        guard let mark = content[BipeRegistries.forwardKey] as? [String: Any],
              let number = mark["count"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              !CFNumberIsFloatType(number as CFNumber) else { return 0 }
        let value = number.int64Value
        return value >= 1 ? Int(min(value, Int64(maxCount))) : 0
    }

    /// The forward count from event JSON (the whole event or its content). 0 when the JSON cannot be read.
    public static func count(eventJson: String?) -> Int {
        guard let content = contentObject(eventJson) else { return 0 }
        return count(content: content)
    }

    /// The content a forward sends: newContent (m.new_content of the latest edit) or else the original content,
    /// without m.relates_to, m.new_content and m.mentions, with the mark set to the original count plus 1.
    public static func forwardedContent(original: [String: Any], newContent: [String: Any]?) -> [String: Any] {
        var content = newContent ?? original
        for key in ["m.relates_to", "m.new_content", "m.mentions"] { content.removeValue(forKey: key) }
        content[BipeRegistries.forwardKey] = ["count": min(count(content: original), maxCount - 1) + 1]
        return content
    }

    /// The content JSON for Room.sendRaw, from EventTimelineItemDebugInfo.originalJson and latestEditJson. nil when
    /// the original JSON is missing or has no msgtype.
    public static func forwardedContentJson(originalJson: String?, latestEditJson: String?) -> String? {
        guard let original = contentObject(originalJson), original["msgtype"] is String else { return nil }
        let newContent = contentObject(latestEditJson)?["m.new_content"] as? [String: Any]
        let forwarded = forwardedContent(original: original, newContent: newContent.flatMap { $0["msgtype"] is String ? $0 : nil })
        guard let data = try? JSONSerialization.data(withJSONObject: forwarded) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The source line for a voice message (voice marker) or audio file with this forward count.
    public static func source(isVoice: Bool, count: Int) -> Source {
        if count >= BipeRegistries.forwardedManyTimes { return .forwardedManyTimes }
        if count >= 1 { return .forwarded }
        return isVoice ? .recorded : .file
    }

    /// The content object of event JSON: its "content" when it has one, else the object itself.
    private static func contentObject(_ json: String?) -> [String: Any]? {
        guard let data = json?.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        return (object["content"] as? [String: Any]) ?? object
    }
}

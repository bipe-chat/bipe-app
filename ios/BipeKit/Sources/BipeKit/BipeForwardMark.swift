// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Forward marks (bipe-chat/bipe#83; registries/voice-media.json, test-vectors/forward-marks.json). A forward adds the
/// top-level object `forwardKey` with an integer count to the copied content. A missing or malformed mark counts as 0.
/// The sender's app sets the mark, so a modified app can drop or fake it.
public enum BipeForwardMark {
    /// The source line of a voice message or audio file (spec voice-message).
    public enum Source: String, Sendable {
        case forwarded
        case forwardedManyTimes
        case recorded
        case file
    }

    /// The forward count of message content (bipe-core `forwardCount`): the count of the mark when it is an integer of 1
    /// or more, else 0.
    public static func count(content: [String: Any]) -> Int {
        CoreJSON.text(content).map { Int(forwardCount(contentJson: $0)) } ?? 0
    }

    /// The forward count from event JSON (the whole event or its content). 0 when the JSON cannot be read.
    public static func count(eventJson: String?) -> Int {
        guard let content = contentObject(eventJson) else { return 0 }
        return count(content: content)
    }

    /// The content a forward sends (bipe-core `forwardedContent`): newContent (m.new_content of the latest edit) or else
    /// the original content, without m.relates_to, m.new_content, m.mentions and the public feed mark, with the mark set
    /// to the original count plus 1.
    public static func forwardedContent(original: [String: Any], newContent: [String: Any]?) -> [String: Any] {
        guard let originalJson = CoreJSON.text(original),
              let json = BipeCore.forwardedContent(originalJson: originalJson, newContentJson: newContent.flatMap(CoreJSON.text)),
              let content = CoreJSON.object(json) else { return original }
        return content
    }

    /// The content JSON for Room.sendRaw, from EventTimelineItemDebugInfo.originalJson and latestEditJson. nil when
    /// the original JSON is missing or has no msgtype.
    public static func forwardedContentJson(originalJson: String?, latestEditJson: String?) -> String? {
        guard let original = contentObject(originalJson), original["msgtype"] is String, let originalText = CoreJSON.text(original) else { return nil }
        let newContent = (contentObject(latestEditJson)?["m.new_content"] as? [String: Any]).flatMap { $0["msgtype"] is String ? $0 : nil }
        return BipeCore.forwardedContent(originalJson: originalText, newContentJson: newContent.flatMap(CoreJSON.text))
    }

    /// The Bipe fields of forwarded content (keys that start with "chat.bipe.", the forward mark among them) as a JSON
    /// object, for Timeline.sendWithExtraContent: the typed message content carries the rest (bipe-chat/bipe#1124).
    /// nil when the JSON cannot be read or has no forward mark, so that no copy goes out without its mark.
    public static func extraContentJson(contentJson: String) -> String? {
        guard let content = contentObject(contentJson), content[BipeRegistries.forwardKey] != nil else { return nil }
        let extra = content.filter { $0.key.hasPrefix(bipeFieldPrefix) }
        guard let data = try? JSONSerialization.data(withJSONObject: extra) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The prefix of Bipe's own content fields (registries: forwardKey, imageDescriptionField, ...).
    static let bipeFieldPrefix = "chat.bipe."

    /// The source line for a voice message (voice marker) or audio file with this forward count (bipe-core `voiceSource`).
    public static func source(isVoice: Bool, count: Int) -> Source {
        switch voiceSource(isVoice: isVoice, count: UInt32(clamping: count)) {
        case .forwarded: .forwarded
        case .forwardedManyTimes: .forwardedManyTimes
        case .recorded: .recorded
        case .file: .file
        }
    }

    /// The forward line of any message (bipe-core `forwardLine`): forwardedManyTimes, forwarded, or nil for a count under 1.
    public static func line(count: Int) -> Source? {
        forwardLine(count: UInt32(clamping: count)).map { $0 == .forwardedManyTimes ? .forwardedManyTimes : .forwarded }
    }

    /// The most chats one forward goes to (bipe-core `forwardChatLimit`).
    public static func chatLimit(sentCounts: [Int]) -> Int {
        Int(forwardChatLimit(sentCounts: sentCounts.map { UInt32(clamping: $0) }))
    }

    /// The content object of event JSON: its "content" when it has one, else the object itself.
    private static func contentObject(_ json: String?) -> [String: Any]? {
        guard let data = json?.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        return (object["content"] as? [String: Any]) ?? object
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Image descriptions (alt text, bipe-chat/bipe#501; registries/image-descriptions.json). The sender's description of a
/// photo travels in the m.image content as one top-level string field, inside the encrypted event.
public enum BipeImageDescription {
    /// The text the sender typed, trimmed and cut at the limit; nil when blank.
    public static func clean(_ text: String?) -> String? {
        guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(BipeRegistries.imageDescriptionMaxLength))
    }

    /// The extra content JSON for UploadParameters.extraContentJson; nil when there is no description.
    public static func extraContentJson(_ text: String?) -> String? {
        guard let text = clean(text),
              let data = try? JSONSerialization.data(withJSONObject: [BipeRegistries.imageDescriptionField: text]) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The description of an m.image event from its JSON (the whole event or its content). A non-string or blank
    /// value, or any other message type, counts as none.
    public static func read(eventJson: String?) -> String? {
        guard let data = eventJson?.data(using: .utf8),
              let event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let content = (event["content"] as? [String: Any]) ?? event
        guard content["msgtype"] as? String == "m.image" else { return nil }
        return clean(content[BipeRegistries.imageDescriptionField] as? String)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Synchronization

/// Assistant sends (bipe ADR 0210, bipe-chat/bipe#439; registries/mcp-tools.json personalSend). The switch in the global
/// account data chat.bipe.assistant_send lets the user's own assistant send; a message it sent carries chat.bipe.assistant
/// with the client name, and the apps label it. The rules match assistantSendAllowed and assistantSender in bipe-shared
/// tools/lib/registries.mjs and test-vectors/assistant-send.json.
public enum BipeAssistantSend {
    /// personalSend.accountDataType. The generator writes no constant for it yet.
    public static let accountDataType = "chat.bipe.assistant_send"
    /// personalSend.markerField.
    public static let markerField = "chat.bipe.assistant"
    /// personalSend.clientMaxLength.
    public static let clientMaxLength = 64

    /// assistantSendAllowed: on only when the content is an object with enabled exactly true. Absent is off.
    public static func allowed(content: Any?) -> Bool {
        guard let object = content as? [String: Any], let enabled = object["enabled"] as? NSNumber else { return false }
        // JSONSerialization reads true as a boolean NSNumber; 1 is a number, not true.
        return CFGetTypeID(enabled) == CFBooleanGetTypeID() && enabled.boolValue
    }

    /// assistantSendAllowed for the raw JSON of the account data. nil (no account data) is off.
    public static func allowed(json: String?) -> Bool {
        guard let data = json?.data(using: .utf8) else { return false }
        return allowed(content: try? JSONSerialization.jsonObject(with: data))
    }

    /// The account data content for the switch.
    public static func json(enabled: Bool) -> String {
        enabled ? #"{"enabled":true}"# : #"{"enabled":false}"#
    }

    /// assistantSender: the client name of a message an assistant sent, or nil.
    public static func sender(content: [String: Any]?) -> String? {
        guard let marker = content?[markerField] as? [String: Any] else { return nil }
        let client = (marker["client"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return !client.isEmpty && client.utf16.count <= clientMaxLength ? client : nil
    }

    /// assistantSender for the raw JSON of a message event (or its content). eventId keys a cache, so a timeline
    /// rebuild reads each event once; json is read only on a cache miss.
    public static func sender(eventId: String?, json: () -> String?) -> String? {
        if let eventId, let cached = cache.withLock({ $0[eventId] }) { return cached.client }
        var client: String?
        if let data = json()?.data(using: .utf8), let event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            client = sender(content: (event["content"] as? [String: Any]) ?? event)
        }
        if let eventId {
            cache.withLock { cache in
                if cache.count >= maxCachedEvents { cache.removeAll(keepingCapacity: true) }
                cache[eventId] = Cached(client: client)
            }
        }
        return client
    }

    private struct Cached: Sendable {
        let client: String?
    }

    private static let maxCachedEvents = 2000
    private static let cache = Mutex<[String: Cached]>([:])
}

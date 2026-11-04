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
    /// personalSend.accountDataType (generated).
    public static let accountDataType = BipeRegistries.assistantSendAccountDataType
    /// personalSend.markerField (generated).
    public static let markerField = BipeRegistries.assistantMarkerField
    /// personalSend.clientMaxLength (generated).
    public static let clientMaxLength = BipeRegistries.assistantClientMaxLength

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

    /// readerMarker.eventType (generated): the room state event bipe-mcp sets before it reads other members' messages.
    public static let readerEventType = BipeRegistries.assistantReaderEventType
    /// readerMarker.field (generated).
    public static let readerField = BipeRegistries.assistantReaderField

    /// assistantReaders (ADR 0130, bipe-chat/bipe#439): the sorted, unique user IDs whose own marker state event says
    /// that their assistant reads the room. A member sets only their own marker: the state key starts with @ and equals
    /// the sender, and the field is exactly true.
    public static func readers(stateEvents: [Any]) -> [String] {
        var out = Set<String>()
        for case let event as [String: Any] in stateEvents {
            guard event["type"] as? String == readerEventType,
                  let stateKey = event["state_key"] as? String, stateKey.hasPrefix("@"),
                  event["sender"] as? String == stateKey,
                  let content = event["content"] as? [String: Any],
                  let value = content[readerField] as? NSNumber,
                  CFGetTypeID(value) == CFBooleanGetTypeID(), value.boolValue
            else { continue }
            out.insert(stateKey)
        }
        return out.sorted()
    }

    /// assistantReaders for the raw JSON of GET /_matrix/client/v3/rooms/{roomId}/state (an array of state events).
    /// nil or a body that is not an array gives no readers.
    public static func readers(stateJson: Data?) -> [String] {
        guard let stateJson, let events = (try? JSONSerialization.jsonObject(with: stateJson)) as? [Any] else { return [] }
        return readers(stateEvents: events)
    }

    private struct Cached: Sendable {
        let client: String?
    }

    private static let maxCachedEvents = 2000
    private static let cache = Mutex<[String: Cached]>([:])
}

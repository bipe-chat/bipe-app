// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Publication-only groups (bipe ADR 0280, bipe-chat/bipe#1263). The rules match bipe-infra
/// compose/synapse-modules/bipe_messaging.py and registries/group-power-levels.json publicationOnly.
public enum BipeGroupPublication {
    /// True when the state events hold the mode with enabled exactly true.
    public static func isOn(stateEvents: [Any]) -> Bool {
        for case let event as [String: Any] in stateEvents {
            guard event["type"] as? String == BipeGroupPowerLevels.publicationOnlyEvent,
                  event["state_key"] as? String == "",
                  let content = event["content"] as? [String: Any],
                  let value = content["enabled"] as? NSNumber,
                  CFGetTypeID(value) == CFBooleanGetTypeID()
            else { continue }
            return value.boolValue
        }
        return false
    }

    /// isOn for the raw JSON of GET /_matrix/client/v3/rooms/{roomId}/state. nil when the body is not an array.
    public static func isOn(stateJson: Data?) -> Bool? {
        guard let stateJson, let events = (try? JSONSerialization.jsonObject(with: stateJson)) as? [Any] else { return nil }
        return isOn(stateEvents: events)
    }

    /// The content of the state event.
    public static func content(enabled: Bool) -> String { "{\"enabled\":\(enabled)}" }
}

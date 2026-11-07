// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeGroupPowerLevels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Publication-only groups (bipe ADR 0280, bipe-chat/bipe#1263). The rules match bipe-infra
 * compose/synapse-modules/bipe_messaging.py and registries/group-power-levels.json publicationOnly.
 */
object BipeGroupPublication {
    /** True when the state events hold the mode with enabled exactly true. */
    fun isOn(stateEvents: JsonArray): Boolean {
        for (element in stateEvents) {
            val event = element as? JsonObject ?: continue
            if ((event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != BipeGroupPowerLevels.PUBLICATION_ONLY_EVENT) continue
            if ((event["state_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "") continue
            val value = (event["content"] as? JsonObject)?.get("enabled") as? JsonPrimitive ?: continue
            if (value.isString) continue
            return value.content == "true"
        }
        return false
    }

    /** isOn for the raw JSON of GET /_matrix/client/v3/rooms/{roomId}/state. Null when the body is not an array. */
    fun isOn(stateJson: String?): Boolean? {
        val array = stateJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return null
        return isOn(array)
    }

    /** The content of the state event. */
    fun content(enabled: Boolean): String = "{\"enabled\":$enabled}"
}

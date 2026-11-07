// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Settings that follow the account (bipe-chat/bipe#1161; bipe-shared registries/synced-settings.json). Global account
 * data [BipeRegistries.SYNCED_SETTINGS_EVENT_TYPE]. Port of syncedSettingsReconcile in bipe-shared
 * tools/lib/registries.mjs. Same rules as ios/BipeKit BipeSyncedSettings.swift.
 */
object BipeSyncedSettings {
    const val EVENT_TYPE = BipeRegistries.SYNCED_SETTINGS_EVENT_TYPE
    const val READ_RECEIPTS = "readReceipts"
    const val PALETTE = "palette"
    const val CHAT_BACKGROUND = "chatBackground"
    const val MURAL = "mural"

    /** The event content, with every key. Empty on null or malformed content. */
    fun parse(json: String?): Map<String, JsonElement> {
        if (json == null) return emptyMap()
        return runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: emptyMap()
    }

    /** The account's settings after this device reads them. See registries/synced-settings.json. */
    fun reconcile(
        server: Map<String, JsonElement>,
        local: Map<String, JsonElement>,
        pending: Set<String>,
        first: Boolean,
    ): Map<String, JsonElement> {
        val result = server.toMutableMap()
        if (first) {
            for ((key, value) in local) if (key !in result) result[key] = value
            if (bool(local, READ_RECEIPTS) == false) result[READ_RECEIPTS] = JsonPrimitive(false)
        }
        for (key in pending) local[key]?.let { result[key] = it }
        return result
    }

    fun bool(settings: Map<String, JsonElement>, key: String): Boolean? =
        (settings[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun string(settings: Map<String, JsonElement>, key: String): String? =
        (settings[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun content(settings: Map<String, JsonElement>): String = JsonObject(settings).toString()
}

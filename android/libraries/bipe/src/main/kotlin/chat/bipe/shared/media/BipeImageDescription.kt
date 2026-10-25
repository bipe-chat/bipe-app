// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.media

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Image descriptions (alt text, bipe-chat/bipe#501; registries/image-descriptions.json). The sender's description of a
 * photo travels in the m.image content as one top-level string field, inside the encrypted event.
 */
object BipeImageDescription {
    /** The text the sender typed, trimmed and cut at the limit; null when blank. */
    fun clean(text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.take(BipeRegistries.IMAGE_DESCRIPTION_MAX_LENGTH)

    /** The extra content JSON for UploadParameters.extraContentJson; null when there is no description. */
    fun extraContentJson(text: String?): String? {
        val description = clean(text) ?: return null
        return buildJsonObject { put(BipeRegistries.IMAGE_DESCRIPTION_FIELD, description) }.toString()
    }

    /**
     * The description of an m.image event from its JSON (the whole event or its content). A non-string or blank
     * value, or any other message type, counts as none.
     */
    fun read(eventJson: String?): String? {
        val event = eventJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val content = event["content"] as? JsonObject ?: event
        if ((content["msgtype"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "m.image") return null
        val value = content[BipeRegistries.IMAGE_DESCRIPTION_FIELD] as? JsonPrimitive ?: return null
        return if (value.isString) clean(value.content) else null
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.drafts

import chat.bipe.shared.generated.BipeRegistries
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Assistant sends (bipe ADR 0210, bipe-chat/bipe#439; registries/mcp-tools.json personalSend). The switch in the global
 * account data chat.bipe.assistant_send lets the user's own assistant send; a message it sent carries chat.bipe.assistant
 * with the client name, and the apps label it. The rules match assistantSendAllowed and assistantSender in bipe-shared
 * tools/lib/registries.mjs and test-vectors/assistant-send.json.
 */
object BipeAssistantSend {
    /** personalSend.accountDataType (generated). */
    const val ACCOUNT_DATA_TYPE = BipeRegistries.ASSISTANT_SEND_ACCOUNT_DATA_TYPE
    /** personalSend.markerField (generated). */
    const val MARKER_FIELD = BipeRegistries.ASSISTANT_MARKER_FIELD
    /** personalSend.clientMaxLength (generated). */
    const val CLIENT_MAX_LENGTH = BipeRegistries.ASSISTANT_CLIENT_MAX_LENGTH

    private const val MAX_CACHED_EVENTS = 2000
    /** Event ID to client name; "" for a message without the marker. */
    private val cache = ConcurrentHashMap<String, String>()

    /** assistantSendAllowed: on only when the content is an object with enabled exactly true. Absent is off. */
    fun allowed(content: JsonElement?): Boolean {
        val enabled = (content as? JsonObject)?.get("enabled") as? JsonPrimitive ?: return false
        return !enabled.isString && enabled.content == "true"
    }

    /** assistantSendAllowed for the raw JSON of the account data. Null (no account data) is off. */
    fun allowed(json: String?): Boolean = allowed(parse(json))

    /** The account data content for the switch. */
    fun json(enabled: Boolean): String = if (enabled) """{"enabled":true}""" else """{"enabled":false}"""

    /** assistantSender: the client name of a message an assistant sent, or null. */
    fun sender(content: JsonObject?): String? {
        val marker = content?.get(MARKER_FIELD) as? JsonObject ?: return null
        val client = (marker["client"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        return client.takeIf { it.isNotEmpty() && it.length <= CLIENT_MAX_LENGTH }
    }

    /**
     * assistantSender for the raw JSON of a message event (or its content). [eventId] keys a cache, so a timeline
     * rebuild reads each event once; [json] is read only on a cache miss.
     */
    fun sender(eventId: String?, json: () -> String?): String? {
        if (eventId != null) cache[eventId]?.let { return it.ifEmpty { null } }
        val event = parse(json()) as? JsonObject
        val client = event?.let { sender(it["content"] as? JsonObject ?: it) }
        if (eventId != null) {
            if (cache.size >= MAX_CACHED_EVENTS) cache.clear()
            cache[eventId] = client.orEmpty()
        }
        return client
    }

    private fun parse(json: String?): JsonElement? = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
}

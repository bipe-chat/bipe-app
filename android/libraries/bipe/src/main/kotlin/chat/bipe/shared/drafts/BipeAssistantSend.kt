// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.drafts

import chat.bipe.shared.generated.BipeRegistries
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    /** readerMarker.eventType (generated): the room state event bipe-mcp sets before it reads other members' messages. */
    const val READER_EVENT_TYPE = BipeRegistries.ASSISTANT_READER_EVENT_TYPE
    /** readerMarker.field (generated). */
    const val READER_FIELD = BipeRegistries.ASSISTANT_READER_FIELD

    /**
     * assistantReaders (ADR 0130, bipe-chat/bipe#439): the sorted, unique user IDs whose own marker state event says
     * that their assistant reads the room. A member sets only their own marker: the state key starts with @ and equals
     * the sender, and the field is exactly true.
     */
    fun readers(stateEvents: JsonArray): List<String> {
        val out = sortedSetOf<String>()
        for (element in stateEvents) {
            val event = element as? JsonObject ?: continue
            if ((event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != READER_EVENT_TYPE) continue
            val stateKey = (event["state_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (!stateKey.startsWith("@")) continue
            if ((event["sender"] as? JsonPrimitive)?.takeIf { it.isString }?.content != stateKey) continue
            val value = (event["content"] as? JsonObject)?.get(READER_FIELD) as? JsonPrimitive ?: continue
            if (!value.isString && value.content == "true") out.add(stateKey)
        }
        return out.toList()
    }

    /**
     * assistantReaders for the raw JSON of GET /_matrix/client/v3/rooms/{roomId}/state (an array of state events).
     * Null or a body that is not an array gives no readers.
     */
    fun readers(stateJson: String?): List<String> = (parse(stateJson) as? JsonArray)?.let(::readers).orEmpty()

    private fun parse(json: String?): JsonElement? = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
}

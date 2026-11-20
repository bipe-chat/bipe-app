// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetEventFilter

/** Spec read-receipts, delivered ack (ADR 0222): the to-device event chat.bipe.delivered with room_id and event_id. */
object DeliveredAck {
    const val EVENT_TYPE = "chat.bipe.delivered"

    data class Received(val sender: String, val roomId: String, val eventId: String)

    /** The widget asks for these and nothing else. */
    val capabilityNames = listOf(
        "org.matrix.msc3819.send.to_device:$EVENT_TYPE",
        "org.matrix.msc3819.receive.to_device:$EVENT_TYPE",
    )

    fun approved() = WidgetCapabilities(
        read = listOf(WidgetEventFilter.ToDevice(EVENT_TYPE)),
        send = listOf(WidgetEventFilter.ToDevice(EVENT_TYPE)),
        requiresClient = false,
        updateDelayedEvent = false,
        sendDelayedEvent = false,
        downloadFiles = false,
        rtcTransports = false,
    )

    fun content(roomId: String, eventId: String): JsonObject = buildJsonObject {
        put("room_id", roomId)
        put("event_id", eventId)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** The to-device message the driver pushes to the widget: type, sender, content. */
    fun parse(message: JsonObject): Received? {
        if (message.string("type") != EVENT_TYPE) return null
        val sender = message.string("sender") ?: return null
        val content = message["content"] as? JsonObject ?: return null
        val roomId = content.string("room_id")?.takeIf { it.isNotEmpty() } ?: return null
        val eventId = content.string("event_id")?.takeIf { it.isNotEmpty() } ?: return null
        return Received(sender, roomId, eventId)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import chat.bipe.shared.calls.BipeCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.WidgetEventFilter
import org.matrix.rustcomponents.sdk.WidgetSettings
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import java.util.UUID

/**
 * The Matrix transport of a call (spec call, ADR 0186): the matrix-sdk-ffi widget driver with no page.
 * The app is the widget. It answers the driver's toWidget requests and sends fromWidget requests
 * (MSC2762 events and state, MSC3819 to-device, MSC4157 delayed events). All calls run on [scope] (main thread).
 */
class CallWidget(
    userId: String,
    deviceId: String,
    private val scope: CoroutineScope,
    extraSend: List<String> = emptyList(),
    extraRead: List<String> = emptyList(),
    private val onIncoming: (Incoming) -> Unit,
) {
    class Failure(message: String) : Exception(message)

    /** What the driver pushes to the widget. */
    sealed interface Incoming {
        data class Event(val event: JsonObject) : Incoming
        data class State(val events: List<JsonObject>) : Incoming
        data class ToDevice(val message: JsonObject) : Incoming
    }

    val widgetId = "bipe-call-${UUID.randomUUID()}"
    // The driver needs a URL; nothing loads it.
    private val made = makeWidgetDriver(WidgetSettings(widgetId, false, "https://bipe.chat/call"))
    private val capabilityNames: List<String>
    private val approved: WidgetCapabilities
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val ready = CompletableDeferred<Unit>()
    private var closed = false
    private var counter = 0

    init {
        val ownKey = BipeCall.stateKey(userId, deviceId)
        val member = BipeCall.MEMBER_EVENT_TYPE
        val key = BipeCall.KEY_EVENT_TYPE
        val sendEvents = listOf(key) + extraSend
        val readEvents = listOf(key) + extraRead
        capabilityNames = listOf(
            "org.matrix.msc4157.send.delayed_event",
            "org.matrix.msc4157.update_delayed_event",
            "org.matrix.msc2762.receive.state_event:$member",
            "org.matrix.msc2762.send.state_event:$member#$ownKey",
            "org.matrix.msc3819.send.to_device:$key",
            "org.matrix.msc3819.receive.to_device:$key",
        ) + sendEvents.map { "org.matrix.msc2762.send.event:$it" } + readEvents.map { "org.matrix.msc2762.receive.event:$it" }
        approved = WidgetCapabilities(
            read = listOf(WidgetEventFilter.StateWithType(member), WidgetEventFilter.ToDevice(key)) +
                readEvents.map { WidgetEventFilter.MessageLikeWithType(it) },
            send = listOf(WidgetEventFilter.StateWithTypeAndStateKey(member, ownKey), WidgetEventFilter.ToDevice(key)) +
                sendEvents.map { WidgetEventFilter.MessageLikeWithType(it) },
            requiresClient = false,
            updateDelayedEvent = true,
            sendDelayedEvent = true,
            downloadFiles = false,
            rtcTransports = false,
        )
    }

    /** Runs the driver for the room and reads its messages until [close]. */
    fun start(room: Room) {
        val provider = CallCapabilities(approved)
        scope.launch(Dispatchers.IO) { made.driver.run(room, provider) }
        scope.launch {
            while (!closed) {
                val text = made.handle.recv() ?: break
                handleMessage(text)
            }
            close()
        }
    }

    /** Waits until the driver approved the capabilities. */
    suspend fun ready() = ready.await()

    fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { it.completeExceptionally(Failure("closed")) }
        pending.clear()
        ready.complete(Unit)
    }

    /** A room event, or a state event with [stateKey]. [delayMs]: a delayed event (MSC4140); the answer has `delay_id`. */
    suspend fun sendEvent(type: String, content: JsonObject, stateKey: String? = null, delayMs: Long? = null): JsonObject =
        request("send_event", buildJsonObject {
            put("type", type)
            put("content", content)
            if (stateKey != null) put("state_key", stateKey)
            if (delayMs != null) put("delay", delayMs)
        })

    /** [action]: restart, cancel or send. */
    suspend fun updateDelayed(delayId: String, action: String) {
        request("org.matrix.msc4157.update_delayed_event", buildJsonObject {
            put("delay_id", delayId)
            put("action", action)
        })
    }

    private suspend fun request(action: String, data: JsonObject): JsonObject {
        if (closed) throw Failure("closed")
        counter += 1
        val requestId = "bipe-$counter"
        val message = buildJsonObject {
            put("api", "fromWidget")
            put("widgetId", widgetId)
            put("requestId", requestId)
            put("action", action)
            put("data", data)
        }
        val answer = CompletableDeferred<JsonObject>()
        pending[requestId] = answer
        if (!made.handle.send(message.toString())) {
            pending.remove(requestId)
            throw Failure("driver stopped")
        }
        val response = try {
            withTimeout(REQUEST_TIMEOUT_MS) { answer.await() }
        } finally {
            pending.remove(requestId)
        }
        (response["error"] as? JsonObject)?.let { throw Failure((it["message"] as? JsonPrimitive)?.contentOrNull ?: action) }
        return response
    }

    private suspend fun handleMessage(text: String) {
        val message = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val requestId = (message["requestId"] as? JsonPrimitive)?.contentOrNull ?: return
        if ((message["api"] as? JsonPrimitive)?.contentOrNull == "fromWidget") {
            pending.remove(requestId)?.complete(message["response"] as? JsonObject ?: JsonObject(emptyMap()))
            return
        }
        val data = message["data"] as? JsonObject ?: JsonObject(emptyMap())
        var answer: JsonObject = JsonObject(emptyMap())
        when ((message["action"] as? JsonPrimitive)?.contentOrNull) {
            "capabilities" -> answer = buildJsonObject {
                put("capabilities", buildJsonArray { capabilityNames.forEach { add(JsonPrimitive(it)) } })
            }
            "notify_capabilities" -> ready.complete(Unit)
            "send_event" -> onIncoming(Incoming.Event(data))
            "update_state" -> onIncoming(Incoming.State((data["state"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()))
            "send_to_device" -> onIncoming(Incoming.ToDevice(data))
        }
        val reply: Map<String, JsonElement> = message + ("response" to answer)
        made.handle.send(JsonObject(reply).toString())
    }

    companion object {
        /** A fromWidget request without an answer after this long fails. */
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

/** Grants the call exactly what CallWidget asks for, and nothing else. */
private class CallCapabilities(private val approved: WidgetCapabilities) : WidgetCapabilitiesProvider {
    override fun acquireCapabilities(capabilities: WidgetCapabilities): WidgetCapabilities = WidgetCapabilities(
        read = capabilities.read.filter { it in approved.read },
        send = capabilities.send.filter { it in approved.send },
        requiresClient = false,
        updateDelayedEvent = capabilities.updateDelayedEvent && approved.updateDelayedEvent,
        sendDelayedEvent = capabilities.sendDelayedEvent && approved.sendDelayedEvent,
        downloadFiles = false,
        rtcTransports = false,
    )
}

/** The string of a JSON field, or null. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.toLong()

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.content.Context
import android.content.SharedPreferences
import chat.bipe.app.calls.CallWidget
import chat.bipe.app.calls.text
import chat.bipe.app.settings.SettingsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
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

    /** The to-device message the driver pushes to the widget: type, sender, content. */
    fun parse(message: JsonObject): Received? {
        if (message.text("type") != EVENT_TYPE) return null
        val sender = message.text("sender") ?: return null
        val content = message["content"] as? JsonObject ?: return null
        val roomId = content.text("room_id")?.takeIf { it.isNotEmpty() } ?: return null
        val eventId = content.text("event_id")?.takeIf { it.isNotEmpty() } ?: return null
        return Received(sender, roomId, eventId)
    }
}

/** The delivered events of each DM on this phone (spec read-receipts notes: delivered state is device-local). */
object DeliveredLog {
    /**
     * Acked event IDs kept per room. Acks from several devices can arrive out of order, so the row builder takes the
     * newest one it finds in the timeline.
     */
    const val KEPT_PER_ROOM = 20
    private const val FILE = "bipe_delivered"
    private var prefs: SharedPreferences? = null
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The room ID after [add] stored a new ack. */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun eventIds(userId: String, roomId: String): List<String> = read(userId)[roomId].orEmpty()

    /** Adds an acked event. True when it was new. */
    @Synchronized
    fun add(userId: String, roomId: String, eventId: String): Boolean {
        val prefs = prefs ?: return false
        val all = read(userId).toMutableMap()
        val ids = all[roomId].orEmpty()
        if (eventId in ids) return false
        all[roomId] = (ids + eventId).takeLast(KEPT_PER_ROOM)
        val json = JsonObject(all.mapValues { (_, list) -> JsonArray(list.map { JsonPrimitive(it) }) })
        prefs.edit().putString(userId, json.toString()).apply()
        _changes.tryEmit(roomId)
        return true
    }

    private fun read(userId: String): Map<String, List<String>> {
        val text = prefs?.getString(userId, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, ids) ->
                ids.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
        }.getOrDefault(emptyMap())
    }
}

/** Spec read-receipts readReceiptsEnabled (ADR 0222): the Privacidade switch, on this phone, on by default. */
object ReadReceiptSetting {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE)
    }

    val isOn: Boolean get() = prefs?.getBoolean(SettingsKeys.READ_RECEIPTS, true) ?: true

    /** A group always sends a public receipt. A DM sends a private one while the switch is off. */
    fun receiptType(isDm: Boolean): ReceiptType = if (isDm && !isOn) ReceiptType.READ_PRIVATE else ReceiptType.READ
}

/**
 * Sends and receives delivered acks for one account. The transport is the matrix-sdk-ffi widget driver with no page
 * (CallWidget), bound to a DM room: in an encrypted room the driver sends the ack with Olm and passes on only encrypted
 * to-device events. One driver receives the acks of every DM. Runs on the main thread, as CallWidget needs.
 */
class DeliveredAcks(private val client: Client, private val ownUserId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var widget: CallWidget? = null
    /** The newest event acked per room in this process: at most one ack per room per batch. */
    private val lastSent = mutableMapOf<String, String>()

    /** Starts the receiver on the first joined DM, when no receiver runs. */
    fun start() {
        scope.launch {
            if (widget?.isClosed == false) return@launch
            for (room in runCatching { client.rooms() }.getOrDefault(emptyList())) {
                val info = runCatching { room.roomInfo() }.getOrNull() ?: continue
                if (info.isDm && info.membership == Membership.JOINED) {
                    bind(room)
                    return@launch
                }
            }
        }
    }

    /** Action ack send: the newest event that this device got in the DM from [sender]. Nothing for an event acked already. */
    fun send(room: Room, eventId: String, sender: String) {
        scope.launch {
            val roomId = room.id()
            if (sender == ownUserId || lastSent[roomId] == eventId) return@launch
            val widget = bind(room) ?: return@launch
            lastSent[roomId] = eventId
            try {
                withTimeoutOrNull(TIMEOUT_MS) { widget.ready() } ?: throw CallWidget.Failure("timeout")
                widget.sendToDevice(DeliveredAck.EVENT_TYPE, buildJsonObject {
                    put(sender, buildJsonObject { put("*", DeliveredAck.content(roomId, eventId)) })
                })
            } catch (failure: Exception) {
                // A request timeout is a TimeoutCancellationException; any other cancellation ends the job.
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                // The driver stopped or did not answer: the next ack starts a new one.
                lastSent.remove(roomId)
                if (this@DeliveredAcks.widget === widget) {
                    widget.close()
                    this@DeliveredAcks.widget = null
                }
            }
        }
    }

    fun stop() {
        widget?.close()
        widget = null
        scope.cancel()
    }

    /** The running widget, or a new one on [room]. */
    private fun bind(room: Room): CallWidget? {
        widget?.takeIf { !it.isClosed }?.let { return it }
        val made = runCatching {
            CallWidget(scope, DeliveredAck.capabilityNames, DeliveredAck.approved(), "bipe-delivered") { incoming ->
                (incoming as? CallWidget.Incoming.ToDevice)?.let { DeliveredAck.parse(it.message) }?.let { ack ->
                    scope.launch { receive(ack) }
                }
            }
        }.getOrNull() ?: return null
        widget = made
        made.start(room)
        return made
    }

    /** Action ack receive: only from the DM's other member, for a DM with that member. */
    private suspend fun receive(ack: DeliveredAck.Received) {
        if (ack.sender == ownUserId) return
        val room = runCatching { client.getRoom(ack.roomId) }.getOrNull() ?: return
        val isDm = runCatching { room.roomInfo().isDm }.getOrDefault(false)
        val joined = runCatching { room.member(ack.sender).membership }.getOrNull() is MembershipState.Join
        if (isDm && joined) DeliveredLog.add(ownUserId, ack.roomId, ack.eventId)
    }

    private companion object {
        /** The capability approval without an answer after this long fails. */
        const val TIMEOUT_MS = 15_000L
    }
}

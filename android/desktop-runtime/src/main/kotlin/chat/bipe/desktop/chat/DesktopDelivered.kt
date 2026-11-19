// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.toVectorDiff
import chat.bipe.app.timeline.DeliveredAck
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.account.NativeResource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.*

/** One Olm to-device receiver per account, with account-owned and device-local receipt state. */
class DesktopDelivered internal constructor(private val runtime: MatrixAccountRuntime, private val owner: AccountTaskGroup) {
    private data class Incoming(val roomId: String, val timestamp: Long)
    private val mutableAcks = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val acks = mutableAcks.asStateFlow()
    private val incoming = Channel<Incoming>(256)
    private val persistence = Mutex()
    private val acked = mutableMapOf<String, Pair<Long, String>>()
    private var widget: DeliveryWidget? = null

    internal fun changed(roomId: String, timestamp: Long) { incoming.trySend(Incoming(roomId, timestamp)) }

    internal suspend fun start() {
        val saved = runCatching { runtime.lifetime.lease.readDelivered()?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
        owner.publish {
            mutableAcks.value = saved?.mapNotNull { (id, ids) ->
                val events = (ids as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                events?.let { id to it.takeLast(PER_ROOM) }
            }?.toMap().orEmpty()
        }
        owner.own(NativeResource { incoming.cancel(); retireWidget() })
        owner.launch {
            bindExisting()
            for (request in incoming) {
                try { acknowledge(request) }
                catch (_: TimeoutCancellationException) { retireWidget() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { retireWidget() }
            }
        }
    }

    private suspend fun bindExisting() {
        val rooms = runtime.client.rooms()
        try {
            val roomId = rooms.firstOrNull { isEncryptedDm(it) }?.id() ?: return
            ensureWidget(roomId)
        } catch (_: TimeoutCancellationException) { retireWidget() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { retireWidget() }
        finally { rooms.forEach { it.destroy() } }
    }

    private suspend fun acknowledge(request: Incoming) {
        if ((acked[request.roomId]?.first ?: Long.MIN_VALUE) >= request.timestamp) return
        val room = runtime.client.getRoom(request.roomId) ?: return
        try {
            if (!isEncryptedDm(room)) return
            val event = newestIncoming(room) ?: return
            if (event.id == acked[request.roomId]?.second) return
            if (!isPeer(room, event.sender)) return
            ensureWidget(request.roomId).send(request.roomId, event.id, event.sender)
            acked[request.roomId] = request.timestamp to event.id
        } finally { room.destroy() }
    }

    private suspend fun ensureWidget(roomId: String): DeliveryWidget {
        widget?.takeIf { it.active }?.let { return it }
        retireWidget()
        val group = AccountTaskGroup.create(runtime.lifetime)
        return try {
            val room = runtime.client.getRoom(roomId) ?: error("Room unavailable")
            group.own(NativeResource { room.destroy() })
            check(isEncryptedDm(room))
            DeliveryWidget(group).also { made ->
                widget = made
                made.start(room, ::receive)
                made.ready()
            }
        } catch (error: Exception) { group.close(); widget = null; throw error }
    }

    private suspend fun retireWidget() {
        widget?.close()
        widget = null
    }

    private suspend fun receive(ack: DeliveredAck.Received) {
        val room = runtime.client.getRoom(ack.roomId) ?: return
        try { if (!isEncryptedDm(room) || !isPeer(room, ack.sender)) return }
        finally { room.destroy() }
        persistence.withLock {
            val current = mutableAcks.value
            val ids = current[ack.roomId].orEmpty()
            if (ack.eventId in ids) return
            val updated = current + (ack.roomId to (ids + ack.eventId).takeLast(PER_ROOM))
            val newest = ((updated - ack.roomId).toList().takeLast(MAX_ROOMS - 1) + (ack.roomId to updated.getValue(ack.roomId))).toMap()
            runtime.lifetime.requireCurrent()
            runtime.lifetime.lease.writeDelivered(JsonObject(newest.mapValues { (_, events) ->
                JsonArray(events.map { JsonPrimitive(it) })
            }).toString())
            owner.publish { mutableAcks.value = newest }
        }
    }

    private suspend fun isPeer(room: Room, id: String): Boolean {
        if (id == runtime.client.userId()) return false
        return room.member(id).membership is MembershipState.Join
    }

    private suspend fun isEncryptedDm(room: Room): Boolean {
        val info = room.roomInfo()
        return try { info.isDm && info.membership == Membership.JOINED && room.isEncrypted() } finally { info.destroy() }
    }

    private data class ReceivedEvent(val id: String, val sender: String)

    /** The SDK supplies cached items in its initial diff. There is no history request for delivery receipts. */
    private suspend fun newestIncoming(room: Room): ReceivedEvent? {
        val timeline = room.timeline()
        try {
            val initial = CompletableDeferred<ReceivedEvent?>()
            val listener = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    try {
                        var newest: ReceivedEvent? = null
                        diff.forEach { update ->
                            // The initial SDK diff is a reset or append; later diffs cannot change the captured answer.
                            if (!initial.isCompleted) {
                                val items = update.toVectorDiff()
                                items.forEachValue { item ->
                                    val event = item.asEvent() ?: return@forEachValue
                                    try {
                                        val id = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
                                        if (id != null && !event.isOwn && event.isRemote && event.content is TimelineItemContent.MsgLike)
                                            newest = ReceivedEvent(id, event.sender)
                                    } finally { event.destroy() }
                                }
                            }
                        }
                        initial.complete(newest)
                    } finally { diff.forEach { it.toVectorDiff().forEachValue { item -> item.destroy() } } }
                }
            })
            try { return withTimeout(2_000) { initial.await() } }
            finally { try { listener.cancel() } finally { listener.destroy() } }
        } finally { timeline.destroy() }
    }

    private companion object {
        const val PER_ROOM = 20
        const val MAX_ROOMS = 1_000
    }
}

private class DeliveryWidget(private val owner: AccountTaskGroup) {
    private val id = "bipe-delivered-${UUID.randomUUID()}"
    private val native = makeWidgetDriver(WidgetSettings(id, false, "https://bipe.chat/call"))
    private val approved = CompletableDeferred<Unit>()
    private val sendLock = Mutex()
    private val pending = MutableStateFlow<Reply?>(null)
    private data class Reply(val id: String, val completion: CompletableDeferred<JsonObject>)
    @Volatile var active = true
        private set

    suspend fun start(room: Room, receive: suspend (DeliveredAck.Received) -> Unit) {
        owner.own(NativeResource { native.destroy() })
        owner.launch {
            try {
                val allowed = DeliveredAck.approved()
                native.driver.run(room, object : WidgetCapabilitiesProvider {
                    override fun acquireCapabilities(capabilities: WidgetCapabilities) = WidgetCapabilities(
                        read = capabilities.read.filter { it in allowed.read }, send = capabilities.send.filter { it in allowed.send },
                        requiresClient = false, updateDelayedEvent = false, sendDelayedEvent = false,
                        downloadFiles = false, rtcTransports = false,
                    )
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The sender will retry with a new driver on the next room update. */ }
            finally { stopped() }
        }
        owner.launch {
            try {
                while (active) {
                    val raw = native.handle.recv() ?: break
                    val message = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: continue
                    if (message.string("widgetId") != id) continue
                    val requestId = message.string("requestId") ?: continue
                    if (message.string("api") == "fromWidget") {
                        pending.value?.takeIf { it.id == requestId }?.completion
                            ?.complete(message["response"] as? JsonObject ?: JsonObject(emptyMap()))
                        continue
                    }
                    if (message.string("api") != "toWidget") continue
                    val response = when (message.string("action")) {
                        "capabilities" -> buildJsonObject {
                            put("capabilities", JsonArray(DeliveredAck.capabilityNames.map { JsonPrimitive(it) }))
                        }
                        "notify_capabilities" -> { approved.complete(Unit); JsonObject(emptyMap()) }
                        "send_to_device" -> {
                            val data = message["data"] as? JsonObject
                            data?.let(DeliveredAck::parse)?.let { ack ->
                                try { receive(ack) } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Invalid or inaccessible room. */ }
                            }
                            JsonObject(emptyMap())
                        }
                        else -> JsonObject(emptyMap())
                    }
                    if (!native.handle.send(JsonObject(message + ("response" to response)).toString())) break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retire the transport on the next room update. */ }
            finally { stopped() }
        }
    }

    suspend fun ready() = withTimeout(15_000) { approved.await() }

    suspend fun send(roomId: String, eventId: String, peer: String) = sendLock.withLock {
        check(active)
        val reply = Reply(UUID.randomUUID().toString(), CompletableDeferred())
        pending.value = reply
        try {
            val request = buildJsonObject {
                put("api", "fromWidget")
                put("widgetId", id)
                put("requestId", reply.id)
                put("action", "send_to_device")
                put("data", buildJsonObject {
                    put("type", DeliveredAck.EVENT_TYPE)
                    put("messages", buildJsonObject {
                        put(peer, buildJsonObject { put("*", DeliveredAck.content(roomId, eventId)) })
                    })
                })
            }
            owner.requireCurrent()
            check(native.handle.send(request.toString()))
            check(withTimeout(15_000) { reply.completion.await() }["error"] == null)
        } finally { pending.value = null }
    }

    suspend fun close() = owner.close()

    private fun stopped() {
        active = false
        approved.completeExceptionally(IllegalStateException("Delivery widget closed"))
        pending.value?.completion?.completeExceptionally(IllegalStateException("Delivery widget closed"))
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

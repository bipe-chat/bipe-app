// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.shared.media.BipeForwardMark
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.*

class ForwardActions internal constructor(
    private val client: Client,
    private val owner: AccountTaskGroup,
    private val noName: String,
    private val source: () -> Timeline,
    private val rows: () -> List<MessageRow>,
) {
    data class Destination(val id: String, val name: String)
    data class Picker internal constructor(val chats: List<Destination>, val limit: Int,
                                           internal val targets: List<Pair<String, String>>)

    private suspend fun <T> read(operation: suspend () -> T): T {
        val task = owner.async(operation)
        try { return task.await() } finally { task.cancel() }
    }

    suspend fun prepare(ids: Set<String>): Picker = read {
        check(ids.isNotEmpty() && ids.size <= SELECTION_LIMIT)
        val targets = rows().filter { it.id in ids && it.canForward }.map { it.id to checkNotNull(it.eventId) }
        check(targets.size == ids.size)
        val limit = BipeForwardMark.chatLimit(targets.map { (_, id) -> content(id) { _, json -> BipeForwardMark.count(json) } })
        val rooms = client.rooms()
        val chats = try {
            rooms.mapNotNull { room ->
                if (room.membership() != Membership.JOINED || room.isSpace()) return@mapNotNull null
                Destination(room.id(), room.displayName()?.takeIf { it.isNotBlank() } ?: noName)
            }.sortedBy { fold(it.name) }
        } finally { rooms.forEach { it.destroy() } }
        Picker(chats, limit, targets)
    }

    /** Queue copies in timeline order. Failure stops only the affected destination. */
    suspend fun send(picker: Picker, destinations: Set<String>): List<String> {
        val task = owner.async {
            val picked = picker.chats.filter { it.id in destinations }
            check(picked.isNotEmpty() && picked.size == destinations.size && picked.size <= picker.limit)
            check(picker.targets.all { (rowId, eventId) -> rows().any { it.id == rowId && it.eventId == eventId && it.canForward } })
            val currentLimit = BipeForwardMark.chatLimit(picker.targets.map { (_, id) -> content(id) { _, json -> BipeForwardMark.count(json) } })
            check(picked.size <= currentLimit)
            picked.mapNotNull { chat ->
                try {
                    val room = client.getRoom(chat.id) ?: error("Room unavailable")
                    try {
                        check(room.membership() == Membership.JOINED && !room.isSpace())
                        val timeline = room.timeline()
                        try {
                            for ((_, id) in picker.targets) content(id) { message, json ->
                                // Each copy is checked against the current event, even if it changed after picker preparation.
                                check(picked.size <= BipeForwardMark.chatLimit(listOf(BipeForwardMark.count(json))))
                                val extra = checkNotNull(BipeForwardMark.extraContentJson(json))
                                val payload = contentWithoutRelationFromMessage(message)
                                try {
                                    owner.requireCurrent()
                                    timeline.sendWithExtraContent(payload, extra).destroy()
                                } finally { payload.destroy() }
                            }
                        } finally { timeline.destroy() }
                    } finally { room.destroy() }
                    null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { chat.name }
            }
        }
        return task.await()
    }

    private suspend fun <T> content(id: String, use: suspend (MessageContent, String) -> T): T {
        val event = source().getEventTimelineItemByEventId(id)
        try {
            val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message
            check(event.isRemote && event.localSendState == null && kind != null)
            val debug = event.lazyProvider.debugInfo()
            val json = checkNotNull(BipeForwardMark.forwardedContentJson(debug.originalJson, debug.latestEditJson))
            return use(kind.content, json)
        } finally { event.destroy() }
    }

    companion object {
        const val SELECTION_LIMIT = 10
        private val accents = Regex("\\p{M}+")
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(accents, "").lowercase(Locale.ROOT)
    }
}

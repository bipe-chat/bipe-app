// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import chat.bipe.core.WorkNoteResult
import chat.bipe.core.workNoteFromEvent
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The notes room of a work account (bipe ADR 0284 points 18 and 19, bipe-chat/bipe#1293; bipe-shared
 * registries/work-notes.json). A note is an m.text message whose field chat.bipe.work_note names the customer chat and the
 * seat that wrote it. A reply-by is an m.notice message whose field chat.bipe.reply_by says which seat sent a reply to a
 * customer. Both are m.room.message: matrix-sdk-ui drops custom event types from the timeline. The rules match workNoteFromEvent and replyByFromEvent in
 * bipe-shared tools/lib/registries.mjs and test-vectors/work-notes.json.
 */
object BipeWorkNotes {
    /** One note that the apps show on a customer chat. [seatId] null: no seat of the account wrote it, no author shows. */
    data class Note(val roomId: String, val body: String, val seatId: String?)

    /** One reply-by event that the apps show as a name on a reply. */
    data class ReplyBy(val roomId: String, val eventId: String, val seatId: String)

    /** The result of workNoteFromEvent. */
    sealed interface NoteResult {
        data class Show(val note: Note) : NoteResult
        data class Hide(val reason: String) : NoteResult
    }

    /** The result of replyByFromEvent. */
    sealed interface ReplyByResult {
        data class Show(val replyBy: ReplyBy) : ReplyByResult
        data class Hide(val reason: String) : ReplyByResult
    }

    /** The notes room ID from the content of the global account data chat.bipe.work_notes_room. Null when absent or malformed. */
    fun roomId(accountDataJson: String?): String? {
        val content = parse(accountDataJson) as? JsonObject ?: return null
        return string(content["room_id"])?.takeIf { it.startsWith("!") && it.none(Char::isWhitespace) }
    }

    /** The account data content that names the notes room. */
    fun accountDataContent(roomId: String): String = buildJsonObject { put("room_id", roomId) }.toString()

    /** The content of a note on the customer chat [roomId] by [seatId]: m.text with the body and noteField {room_id, seat_id}. */
    fun noteContent(body: String, roomId: String, seatId: String?): String = buildJsonObject {
        put("msgtype", "m.text")
        put("body", body)
        put(BipeRegistries.WORK_NOTE_FIELD, buildJsonObject {
            put("room_id", roomId)
            seatId?.let { put("seat_id", it) }
        })
    }.toString()

    /**
     * The content of a reply-by message (type m.room.message): m.notice with replyByBody and replyByField
     * {room_id, event_id, seat_id}: seat [seatId] sent event [eventId] in the customer chat [roomId].
     */
    fun replyByContent(roomId: String, eventId: String, seatId: String): String = buildJsonObject {
        put("msgtype", "m.notice")
        put("body", BipeRegistries.WORK_REPLY_BY_BODY)
        put(BipeRegistries.WORK_REPLY_BY_FIELD, buildJsonObject {
            put("room_id", roomId)
            put("event_id", eventId)
            put("seat_id", seatId)
        })
    }.toString()

    /**
     * workNoteFromEvent for the raw JSON of a notes room event. [sender] (EventTimelineItem.sender) replaces the JSON
     * sender when given, and [redacted] marks a redacted timeline item.
     */
    fun noteFromEventJson(json: String?, sender: String? = null, redacted: Boolean = false, ownUserId: String,
                          joinedRoomIds: Set<String>, seatIds: Set<String>): NoteResult {
        val event = parse(json) as? JsonObject ?: return NoteResult.Hide("type")
        return noteFromEvent(string(event["type"]), sender ?: string(event["sender"]), redacted || isRedacted(event), event["content"],
            ownUserId, joinedRoomIds, seatIds)
    }

    /** workNoteFromEvent: an event { sender, type, redacted?, content } of the notes room. [seatIds] from GET /v1/me/business/seats. */
    fun noteFromEvent(event: JsonObject, ownUserId: String, joinedRoomIds: Set<String>, seatIds: Set<String>): NoteResult =
        noteFromEvent(string(event["type"]), string(event["sender"]), isRedacted(event), event["content"], ownUserId, joinedRoomIds, seatIds)

    /** bipe-core `workNoteFromEvent` on the event { type, sender, redacted, content }. */
    private fun noteFromEvent(type: String?, sender: String?, redacted: Boolean, rawContent: JsonElement?,
                              ownUserId: String, joinedRoomIds: Set<String>, seatIds: Set<String>): NoteResult =
        when (val result = workNoteFromEvent(event(type, sender, redacted, rawContent), ownUserId, joinedRoomIds.toList(), seatIds.toList())) {
            is WorkNoteResult.Show -> NoteResult.Show(Note(result.note.roomId, result.note.body, result.note.seatId))
            is WorkNoteResult.Hide -> NoteResult.Hide(result.reason)
        }

    /** replyByFromEvent for the raw JSON of a notes room event; [sender] and [redacted] as for [noteFromEventJson]. */
    fun replyByFromEventJson(json: String?, sender: String? = null, redacted: Boolean = false, ownUserId: String, seatIds: Set<String>): ReplyByResult {
        val event = parse(json) as? JsonObject ?: return ReplyByResult.Hide("type")
        return replyByFromEvent(string(event["type"]), sender ?: string(event["sender"]), redacted || isRedacted(event), event["content"], ownUserId, seatIds)
    }

    /** replyByFromEvent: an event { sender, type, redacted?, content } of the notes room. [seatIds] from GET /v1/me/business/seats. */
    fun replyByFromEvent(event: JsonObject, ownUserId: String, seatIds: Set<String>): ReplyByResult =
        replyByFromEvent(string(event["type"]), string(event["sender"]), isRedacted(event), event["content"], ownUserId, seatIds)

    /** bipe-core `replyByFromEvent` on the event { type, sender, redacted, content }. */
    private fun replyByFromEvent(type: String?, sender: String?, redacted: Boolean, rawContent: JsonElement?,
                                 ownUserId: String, seatIds: Set<String>): ReplyByResult =
        when (val result = chat.bipe.core.replyByFromEvent(event(type, sender, redacted, rawContent), ownUserId, seatIds.toList())) {
            is chat.bipe.core.ReplyByResult.Show -> ReplyByResult.Show(ReplyBy(result.replyBy.roomId, result.replyBy.eventId, result.replyBy.seatId))
            is chat.bipe.core.ReplyByResult.Hide -> ReplyByResult.Hide(result.reason)
        }

    private fun event(type: String?, sender: String?, redacted: Boolean, rawContent: JsonElement?): String = buildJsonObject {
        type?.let { put("type", it) }
        sender?.let { put("sender", it) }
        put("redacted", redacted)
        rawContent?.let { put("content", it) }
    }.toString()

    private fun isRedacted(event: JsonObject): Boolean {
        val unsigned = event["unsigned"] as? JsonObject
        return unsigned?.get("redacted_because").let { it != null && it !is JsonNull } ||
            (event["redacted"] as? JsonPrimitive)?.content == "true"
    }

    private fun parse(json: String?): JsonElement? = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

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
 * registries/work-notes.json). A note is an m.text message whose field chat.bipe.work_note names the customer chat. A
 * reply-by event says which seat sent a reply to a customer. The rules match workNoteFromEvent and replyByFromEvent in
 * bipe-shared tools/lib/registries.mjs and test-vectors/work-notes.json.
 */
object BipeWorkNotes {
    /** One note that the apps show on a customer chat. */
    data class Note(val roomId: String, val body: String)

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

    /** The content of a note on the customer chat [roomId]: m.text with the body and noteField {room_id}. */
    fun noteContent(body: String, roomId: String): String = buildJsonObject {
        put("msgtype", "m.text")
        put("body", body)
        put(BipeRegistries.WORK_NOTE_FIELD, buildJsonObject { put("room_id", roomId) })
    }.toString()

    /** The content of a reply-by event: seat [seatId] sent event [eventId] in the customer chat [roomId]. */
    fun replyByContent(roomId: String, eventId: String, seatId: String): String = buildJsonObject {
        put("room_id", roomId)
        put("event_id", eventId)
        put("seat_id", seatId)
    }.toString()

    /**
     * workNoteFromEvent for the raw JSON of a notes room event. [sender] (EventTimelineItem.sender) replaces the JSON
     * sender when given, and [redacted] marks a redacted timeline item.
     */
    fun noteFromEventJson(json: String?, sender: String? = null, redacted: Boolean = false, ownUserId: String, joinedRoomIds: Set<String>): NoteResult {
        val event = parse(json) as? JsonObject ?: return NoteResult.Hide("type")
        return noteFromEvent(string(event["type"]), sender ?: string(event["sender"]), redacted || isRedacted(event), event["content"], ownUserId, joinedRoomIds)
    }

    /** workNoteFromEvent: an event { sender, type, redacted?, content } of the notes room. */
    fun noteFromEvent(event: JsonObject, ownUserId: String, joinedRoomIds: Set<String>): NoteResult =
        noteFromEvent(string(event["type"]), string(event["sender"]), isRedacted(event), event["content"], ownUserId, joinedRoomIds)

    private fun noteFromEvent(type: String?, sender: String?, redacted: Boolean, rawContent: JsonElement?,
                              ownUserId: String, joinedRoomIds: Set<String>): NoteResult {
        if (type != "m.room.message") return NoteResult.Hide("type")
        if (redacted) return NoteResult.Hide("redacted")
        if (sender != ownUserId) return NoteResult.Hide("sender")
        val content = rawContent as? JsonObject
        if (content == null || string(content["msgtype"]) != "m.text") return NoteResult.Hide("content")
        val field = content[BipeRegistries.WORK_NOTE_FIELD] as? JsonObject ?: return NoteResult.Hide("field")
        val roomId = string(field["room_id"])?.takeIf { it.startsWith("!") } ?: return NoteResult.Hide("room_id")
        if (roomId !in joinedRoomIds) return NoteResult.Hide("not_joined")
        val body = string(content["body"])?.takeIf { it.isNotBlank() } ?: return NoteResult.Hide("body")
        // Kotlin and JavaScript String.length both count UTF-16 code units.
        if (body.length > BipeRegistries.WORK_NOTE_BODY_MAX_LENGTH) return NoteResult.Hide("body_too_long")
        return NoteResult.Show(Note(roomId, body))
    }

    /** replyByFromEvent for the raw JSON of a notes room event; [sender] and [redacted] as for [noteFromEventJson]. */
    fun replyByFromEventJson(json: String?, sender: String? = null, redacted: Boolean = false, ownUserId: String, seatIds: Set<String>): ReplyByResult {
        val event = parse(json) as? JsonObject ?: return ReplyByResult.Hide("type")
        return replyByFromEvent(string(event["type"]), sender ?: string(event["sender"]), redacted || isRedacted(event), event["content"], ownUserId, seatIds)
    }

    /** replyByFromEvent: an event { sender, type, redacted?, content } of the notes room. [seatIds] from GET /v1/me/business/seats. */
    fun replyByFromEvent(event: JsonObject, ownUserId: String, seatIds: Set<String>): ReplyByResult =
        replyByFromEvent(string(event["type"]), string(event["sender"]), isRedacted(event), event["content"], ownUserId, seatIds)

    private fun replyByFromEvent(type: String?, sender: String?, redacted: Boolean, rawContent: JsonElement?,
                                 ownUserId: String, seatIds: Set<String>): ReplyByResult {
        if (type != BipeRegistries.WORK_REPLY_BY_TYPE) return ReplyByResult.Hide("type")
        if (redacted) return ReplyByResult.Hide("redacted")
        if (sender != ownUserId) return ReplyByResult.Hide("sender")
        val content = rawContent as? JsonObject ?: return ReplyByResult.Hide("content")
        val roomId = string(content["room_id"])?.takeIf { it.startsWith("!") } ?: return ReplyByResult.Hide("room_id")
        val eventId = string(content["event_id"])?.takeIf { it.startsWith("$") } ?: return ReplyByResult.Hide("event_id")
        val seatId = string(content["seat_id"])?.takeIf { it in seatIds } ?: return ReplyByResult.Hide("seat_id")
        return ReplyByResult.Show(ReplyBy(roomId, eventId, seatId))
    }

    private fun isRedacted(event: JsonObject): Boolean {
        val unsigned = event["unsigned"] as? JsonObject
        return unsigned?.get("redacted_because").let { it != null && it !is JsonNull } ||
            (event["redacted"] as? JsonPrimitive)?.content == "true"
    }

    private fun parse(json: String?): JsonElement? = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}

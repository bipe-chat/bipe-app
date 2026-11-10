// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.drafts

import chat.bipe.core.DraftResult
import chat.bipe.core.draftFromEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Assistant drafts (bipe ADR 0210, bipe-chat/bipe#866; registries/drafts.json, bipe-shared docs/drafts-room.md).
 * A draft is an m.text message in the account's private drafts room. The field chat.bipe.draft names the target chat.
 * The rules match draftFromEvent in bipe-shared tools/lib/registries.mjs and test-vectors/drafts.json.
 */
object BipeAssistantDrafts {
    /** One draft that the apps show. */
    data class Draft(
        val roomId: String,
        val body: String,
        val replyTo: String?,
        val threadRoot: String?,
        val client: String,
    )

    /** The result of draftFromEvent: a draft, or the reason the apps do not show the event. */
    sealed interface Result {
        data class Show(val draft: Draft) : Result
        data class Hide(val reason: String) : Result
    }

    /** The drafts room ID from the content of the global account data chat.bipe.drafts_room. Null when absent or malformed. */
    fun roomId(accountDataJson: String?): String? {
        val content = parse(accountDataJson) as? JsonObject ?: return null
        return string(content["room_id"])?.takeIf { it.startsWith("!") && it.none(Char::isWhitespace) }
    }

    /**
     * draftFromEvent for the raw JSON of a drafts room event. [sender] (EventTimelineItem.sender) replaces the JSON
     * sender when given, and [redacted] marks a redacted timeline item.
     */
    fun fromEventJson(json: String?, sender: String? = null, redacted: Boolean = false, ownUserId: String, joinedRoomIds: Set<String>): Result {
        val event = parse(json) as? JsonObject ?: return Result.Hide("type")
        val unsigned = event["unsigned"] as? JsonObject
        val isRedacted = redacted || unsigned?.get("redacted_because") != null || (event["redacted"] as? JsonPrimitive)?.content == "true"
        return fromEvent(string(event["type"]), sender ?: string(event["sender"]), isRedacted, event["content"], ownUserId, joinedRoomIds)
    }

    /** draftFromEvent: an event { sender, type, redacted?, content } of the drafts room. */
    fun fromEvent(event: JsonObject, ownUserId: String, joinedRoomIds: Set<String>): Result =
        fromEvent(string(event["type"]), string(event["sender"]), (event["redacted"] as? JsonPrimitive)?.content == "true",
            event["content"], ownUserId, joinedRoomIds)

    /** bipe-core `draftFromEvent` on the event { type, sender, redacted, content }. */
    private fun fromEvent(type: String?, sender: String?, redacted: Boolean, rawContent: JsonElement?,
                          ownUserId: String, joinedRoomIds: Set<String>): Result {
        val event = buildJsonObject {
            type?.let { put("type", it) }
            sender?.let { put("sender", it) }
            put("redacted", redacted)
            rawContent?.let { put("content", it) }
        }
        return when (val result = draftFromEvent(event.toString(), ownUserId, joinedRoomIds.toList())) {
            is DraftResult.Show -> result.draft.let { Result.Show(Draft(it.roomId, it.body, it.replyTo, it.threadRoot, it.client)) }
            is DraftResult.Hide -> Result.Hide(result.reason)
        }
    }

    private fun parse(json: String?): JsonElement? = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}

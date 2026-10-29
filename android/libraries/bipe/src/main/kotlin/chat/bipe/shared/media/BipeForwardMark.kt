// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.media

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Forward marks (bipe-chat/bipe#83; registries/voice-media.json, test-vectors/forward-marks.json). A forward adds the
 * top-level object FORWARD_KEY with an integer count to the copied content. A missing or malformed mark counts as 0.
 * The sender's app sets the mark, so a modified app can drop or fake it.
 */
object BipeForwardMark {
    /** The largest count the app keeps. A larger count reads as this one. */
    const val MAX_COUNT = 2_147_483_647

    /** The source line of a voice message or audio file (spec voice-message). [code] is the test-vector value. */
    enum class Source(val code: String) {
        FORWARDED("forwarded"),
        FORWARDED_MANY_TIMES("forwardedManyTimes"),
        RECORDED("recorded"),
        FILE("file"),
    }

    /** The forward count of message content: the count of the mark when it is an integer of 1 or more, else 0. */
    fun count(content: JsonObject): Int {
        val mark = content[BipeRegistries.FORWARD_KEY] as? JsonObject ?: return 0
        val number = mark["count"] as? JsonPrimitive ?: return 0
        if (number.isString) return 0
        // longOrNull is null for a fraction ("2.5"), a boolean or a value outside Long.
        val value = number.longOrNull ?: return 0
        return if (value >= 1) value.coerceAtMost(MAX_COUNT.toLong()).toInt() else 0
    }

    /** The forward count from event JSON (the whole event or its content). 0 when the JSON cannot be read. */
    fun count(eventJson: String?): Int = content(eventJson)?.let { count(it) } ?: 0

    /**
     * The content a forward sends: [newContent] (m.new_content of the latest edit) or else [original], without
     * m.relates_to, m.new_content and m.mentions, with the mark set to the original count plus 1.
     */
    fun forwardedContent(original: JsonObject, newContent: JsonObject?): JsonObject {
        val base = (newContent ?: original).filterKeys { it != "m.relates_to" && it != "m.new_content" && it != "m.mentions" }
        val next = count(original).coerceAtMost(MAX_COUNT - 1) + 1
        return JsonObject(base + (BipeRegistries.FORWARD_KEY to buildJsonObject { put("count", next) }))
    }

    /**
     * The content JSON for Room.sendRaw, from EventTimelineItemDebugInfo.originalJson and latestEditJson. null when
     * the original JSON is missing or has no msgtype.
     */
    fun forwardedContentJson(originalJson: String?, latestEditJson: String?): String? {
        val original = content(originalJson)?.takeIf { it.hasMsgType() } ?: return null
        val newContent = (content(latestEditJson)?.get("m.new_content") as? JsonObject)?.takeIf { it.hasMsgType() }
        return forwardedContent(original, newContent).toString()
    }

    /** The source line for a voice message (voice marker) or audio file with this forward count. */
    fun source(isVoice: Boolean, count: Int): Source = when {
        count >= BipeRegistries.FORWARDED_MANY_TIMES -> Source.FORWARDED_MANY_TIMES
        count >= 1 -> Source.FORWARDED
        isVoice -> Source.RECORDED
        else -> Source.FILE
    }

    private fun JsonObject.hasMsgType(): Boolean = (get("msgtype") as? JsonPrimitive)?.isString == true

    /** The content object of event JSON: its "content" when it has one, else the object itself. */
    private fun content(json: String?): JsonObject? {
        val event = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        return event["content"] as? JsonObject ?: event
    }
}

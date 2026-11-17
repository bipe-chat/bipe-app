// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.media

import chat.bipe.core.ForwardLine
import chat.bipe.core.VoiceSource
import chat.bipe.core.forwardChatLimit
import chat.bipe.core.forwardCount
import chat.bipe.core.forwardLine
import chat.bipe.core.voiceSource
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    fun count(content: JsonObject): Int = forwardCount(content.toString()).toInt()

    fun count(eventJson: String?): Int = content(eventJson)?.let { count(it) } ?: 0

    /** bipe-core `forwardedContent`: the original count plus 1, without relations, mentions and the feed mark. */
    fun forwardedContent(original: JsonObject, newContent: JsonObject?): JsonObject =
        chat.bipe.core.forwardedContent(original.toString(), newContent?.toString())
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject } ?: original

    fun forwardedContentJson(originalJson: String?, latestEditJson: String?): String? {
        val original = content(originalJson)?.takeIf { it.hasMsgType() } ?: return null
        val newContent = (content(latestEditJson)?.get("m.new_content") as? JsonObject)?.takeIf { it.hasMsgType() }
        return forwardedContent(original, newContent).toString()
    }

    /**
     * The Bipe fields of forwarded content (keys that start with "chat.bipe.", the forward mark among them) as a JSON
     * object, for Timeline.sendWithExtraContent: the typed message content carries the rest (bipe-chat/bipe#1124).
     * null when the JSON cannot be read or has no forward mark, so that no copy goes out without its mark.
     */
    fun extraContentJson(contentJson: String): String? {
        val content = content(contentJson)?.takeIf { it.containsKey(BipeRegistries.FORWARD_KEY) } ?: return null
        return JsonObject(content.filterKeys { it.startsWith(BIPE_FIELD_PREFIX) }).toString()
    }

    /** The prefix of Bipe's own content fields (registries: FORWARD_KEY, IMAGE_DESCRIPTION_FIELD, ...). */
    private const val BIPE_FIELD_PREFIX = "chat.bipe."

    /** The source line for a voice message (voice marker) or audio file with this forward count. */
    fun source(isVoice: Boolean, count: Int): Source = when (voiceSource(isVoice, count.coerceAtLeast(0).toUInt())) {
        VoiceSource.FORWARDED -> Source.FORWARDED
        VoiceSource.FORWARDED_MANY_TIMES -> Source.FORWARDED_MANY_TIMES
        VoiceSource.RECORDED -> Source.RECORDED
        VoiceSource.FILE -> Source.FILE
    }

    fun line(count: Int): Source? = when (forwardLine(count.coerceAtLeast(0).toUInt())) {
        ForwardLine.FORWARDED -> Source.FORWARDED
        ForwardLine.FORWARDED_MANY_TIMES -> Source.FORWARDED_MANY_TIMES
        null -> null
    }

    fun chatLimit(sentCounts: List<Int>): Int = forwardChatLimit(sentCounts.map { it.coerceAtLeast(0).toUInt() }).toInt()

    private fun JsonObject.hasMsgType(): Boolean = (get("msgtype") as? JsonPrimitive)?.isString == true

    /** The content object of event JSON: its "content" when it has one, else the object itself. */
    private fun content(json: String?): JsonObject? {
        val event = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        return event["content"] as? JsonObject ?: event
    }
}

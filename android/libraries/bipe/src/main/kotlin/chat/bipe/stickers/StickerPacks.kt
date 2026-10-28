// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from bipe-android libraries/bipe stickers/StickerPacks.kt (Bipe code).

package chat.bipe.stickers

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Sticker packs per MSC2545 (bipe/docs/adr/0033-sticker-packs-msc2545.md). Mirrors StickerPacks.swift in BipeKit.

object StickerPackEventType {
    /** Global account data: the user's own pack. */
    const val USER_PACK = "im.ponies.user_emotes"

    /** Room state: packs shared in a room. The state key names the pack. */
    const val ROOM_PACK = "im.ponies.room_emotes"

    /** Global account data: room packs the user enabled everywhere. */
    const val ENABLED_ROOMS = "im.ponies.emote_rooms"
}

@Immutable
data class Sticker(
    val packId: String,
    val shortcode: String,
    /** The `mxc://` URI of the image. */
    val url: String,
    val body: String,
    /** info.is_animated of the image; null when the pack does not say (ADR 0205). */
    val isAnimated: Boolean? = null,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val size: Int?,
) {
    val id: String get() = "$packId/$shortcode"

    /** The JSON content of an `m.sticker` event. [threadRootId] puts the sticker in a thread. */
    fun eventContentJson(threadRootId: String? = null): String = buildJsonObject {
        put("body", body)
        putJsonObject("info") {
            width?.let { put("w", it) }
            height?.let { put("h", it) }
            mimeType?.let { put("mimetype", it) }
            size?.let { put("size", it) }
            isAnimated?.let { put("is_animated", it) }
        }
        if (threadRootId != null) {
            putJsonObject("m.relates_to") {
                put("rel_type", "m.thread")
                put("event_id", threadRootId)
                put("is_falling_back", true)
            }
        }
        put("url", url)
    }.toString()
}

@Immutable
data class StickerPack(
    val source: Source,
    val displayName: String?,
    val stickers: List<Sticker>,
) {
    sealed interface Source {
        data object User : Source
        data class Room(val roomId: String, val stateKey: String) : Source
    }

    val id: String get() = packId(source)

    companion object {
        /**
         * Parses an MSC2545 pack. Keeps images whose usage includes `sticker`, or that state no usage.
         * Returns null when the content is not a pack or holds no stickers.
         */
        fun parse(contentJson: String, source: Source): StickerPack? {
            val content = runCatching { Json.parseToJsonElement(contentJson) as? JsonObject }.getOrNull() ?: return null
            return parse(content, source)
        }

        internal fun parse(content: JsonObject, source: Source): StickerPack? {
            val images = content["images"] as? JsonObject ?: return null
            val pack = content["pack"] as? JsonObject
            val packUsage = pack?.get("usage").stringList()
            val packId = packId(source)

            val stickers = images.keys.sorted().mapNotNull { shortcode ->
                val image = images[shortcode] as? JsonObject ?: return@mapNotNull null
                val url = image["url"].string()?.takeIf { it.startsWith("mxc://") } ?: return@mapNotNull null
                val usage = image["usage"].stringList() ?: packUsage
                if (!usage.isNullOrEmpty() && "sticker" !in usage) return@mapNotNull null
                val info = image["info"] as? JsonObject
                Sticker(
                    packId = packId,
                    shortcode = shortcode,
                    url = url,
                    body = image["body"].string() ?: shortcode,
                    isAnimated = (info?.get("is_animated") as? JsonPrimitive)?.booleanOrNull,
                    width = info?.get("w").int(),
                    height = info?.get("h").int(),
                    mimeType = info?.get("mimetype").string(),
                    size = info?.get("size").int(),
                )
            }
            if (stickers.isEmpty()) return null
            return StickerPack(source = source, displayName = pack?.get("display_name").string(), stickers = stickers)
        }

        /** `im.ponies.emote_rooms` content: `{"rooms": {roomId: {stateKey: {}}}}`. */
        fun parseEnabledRooms(contentJson: String): List<Pair<String, String>> {
            val content = runCatching { Json.parseToJsonElement(contentJson) as? JsonObject }.getOrNull() ?: return emptyList()
            val rooms = content["rooms"] as? JsonObject ?: return emptyList()
            return rooms.keys.sorted().flatMap { roomId ->
                val keys = (rooms[roomId] as? JsonObject)?.keys?.sorted().orEmpty()
                keys.map { roomId to it }
            }
        }

        private fun packId(source: Source): String = when (source) {
            Source.User -> "user"
            is Source.Room -> "${source.roomId}/${source.stateKey}"
        }
    }
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonElement?.stringList(): List<String>? = (this as? JsonArray)?.mapNotNull { it.string() }

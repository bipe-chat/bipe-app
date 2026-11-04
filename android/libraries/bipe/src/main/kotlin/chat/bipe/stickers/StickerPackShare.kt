// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.stickers

import androidx.compose.runtime.Immutable
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLDecoder

/**
 * A pack in a room (ADR 0205, bipe-chat/bipe#842): the target of a pack link and of stickerPackField on a sent
 * sticker. Mirrors StickerPackShare.swift in BipeKit.
 */
@Immutable
data class StickerPackRef(
    val roomId: String,
    /** Empty for a pack link: a pack room holds its pack at state key "". */
    val stateKey: String,
) {
    val id: String get() = "$roomId/$stateKey"

    /** stickerPackLinkBase plus the room ID, URL-encoded (spec sticker-picker sharePack). */
    val link: String
        get() = BipeRegistries.STICKER_PACK_LINK_BASE + roomId.map { char ->
            if (char.isLetterOrDigit() && char.code < 128 || char in "-._~") char.toString()
            else char.toString().toByteArray().joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }
        }.joinToString("")

    companion object {
        /** Room IDs have the form !opaque:server; neither part holds a slash. */
        internal fun isRoomId(value: String): Boolean =
            value.startsWith("!") && ":" in value && "/" !in value && value.none { it.isWhitespace() }

        /** The pack of a pack link (https://bipe.chat/s/<room ID>, also www.), or null when the URL is not one. */
        fun fromLink(url: String): StickerPackRef? {
            val base = runCatching { URI(BipeRegistries.STICKER_PACK_LINK_BASE) }.getOrNull() ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https" || (host != base.host && host != "www.${base.host}")) return null
            val path = uri.rawPath ?: return null
            if (!path.startsWith(base.rawPath)) return null
            val roomId = runCatching { URLDecoder.decode(path.removePrefix(base.rawPath).replace("+", "%2B"), "UTF-8") }.getOrNull()
            return roomId?.takeIf(::isRoomId)?.let { StickerPackRef(it, "") }
        }

        /** stickerPackField of an event: content[stickerPackField] = { room_id, state_key }. Null when absent or malformed. */
        fun read(eventJson: String): StickerPackRef? {
            val event = runCatching { Json.parseToJsonElement(eventJson) as? JsonObject }.getOrNull() ?: return null
            val content = event["content"] as? JsonObject ?: event
            val field = content[BipeRegistries.STICKER_PACK_FIELD] as? JsonObject ?: return null
            val roomId = field["room_id"].string()?.takeIf(::isRoomId) ?: return null
            val stateKey = field["state_key"].string() ?: return null
            return StickerPackRef(roomId, stateKey)
        }
    }
}

/** The room pack; null for the user's own pack. */
val StickerPack.ref: StickerPackRef?
    get() = (source as? StickerPack.Source.Room)?.let { StickerPackRef(it.roomId, it.stateKey) }

/** The room pack this sticker comes from; null for the user's own pack. packId is "user" or "<room ID>/<state key>". */
val Sticker.packRef: StickerPackRef?
    get() {
        val slash = packId.indexOf('/')
        if (slash < 0) return null
        val roomId = packId.substring(0, slash)
        return if (StickerPackRef.isRoomId(roomId)) StickerPackRef(roomId, packId.substring(slash + 1)) else null
    }

/**
 * The m.sticker content to send (spec sticker-picker send): eventContentJson, plus stickerPackField with
 * { room_id, state_key } when the sticker is from a room pack.
 */
fun Sticker.sendContentJson(threadRootId: String? = null): String {
    val json = eventContentJson(threadRootId)
    val ref = packRef ?: return json
    val content = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json
    val field = buildJsonObject {
        put("room_id", ref.roomId)
        put("state_key", ref.stateKey)
    }
    return JsonObject(content + (BipeRegistries.STICKER_PACK_FIELD to field)).toString()
}

/** What sticker-pack shows of a pack room, read from its state. */
@Immutable
data class StickerPackPreview(
    val pack: StickerPack,
    /** The sender of m.room.create (spec sticker-pack notes). */
    val creatorId: String?,
    /** m.room.name, for a pack without a display name. */
    val roomName: String?,
) {
    companion object {
        /** Reads the pack at ref.stateKey, the creator and the room name from GET /rooms/{roomId}/state. */
        fun parse(stateJson: String, ref: StickerPackRef): StickerPackPreview? {
            val events = stateEvents(stateJson) ?: return null
            fun event(type: String, stateKey: String = ""): JsonObject? =
                events.firstOrNull { it["type"].string() == type && it["state_key"].string() == stateKey }
            val content = event(StickerPackEventType.ROOM_PACK, ref.stateKey)?.get("content") as? JsonObject ?: return null
            val pack = StickerPack.parse(content, StickerPack.Source.Room(ref.roomId, ref.stateKey)) ?: return null
            return StickerPackPreview(
                pack = pack,
                creatorId = event("m.room.create")?.get("sender").string(),
                roomName = (event("m.room.name")?.get("content") as? JsonObject)?.get("name").string(),
            )
        }

        /**
         * Whether userId may write the pack state: the room creator, or a power level at least the one the pack event
         * needs (events, else state_default, else 50; users, else users_default, else 0).
         */
        fun canWrite(stateJson: String, userId: String): Boolean {
            val events = stateEvents(stateJson) ?: return false
            if (events.any { it["type"].string() == "m.room.create" && it["sender"].string() == userId }) return true
            val levels = events.firstOrNull { it["type"].string() == "m.room.power_levels" }?.get("content") as? JsonObject ?: return false
            val needed = (levels["events"] as? JsonObject)?.get(StickerPackEventType.ROOM_PACK).int() ?: levels["state_default"].int() ?: 50
            val own = (levels["users"] as? JsonObject)?.get(userId).int() ?: levels["users_default"].int() ?: 0
            return own >= needed
        }

        private fun stateEvents(json: String): List<JsonObject>? =
            (runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull())?.mapNotNull { it as? JsonObject }
    }
}

/**
 * Content of a new pack room (spec sticker-picker notes, Pack room): public join rule, world_readable history,
 * no encryption (ADR 0179), only the creator writes state and messages, the pack copy at state key "".
 */
object StickerPackRoom {
    /** The POST /createRoom body. userPack is the content of im.ponies.user_emotes; the copy gets displayName when the pack has none. */
    fun createBody(userPack: JsonObject, displayName: String): JsonObject {
        val pack = userPack["pack"] as? JsonObject ?: JsonObject(emptyMap())
        val name = pack["display_name"].string()?.takeIf { it.isNotEmpty() } ?: displayName
        val copy = JsonObject(userPack + ("pack" to JsonObject(pack + ("display_name" to JsonPrimitive(name)))))
        return buildJsonObject {
            put("preset", "public_chat")
            put("visibility", "private")
            put("name", name)
            put(
                "initial_state",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "m.room.history_visibility")
                            put("state_key", "")
                            putJsonObject("content") { put("history_visibility", "world_readable") }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", StickerPackEventType.ROOM_PACK)
                            put("state_key", "")
                            put("content", copy)
                        },
                    )
                },
            )
            putJsonObject("power_level_content_override") {
                put("events_default", 100)
                put("state_default", 100)
                put("invite", 100)
            }
        }
    }

    /** im.ponies.emote_rooms content with ref added, keeping every other field. */
    fun enabling(ref: StickerPackRef, content: JsonObject): JsonObject {
        val rooms = content["rooms"] as? JsonObject ?: JsonObject(emptyMap())
        val keys = rooms[ref.roomId] as? JsonObject ?: JsonObject(emptyMap())
        if (keys.containsKey(ref.stateKey)) return JsonObject(content + ("rooms" to rooms))
        val updatedKeys = JsonObject(keys + (ref.stateKey to JsonObject(emptyMap())))
        return JsonObject(content + ("rooms" to JsonObject(rooms + (ref.roomId to updatedKeys))))
    }
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

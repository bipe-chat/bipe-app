// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The pack reads and writes of bipe-android libraries/bipe stickers/StickerPackRepository.kt (Bipe code),
// over HttpURLConnection instead of the fork's OkHttp client and session store.

package chat.bipe.stickers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads and writes MSC2545 sticker packs over the Matrix client-server API (ADR 0033): the SDK cannot
 * read custom room state under sliding sync. Requests carry the session's access token; nothing logs them.
 */
class StickerPackClient(
    private val homeserverUrl: String,
    private val userId: String,
    /** The current access token; read again after an HTTP 401. */
    private val accessToken: () -> String?,
) {
    /** The user's own pack, the packs of roomId, then the room packs the user enabled everywhere. */
    suspend fun packs(roomId: String): List<StickerPack> = withTokenRetry { token ->
        val packs = mutableListOf<StickerPack>()
        get(listOf("user", userId, "account_data", StickerPackEventType.USER_PACK), token)
            ?.let { StickerPack.parse(it, StickerPack.Source.User) }
            ?.let { packs += it }
        packs += roomPacks(roomId, token)
        get(listOf("user", userId, "account_data", StickerPackEventType.ENABLED_ROOMS), token)?.let { content ->
            for ((enabledRoomId, stateKey) in StickerPack.parseEnabledRooms(content)) {
                if (enabledRoomId == roomId) continue
                // A pack in a room the user left is unreadable. Skip it, keep the others.
                val pack = runCatching {
                    get(listOf("rooms", enabledRoomId, "state", StickerPackEventType.ROOM_PACK, stateKey), token)
                }.getOrNull()?.let { StickerPack.parse(it, StickerPack.Source.Room(enabledRoomId, stateKey)) }
                if (pack != null) packs += pack
            }
        }
        packs
    }

    /**
     * Uploads the candidates that are not yet in the user's pack and writes the pack back, keeping every
     * other field. Returns how many were added. A failed upload keeps what was added and then throws.
     */
    suspend fun addToUserPack(candidates: List<StickerImportCandidate>): Int = withTokenRetry { token ->
        val path = listOf("user", userId, "account_data", StickerPackEventType.USER_PACK)
        val content = get(path, token)
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val images = (content["images"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        var added = 0
        var uploadError: IOException? = null
        for (candidate in candidates.distinctBy { it.shortcode }.filterNot { images.containsKey(it.shortcode) }) {
            try {
                val extension = if (candidate.mimeType == "image/png") "png" else "webp"
                val url = upload(candidate.bytes, candidate.mimeType, "${candidate.shortcode}.$extension", token)
                images[candidate.shortcode] = buildJsonObject {
                    put("url", url)
                    put("body", "Sticker")
                    putJsonArray("usage") { add("sticker") }
                    putJsonObject("info") {
                        put("w", candidate.width)
                        put("h", candidate.height)
                        put("mimetype", candidate.mimeType)
                        put("size", candidate.bytes.size)
                        if (candidate.isAnimated) put("is_animated", true)
                    }
                }
                added++
            } catch (e: IOException) {
                uploadError = e
                break
            }
        }
        if (added > 0) {
            put(path, JsonObject(content + ("images" to JsonObject(images))).toString(), token)
        }
        uploadError?.let { throw it }
        added
    }

    /**
     * Adds a sticker someone sent to the user's pack by its mxc:// URL, with no upload (bipe-chat/bipe#670).
     * Returns false when the pack already has that image.
     */
    suspend fun saveToUserPack(url: String, width: Int?, height: Int?, mimeType: String?, size: Long?): Boolean = withTokenRetry { token ->
        val path = listOf("user", userId, "account_data", StickerPackEventType.USER_PACK)
        val content = get(path, token)
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val images = (content["images"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val known = images.values.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.content == url }
        if (known) return@withTokenRetry false
        images[StickerImporter.shortcode(url.toByteArray())] = buildJsonObject {
            put("url", url)
            put("body", "Sticker")
            putJsonArray("usage") { add("sticker") }
            putJsonObject("info") {
                width?.let { put("w", it) }
                height?.let { put("h", it) }
                mimeType?.let { put("mimetype", it) }
                size?.let { put("size", it) }
            }
        }
        put(path, JsonObject(content + ("images" to JsonObject(images))).toString(), token)
        true
    }

    /** A sticker list in global account data ([StickerShortlist]): recents or favourites. Empty when unset. */
    suspend fun shortlist(eventType: String): List<StickerRef> = withTokenRetry { token ->
        get(listOf("user", userId, "account_data", eventType), token)?.let(StickerShortlist::parse).orEmpty()
    }

    /** Writes the whole list, so a later write also carries changes from a failed one. */
    suspend fun setShortlist(eventType: String, refs: List<StickerRef>) = withTokenRetry { token ->
        put(listOf("user", userId, "account_data", eventType), StickerShortlist.content(refs), token)
    }

    /**
     * Spec sticker-pack appear: the pack at ref, the room creator and the room name, from the room state. A public
     * world_readable room is read without joining. Null when the room has no pack at ref.stateKey.
     */
    suspend fun preview(ref: StickerPackRef): StickerPackPreview? = withTokenRetry { token ->
        get(listOf("rooms", ref.roomId, "state"), token)?.let { StickerPackPreview.parse(it, ref) }
    }

    /** Whether ref is in im.ponies.emote_rooms (spec sticker-pack, phase added). */
    suspend fun isEnabled(ref: StickerPackRef): Boolean = withTokenRetry { token ->
        val content = get(listOf("user", userId, "account_data", StickerPackEventType.ENABLED_ROOMS), token) ?: return@withTokenRetry false
        StickerPack.parseEnabledRooms(content).any { it.first == ref.roomId && it.second == ref.stateKey }
    }

    /** Spec sticker-pack add: puts ref in im.ponies.emote_rooms with a read-merge-write on the server copy. The caller joins first. */
    suspend fun enable(ref: StickerPackRef) = withTokenRetry { token ->
        val path = listOf("user", userId, "account_data", StickerPackEventType.ENABLED_ROOMS)
        val content = get(path, token)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        put(path, StickerPackRoom.enabling(ref, content).toString(), token)
    }

    /** StickerPackView.canShare of a room pack: this user may write the pack state. False when the state is unreadable. */
    suspend fun canWritePack(roomId: String): Boolean = runCatching {
        withTokenRetry { token -> get(listOf("rooms", roomId, "state"), token) }
    }.getOrNull()?.let { StickerPackPreview.canWrite(it, userId) } ?: false

    /**
     * Spec sticker-picker sharePack for the user's own pack: a new pack room with a copy of the pack ([StickerPackRoom]).
     * Later edits of the user's pack do not change the copy.
     */
    suspend fun createPackRoom(displayName: String): StickerPackRef = withTokenRetry { token ->
        val pack = get(listOf("user", userId, "account_data", StickerPackEventType.USER_PACK), token)
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: throw IOException("No sticker pack")
        val body = StickerPackRoom.createBody(pack, displayName).toString().toByteArray()
        val response = perform("POST", clientUrl(listOf("createRoom")), token, body, "application/json") ?: throw IOException("createRoom returned 404")
        val roomId = ((Json.parseToJsonElement(response) as? JsonObject)?.get("room_id") as? JsonPrimitive)?.content
            ?: throw IOException("createRoom returned no room_id")
        StickerPackRef(roomId, "")
    }

    private suspend fun roomPacks(roomId: String, token: String): List<StickerPack> {
        val body = get(listOf("rooms", roomId, "state"), token) ?: return emptyList()
        val events = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return events.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            if ((event["type"] as? JsonPrimitive)?.content != StickerPackEventType.ROOM_PACK) return@mapNotNull null
            val stateKey = (event["state_key"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val content = event["content"] as? JsonObject ?: return@mapNotNull null
            StickerPack.parse(content, StickerPack.Source.Room(roomId, stateKey))
        }.sortedBy { it.id }
    }

    private suspend fun <T> withTokenRetry(request: suspend (String) -> T): T {
        val token = accessToken() ?: throw UnauthorizedException()
        return try {
            request(token)
        } catch (e: UnauthorizedException) {
            request(accessToken() ?: throw e)
        }
    }

    private suspend fun get(path: List<String>, token: String): String? = perform("GET", clientUrl(path), token, null, null)

    private suspend fun put(path: List<String>, json: String, token: String) {
        perform("PUT", clientUrl(path), token, json.toByteArray(), "application/json")
    }

    /** `POST /_matrix/media/v3/upload`. Returns the `mxc://` URI. Stickers are stored in clear (spec sticker-import notes). */
    private suspend fun upload(bytes: ByteArray, mimeType: String, fileName: String, token: String): String {
        val url = "${base()}/_matrix/media/v3/upload?filename=${URLEncoder.encode(fileName, "UTF-8")}"
        val body = perform("POST", url, token, bytes, mimeType) ?: throw IOException("Upload returned 404")
        val contentUri = (Json.parseToJsonElement(body) as? JsonObject)?.get("content_uri") as? JsonPrimitive
        return contentUri?.content?.takeIf { it.startsWith("mxc://") } ?: throw IOException("Upload returned no content_uri")
    }

    private fun base(): String = homeserverUrl.trimEnd('/')

    private fun clientUrl(path: List<String>): String =
        base() + "/_matrix/client/v3/" + path.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** Returns the body on 200, null on 404. */
    private suspend fun perform(method: String, url: String, token: String, body: ByteArray?, contentType: String?): String? =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                if (body != null) {
                    connection.doOutput = true
                    contentType?.let { connection.setRequestProperty("Content-Type", it) }
                    connection.outputStream.use { it.write(body) }
                }
                when (connection.responseCode) {
                    200 -> connection.inputStream.use { it.readBytes().decodeToString() }
                    404 -> null
                    401 -> throw UnauthorizedException()
                    else -> throw IOException("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
}

private class UnauthorizedException : IOException("HTTP 401")

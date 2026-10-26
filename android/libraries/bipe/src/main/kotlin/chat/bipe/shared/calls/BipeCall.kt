// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.calls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/** An OpenID token of `Client.requestOpenidToken()`, for the SFU token request (MSC4195). */
data class BipeCallOpenId(val accessToken: String, val tokenType: String, val matrixServerName: String, val expiresInSeconds: Long)

/** One media key of one call member, for the LiveKit per-participant key provider. */
class BipeCallKey(val identity: String, val index: Int, val key: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is BipeCallKey && other.identity == identity && other.index == index && other.key.contentEquals(key)

    override fun hashCode(): Int = (identity.hashCode() * 31 + index) * 31 + key.contentHashCode()

    override fun toString(): String = "BipeCallKey($identity, $index)"
}

/** One active member device of a room call (MSC4143 membership). */
data class BipeCallMember(val userId: String, val deviceId: String, val createdTs: Long, val focusUrl: String?) {
    val identity: String get() = BipeCall.livekitIdentity(userId, deviceId)
}

/**
 * MatrixRTC formats of spec call (bipe-chat/bipe#758, ADR 0186). The vectors are bipe-shared
 * test-vectors/calls/matrixrtc.json; iOS has the same rules in BipeKit BipeCall.swift.
 */
object BipeCall {
    const val MEMBER_EVENT_TYPE = "org.matrix.msc3401.call.member"
    const val KEY_EVENT_TYPE = "io.element.call.encryption_keys"
    const val FOCI_KEY = "org.matrix.msc4143.rtc_foci"

    /** LiveKit key provider settings. Element Web uses the same key ring. */
    const val KEY_RING_SIZE = 16
    const val RATCHET_WINDOW_SIZE = 10

    /** The own key index. The LiveKit SDKs give the app no way to change the index of a published track. */
    const val OWN_KEY_INDEX = 0

    /** The delayed leave (MSC4140): sent before the join, restarted while in the call. */
    const val LEAVE_DELAY_MS = 20_000L
    const val LEAVE_RESTART_MS = 5_000L

    /** A new own key is used this long after it was sent, so the others have it first. */
    const val KEY_USE_DELAY_MS = 1_000L

    /** MSC4143: a membership without `expires` lasts 4 hours. */
    const val DEFAULT_EXPIRES_MS = 14_400_000L
    const val KEY_LENGTH = 16

    fun stateKey(userId: String, deviceId: String): String = "_${userId}_$deviceId"

    fun livekitIdentity(userId: String, deviceId: String): String = "$userId:$deviceId"

    fun membershipContent(deviceId: String, roomId: String, focusUrl: String, createdTs: Long, video: Boolean): JsonObject =
        buildJsonObject {
            put("application", "m.call")
            put("call_id", "")
            put("scope", "m.room")
            put("device_id", deviceId)
            put("focus_active", buildJsonObject {
                put("type", "livekit")
                put("focus_selection", "oldest_membership")
            })
            put("foci_preferred", buildJsonArray {
                add(buildJsonObject {
                    put("type", "livekit")
                    put("livekit_service_url", focusUrl)
                    put("livekit_alias", roomId)
                })
            })
            put("created_ts", createdTs)
            put("m.call.intent", if (video) "video" else "audio")
        }

    fun sfuRequestBody(roomId: String, deviceId: String, openId: BipeCallOpenId): JsonObject = buildJsonObject {
        put("room", roomId)
        put("openid_token", buildJsonObject {
            put("access_token", openId.accessToken)
            put("token_type", openId.tokenType)
            put("matrix_server_name", openId.matrixServerName)
            put("expires_in", openId.expiresInSeconds)
        })
        put("device_id", deviceId)
    }

    /** The `url` and `jwt` of a `/sfu/get` answer. */
    fun sfuAnswer(json: JsonObject): Pair<String, String>? {
        val url = json.string("url")?.takeIf { it.isNotEmpty() } ?: return null
        val jwt = json.string("jwt")?.takeIf { it.isNotEmpty() } ?: return null
        return url to jwt
    }

    fun keyEventContent(index: Int, key: ByteArray, deviceId: String, sentTs: Long): JsonObject = buildJsonObject {
        put("keys", buildJsonArray {
            add(buildJsonObject {
                put("index", index)
                put("key", base64Unpadded(key))
            })
        })
        put("device_id", deviceId)
        put("call_id", "")
        put("sent_ts", sentTs)
    }

    /** The keys of a room event or of a to-device message of type [KEY_EVENT_TYPE]. */
    fun readKeys(sender: String, content: JsonObject): List<BipeCallKey> {
        val raw = content["keys"]
        val deviceId: String?
        val items: List<JsonObject>
        when (raw) {
            is JsonArray -> {
                if ((content.string("call_id") ?: "") != "") return emptyList()
                deviceId = content.string("device_id")
                items = raw.filterIsInstance<JsonObject>()
            }
            is JsonObject -> {
                val session = content["session"] as? JsonObject
                if ((session?.string("call_id") ?: "") != "") return emptyList()
                deviceId = (content["member"] as? JsonObject)?.string("claimed_device_id")
                items = listOf(raw)
            }
            else -> return emptyList()
        }
        if (deviceId.isNullOrEmpty()) return emptyList()
        val identity = livekitIdentity(sender, deviceId)
        return items.mapNotNull { item ->
            val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (index !in 0 until KEY_RING_SIZE) return@mapNotNull null
            val key = item.string("key")?.let(::decodeBase64) ?: return@mapNotNull null
            if (key.size != KEY_LENGTH) return@mapNotNull null
            BipeCallKey(identity, index, key)
        }
    }

    /** The first LiveKit focus of the well-known, https only. */
    fun focusUrl(wellKnown: JsonObject): String? =
        (wellKnown[FOCI_KEY] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::livekitUrl) }

    /** The member of one [MEMBER_EVENT_TYPE] state event, or null when the event is no active membership. */
    fun member(sender: String, stateKey: String, originServerTs: Long, content: JsonObject, now: Long): BipeCallMember? {
        if (content.string("application") != "m.call" || (content.string("call_id") ?: "") != "") return null
        val deviceId = content.string("device_id")?.takeIf { it.isNotEmpty() } ?: return null
        if (stateKey != stateKey(sender, deviceId) && stateKey != "${sender}_$deviceId") return null
        val created = (content["created_ts"] as? JsonPrimitive)?.longOrNull ?: originServerTs
        val expires = (content["expires"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_MS
        if (created + expires <= now) return null
        val focus = (content["foci_preferred"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::livekitUrl) }
        return BipeCallMember(sender, deviceId, created, focus)
    }

    /** focus_selection oldest_membership: the focus of the oldest member, else the own one. */
    fun oldestFocus(members: List<BipeCallMember>, own: String): String =
        members.filter { it.focusUrl != null }.minByOrNull { it.createdTs }?.focusUrl ?: own

    fun base64Unpadded(data: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(data)

    fun decodeBase64(text: String): ByteArray? {
        val standard = text.replace('-', '+').replace('_', '/').trimEnd('=')
        return runCatching { Base64.getDecoder().decode(standard.padEnd((standard.length + 3) / 4 * 4, '=')) }.getOrNull()
    }

    private fun livekitUrl(focus: JsonObject): String? {
        if (focus.string("type") != "livekit") return null
        return focus.string("livekit_service_url")?.takeIf { it.startsWith("https://") }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.calls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** What one ring event means for this device (spec incoming-call). */
data class BipeCallRingRead(val ring: Boolean, val notify: Boolean, val video: Boolean, val expiresAt: Long)

/** The timeline call line (spec incoming-call notes, timeline-rows). */
data class BipeCallLine(val outcome: Outcome, val minutes: Int?, val joined: List<String>) {
    enum class Outcome { ONGOING, MISSED, DECLINED, ENDED }
}

/**
 * MSC4075 ringing (bipe-chat/bipe#759, ADR 0186). Vectors: bipe-shared test-vectors/calls/ringing.json;
 * iOS has the same rules in BipeKit BipeCallRing.swift.
 */
object BipeCallRing {
    const val EVENT_TYPE = "org.matrix.msc4075.rtc.notification"
    const val DECLINE_EVENT_TYPE = "org.matrix.msc4310.rtc.decline"
    const val LIFETIME_MS = 30_000L

    /** The longest ring a device accepts, whatever the sender says. */
    const val MAX_LIFETIME_MS = 120_000L

    /** A sender clock further off than this from the server time is not used. */
    const val MAX_CLOCK_SKEW_MS = 20_000L

    fun content(notificationType: String, senderTs: Long, membershipEventId: String, userIds: List<String>, video: Boolean): JsonObject =
        buildJsonObject {
            put("notification_type", notificationType)
            put("sender_ts", senderTs)
            put("lifetime", LIFETIME_MS)
            put("m.mentions", buildJsonObject {
                if (userIds.isEmpty()) put("room", true) else put("user_ids", buildJsonArray { userIds.forEach { add(JsonPrimitive(it)) } })
            })
            put("m.relates_to", buildJsonObject {
                put("rel_type", "m.reference")
                put("event_id", membershipEventId)
            })
            put("m.call.intent", if (video) "video" else "audio")
        }

    fun read(content: JsonObject, sender: String, originServerTs: Long, ownUserId: String, now: Long): BipeCallRingRead {
        val senderTs = (content["sender_ts"] as? JsonPrimitive)?.longOrNull ?: originServerTs
        val base = if (abs(senderTs - originServerTs) <= MAX_CLOCK_SKEW_MS) senderTs else originServerTs
        val lifetime = ((content["lifetime"] as? JsonPrimitive)?.longOrNull ?: LIFETIME_MS).coerceIn(0, MAX_LIFETIME_MS)
        val expiresAt = base + lifetime
        val video = (content["m.call.intent"] as? JsonPrimitive)?.contentOrNull == "video"
        val mentions = content["m.mentions"] as? JsonObject
        val users = (mentions?.get("user_ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val addressed = ownUserId in users || (mentions?.get("room") as? JsonPrimitive)?.booleanOrNull == true
        val live = now < expiresAt && sender != ownUserId && addressed
        val type = (content["notification_type"] as? JsonPrimitive)?.contentOrNull
        return BipeCallRingRead(live && type == "ring", live && type == "notification", video, expiresAt)
    }

    /** [memberEvents]: (user, time) of each org.matrix.msc3401.call.member state event of the room. */
    fun line(isDm: Boolean, sender: String, ringTs: Long, active: Boolean, declinedBy: List<String>, memberEvents: List<Pair<String, Long>>): BipeCallLine {
        val after = memberEvents.filter { it.second >= ringTs }
        val joined = after.map { it.first }.filter { it != sender }.distinct()
        val groupJoined = if (isDm) emptyList() else joined
        if (active) return BipeCallLine(BipeCallLine.Outcome.ONGOING, null, groupJoined)
        if (joined.isEmpty()) {
            val outcome = if (isDm && declinedBy.isNotEmpty()) BipeCallLine.Outcome.DECLINED else BipeCallLine.Outcome.MISSED
            return BipeCallLine(outcome, null, emptyList())
        }
        val last = after.maxOf { it.second }
        val minutes = maxOf(1, ((last - ringTs + 30_000) / 60_000).toInt())
        return BipeCallLine(BipeCallLine.Outcome.ENDED, minutes, groupJoined)
    }
}

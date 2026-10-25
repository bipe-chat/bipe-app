// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Contract `WorkHours`: the opening hours of a work account (ADR 0138 rule 4, bipe-chat/bipe#511; spec work-hours).
 * Outside them the phone shows that account's pushes with no sound and no heads-up.
 */
@Serializable
data class BipeWorkHours(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("days") val days: List<Day> = emptyList(),
) {
    /** Contract `WorkRange`: local HH:MM, half-open; end 24:00 is midnight. */
    @Serializable
    data class Range(@SerialName("start") val start: String, @SerialName("end") val end: String)

    /** Contract `WorkDay`: ISO weekday, 1 Monday to 7 Sunday. */
    @Serializable
    data class Day(@SerialName("day") val day: Int, @SerialName("ranges") val ranges: List<Range> = emptyList())

    /** Same rule as isWithinWorkHours() in bipe-shared tools/lib/registries.mjs (test-vectors/work-hours.json). */
    fun isOpen(at: Instant): Boolean {
        val zone = runCatching { ZoneId.of(timeZone) }.getOrElse { ZoneId.of(BipeRegistries.WORK_HOURS_DEFAULT_TIME_ZONE) }
        val local = at.atZone(zone)
        val minute = local.hour * 60 + local.minute
        val day = days.firstOrNull { it.day == local.dayOfWeek.value } ?: return false
        return day.ranges.any { range ->
            val start = minutes(range.start)
            val end = minutes(range.end)
            start != null && end != null && minute >= start && minute < end
        }
    }

    /** True when the hours are on and [at] is outside them: the account's pushes arrive quietly. */
    fun isQuiet(at: Instant): Boolean = enabled && !isOpen(at)

    fun encoded(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The registry defaults (registries/work-hours.json), off, in the phone's time zone. */
        fun defaults(): BipeWorkHours = BipeWorkHours(
            enabled = false,
            timeZone = TimeZone.getDefault().id,
            days = BipeRegistries.workHoursDefaultDays.map { (day, ranges) -> Day(day, ranges.map { (start, end) -> Range(start, end) }) },
        )

        fun decoded(text: String): BipeWorkHours? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun minutes(hhmm: String): Int? {
            val parts = hhmm.split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..24 || minute !in 0..59 || (hour == 24 && minute != 0)) return null
            return hour * 60 + minute
        }
    }
}

/** `GET /v1/me/work-hours`. Company accounts only (404 otherwise). */
suspend fun BipeApiClient.workHours(accessToken: String): BipeWorkHours =
    send("GET", listOf("v1", "me", "work-hours"), accessToken = accessToken, response = BipeWorkHours.serializer())

/** `PUT /v1/me/work-hours`. */
suspend fun BipeApiClient.setWorkHours(hours: BipeWorkHours, accessToken: String): BipeWorkHours = send(
    "PUT",
    listOf("v1", "me", "work-hours"),
    body = encode(BipeWorkHours.serializer(), hours),
    accessToken = accessToken,
    response = BipeWorkHours.serializer(),
)

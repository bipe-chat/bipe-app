// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.matrix.rustcomponents.sdk.Client

sealed interface DayJump {
    data object Empty : DayJump
    data class Loaded(val rowId: String) : DayJump
    data class History(val eventId: String, val day: LocalDate, val zone: ZoneId) : DayJump
}

internal data class DayWindow(val date: LocalDate, val zone: ZoneId) {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun target(eventId: String, timestamp: Long, rows: List<MessageRow>, reachedStart: Boolean): DayJump {
        if (timestamp !in start until end) return DayJump.Empty
        val first = rows.firstOrNull { (it.dayBefore ?: it.timestamp) in start until end }
        val oldest = rows.firstOrNull()?.let { it.dayBefore ?: it.timestamp }
        return if (first != null && (reachedStart || (oldest != null && oldest < start) || first.originalEventId == eventId)) {
            DayJump.Loaded(first.id)
        } else DayJump.History(eventId, date, zone)
    }
}

internal data class TimestampEvent(val eventId: String, val timestamp: Long)

private val dateSearchHttp by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
}

/** MSC3030 is not exposed by the pinned SDK. Use the active SDK session and keep its token on its homeserver. */
internal suspend fun timestampToEvent(client: Client, roomId: String, start: Long): TimestampEvent? {
    val session = client.session()
    val room = URLEncoder.encode(roomId, Charsets.UTF_8)
    val base = session.homeserverUrl.trimEnd('/')
    val url = URI("$base/_matrix/client/v1/rooms/$room/timestamp_to_event?ts=${start.coerceAtLeast(0)}&dir=f")
    val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer ${session.accessToken}").GET().build()
    val response = dateSearchHttp.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    return when (response.statusCode()) {
        200 -> {
            val json = Json.parseToJsonElement(response.body()).jsonObject
            TimestampEvent(json.getValue("event_id").jsonPrimitive.content, json.getValue("origin_server_ts").jsonPrimitive.long)
        }
        404 -> null
        else -> error("timestamp_to_event: HTTP ${response.statusCode()}")
    }
}

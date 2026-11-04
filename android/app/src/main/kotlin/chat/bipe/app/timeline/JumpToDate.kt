// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.LocalReduceMotion
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.shared.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client

/** Spec chat-timeline dayFocus (bipe-chat/bipe#828): the event the day view opens on, and the start of the picked day. */
data class DayFocus(val eventId: String, val dayStart: Long)

/** The days of timeline rows, for the floating date and jumpToDate of spec chat-timeline. Rows are oldest first. */
object TimelineDays {
    /** The date a row carries: the date separator's day, or the message's time. */
    fun date(row: TimelineRow): Long? = row.day ?: row.message?.sentAt

    /** The day of the row at index: its own date, else the nearest date above it, else below it. */
    fun day(index: Int, rows: List<TimelineRow>): Long? {
        if (index !in rows.indices) return null
        for (i in index downTo 0) date(rows[i])?.let { return it }
        for (i in index until rows.size) date(rows[i])?.let { return it }
        return null
    }

    /** The start of the local day of a time. */
    fun startOfDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** The first row on or after the start of a day. */
    fun firstRow(dayStart: Long, rows: List<TimelineRow>): TimelineRow? =
        rows.firstOrNull { row -> date(row)?.let { startOfDay(it) >= dayStart } == true }

    /** true when the loaded rows reach back before the start of the day, so the day is on screen already. */
    fun reaches(dayStart: Long, rows: List<TimelineRow>, reachedStart: Boolean): Boolean {
        if (reachedStart) return true
        val oldest = rows.firstNotNullOfOrNull { date(it) } ?: return false
        return oldest < dayStart
    }

    /** A day of the Material date picker (UTC midnight) as the start of that day on the phone. */
    fun localDayStart(pickerUtcMillis: Long): Long {
        val day: LocalDate = Instant.ofEpochMilli(pickerUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/** The first event at or after a time: its event ID and origin server time. */
data class TimestampEvent(val eventId: String, val sentAt: Long)

/**
 * Matrix client-server GET /_matrix/client/v1/rooms/{roomId}/timestamp_to_event (MSC3030), dir f: the first event at
 * or after [millis], or null when the room has none. matrix-sdk-ffi has no call for it, so the app asks the
 * homeserver with the session's token. Throws on any other failure.
 */
suspend fun timestampToEvent(client: Client, roomId: String, millis: Long): TimestampEvent? = withContext(Dispatchers.IO) {
    val auth = client.session()
    val room = URLEncoder.encode(roomId, "UTF-8")
    val url = URL("${auth.homeserverUrl.trimEnd('/')}/_matrix/client/v1/rooms/$room/timestamp_to_event?ts=${maxOf(0L, millis)}&dir=f")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        when (connection.responseCode) {
            200 -> {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                TimestampEvent(json.getString("event_id"), json.getLong("origin_server_ts"))
            }
            404 -> null
            else -> error("timestamp_to_event: HTTP ${connection.responseCode}")
        }
    } finally {
        connection.disconnect()
    }
}

/** Spec chat-timeline floatingDate: the day of the top row while the list scrolls. A button in the main timeline. */
@Composable
fun FloatingDatePill(day: Long, onPick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    val pickLabel = stringResource(R.string.bipe_jump_to_date_title)
    val tap = if (onPick != null) {
        Modifier.bipeTapTarget().clip(CircleShape).clickable(onClickLabel = pickLabel, onClick = onPick)
    } else {
        Modifier
    }
    Box(modifier = modifier.then(tap), contentAlignment = Alignment.Center) {
        Text(
            daySeparatorText(day),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colors.inkPrimary,
            modifier = Modifier
                .background(colors.surfaceRaised, CircleShape)
                .border(1.dp, colors.lineSubtle, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Spec chat-timeline floatingDate: the pill fades in and out; with Reduce Motion (animator scale 0) it shows and
 * hides with no fade. [day] is the last day shown, so the pill keeps its text while it fades out.
 */
@Composable
fun FloatingDateOverlay(visible: Boolean, day: Long?, onPick: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) {
        if (visible && day != null) FloatingDatePill(day, onPick, modifier)
        return
    }
    AnimatedVisibility(visible = visible && day != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        day?.let { FloatingDatePill(it, onPick) }
    }
}

/** Spec chat-timeline jumpToDate: a calendar up to today. Picking a day jumps at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpToDateDialog(onPick: (Long) -> Unit, onCancel: () -> Unit) {
    val colors = BipeTheme.colors
    val state = rememberDatePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
        },
    )
    LaunchedEffect(state.selectedDateMillis) {
        state.selectedDateMillis?.let { onPick(TimelineDays.localDayStart(it)) }
    }
    DatePickerDialog(
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_app_cancel)) } },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    stringResource(R.string.bipe_jump_to_date_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

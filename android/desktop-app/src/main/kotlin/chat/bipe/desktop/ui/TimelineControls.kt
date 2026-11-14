// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.TimelineDates
import chat.bipe.desktop.chat.TimelineScrollPolicy
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.delay

/** Lives in one account's workspace, keyed by room, so switching rooms retains the reading position. */
internal class RoomViewport {
    val list = LazyListState()
    val policy = TimelineScrollPolicy()
}

@Composable internal fun timelineToday(zone: ZoneId): LocalDate {
    val today by produceState(LocalDate.now(zone), zone) {
        while (true) {
            val now = ZonedDateTime.now(zone)
            value = now.toLocalDate()
            delay(Duration.between(now, value.plusDays(1).atStartOfDay(zone)).toMillis().coerceAtLeast(1))
        }
    }
    return today
}

@Composable internal fun TimelineDay(timestamp: Long, today: LocalDate, zone: ZoneId, s: DesktopCatalog, colors: DesktopColors) {
    val label = TimelineDates.label(timestamp, today, zone, Locale.forLanguageTag(s.locale),
        s.text("bipe_app_timeline_today"), s.text("bipe_app_timeline_yesterday"))
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = colors["bubble.own"], contentColor = colors["accent.ink"],
            modifier = Modifier.semantics { heading() }) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable internal fun JumpToNewest(s: DesktopCatalog, colors: DesktopColors, modifier: Modifier = Modifier, jump: () -> Unit) {
    IconButton(onClick = jump, modifier = modifier.size(48.dp).clip(CircleShape)
        .background(colors["surface.raised"]).border(1.dp, colors["line.subtle"], CircleShape)) {
        Icon(sharedIcon("caret-right"), contentDescription = s.text("bipe_a11y_scroll_to_newest"), tint = colors["accent.ink"], modifier = Modifier.rotate(90f))
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.DayHistory
import chat.bipe.desktop.chat.DayJump
import chat.bipe.desktop.chat.DesktopTimeline
import chat.bipe.desktop.chat.LoadPhase
import chat.bipe.desktop.chat.TimelineDates
import chat.bipe.desktop.safety.MessageSafety
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun JumpDatePicker(today: LocalDate, s: DesktopCatalog, pick: (LocalDate) -> Unit, close: () -> Unit) {
    val dates = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = pickerDay(utcTimeMillis) <= today
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    }
    val state = rememberDatePickerState(
        initialDisplayedMonthMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = dates,
    )
    LaunchedEffect(state.selectedDateMillis) {
        state.selectedDateMillis?.let { pick(pickerDay(it)) }
    }
    DatePickerDialog(onDismissRequest = close, confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(s.text("bipe_app_cancel")) } }) {
        DatePicker(state, title = {
            Text(s.text("bipe_jump_to_date_title"), Modifier.padding(start = 24.dp, top = 16.dp),
                style = MaterialTheme.typography.titleMedium)
        })
    }
}

private fun pickerDay(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDate()

@Composable internal fun DayHistoryDialog(model: DesktopTimeline, focus: DayJump.History, s: DesktopCatalog,
                                          colors: DesktopColors, close: () -> Unit) {
    var attempt by remember(model, focus) { mutableStateOf(0) }
    var phase by remember(model, focus, attempt) { mutableStateOf<LoadPhase<DayHistory>>(LoadPhase.Loading) }
    LaunchedEffect(model, focus, attempt) {
        val history = try { model.openDayHistory(focus) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            phase = LoadPhase.Failed
            return@LaunchedEffect
        }
        try {
            phase = LoadPhase.Ready(history)
            awaitCancellation()
        } finally { history.close() }
    }
    val locale = Locale.forLanguageTag(s.locale)
    val title = focus.day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = {
            when (val current = phase) {
                LoadPhase.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                LoadPhase.Failed -> Text(s.text("bipe_error_generic"))
                is LoadPhase.Ready -> DayHistoryRows(current.value, focus, s, colors, retry = { attempt++ })
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(s.text("bipe_app_close")) } },
        dismissButton = {
            if (phase == LoadPhase.Failed) TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
        },
    )
}

@Composable private fun DayHistoryRows(history: DayHistory, focus: DayJump.History, s: DesktopCatalog,
                                       colors: DesktopColors, retry: () -> Unit) {
    val load by history.rows.collectAsState()
    val pages by history.pages.collectAsState()
    val today = timelineToday(focus.zone)
    val list = rememberLazyListState()
    var positioned by remember(history) { mutableStateOf(false) }
    val messages = (load as? LoadPhase.Ready)?.value.orEmpty()
    val names = remember(messages) { messages.mapNotNull { row -> row.senderId?.let { it to row.sender } }.toMap() }
    LaunchedEffect(messages) {
        if (positioned || messages.isEmpty()) return@LaunchedEffect
        val index = messages.indexOfFirst {
            Instant.ofEpochMilli(it.dayBefore ?: it.timestamp).atZone(focus.zone).toLocalDate() == focus.day
        }.takeIf { it >= 0 } ?: messages.indexOfFirst { it.originalEventId == focus.eventId }
        if (index >= 0) {
            list.scrollToItem(index + if (pages.earlier) 1 else 0)
            positioned = true
        }
    }
    when (load) {
        LoadPhase.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
        LoadPhase.Failed -> Column {
            Text(s.text("bipe_error_generic"))
            TextButton(onClick = retry) { Text(s.text("bipe_app_retry")) }
        }
        is LoadPhase.Ready -> Column {
            if (pages.status == DayHistory.Paging.FAILED) Text(s.text("bipe_error_generic"))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pages.earlier) item(key = "earlier") {
                    TextButton(onClick = { history.paginate(earlier = true) }, enabled = pages.status != DayHistory.Paging.LOADING) {
                        Text(s.text("bipe_action_show_more"))
                    }
                }
                items(messages, key = { it.id }) { row ->
                    Column {
                        row.dayBefore?.let { TimelineDay(it, today, focus.zone, s, colors) }
                        if (row.stateLine != null) {
                            GroupStateRow(row, names, s, colors)
                            return@items
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (row.own) Arrangement.End else Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(16.dp), color = colors[if (row.own) "bubble.own" else "bubble.other"],
                                contentColor = colors["ink.primary"]) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!row.own) Text(row.sender, style = MaterialTheme.typography.labelMedium, color = colors["accent.ink"])
                                    Text(row.emotePrefix() + row.body)
                                    Text(TimelineDates.fullTimestamp(row.timestamp, focus.zone, Locale.forLanguageTag(s.locale)),
                                        style = MaterialTheme.typography.labelSmall, color = colors["ink.secondary"])
                                }
                            }
                        }
                        val warning = row.safety as? MessageSafety.Warning
                        if (warning != null) ScamWarningNotice(warning.kind, 0, s, colors)
                    }
                }
                if (pages.later) item(key = "later") {
                    TextButton(onClick = { history.paginate(earlier = false) }, enabled = pages.status != DayHistory.Paging.LOADING) {
                        Text(s.text("bipe_action_show_more"))
                    }
                }
            }
        }
    }
}

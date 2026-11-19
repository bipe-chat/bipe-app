// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable internal fun DesktopSearchDialog(model: DesktopMessageSearch, roomId: String?, activeRoom: String?, s: DesktopCatalog,
                                             close: () -> Unit, openCurrent: (String) -> Unit,
                                             open: (DesktopSearchRoom, String?) -> Unit) {
    var text by remember(model, roomId) { mutableStateOf("") }
    var attempt by remember(model, roomId, text) { mutableStateOf(0) }
    val title = s.text(if (roomId == null) "bipe_app_search_placeholder" else "bipe_search_in_chat")
    val recent by model.recent.collectAsState()
    val focus = remember(model, roomId) { FocusRequester() }
    val joined by produceState<LoadPhase<List<DesktopSearchRoom>>>(LoadPhase.Loading, model, attempt) {
        if (roomId != null) { value = LoadPhase.Ready(emptyList()); return@produceState }
        value = try { LoadPhase.Ready(model.joinedRooms()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LoadPhase.Failed }
    }
    val query = remember(model, roomId, text.trim(), attempt) { model.query(text, roomId) }
    val result = query?.phase?.collectAsState()?.value
    val page = (result as? LoadPhase.Ready)?.value
    val rooms = (joined as? LoadPhase.Ready)?.value.orEmpty()
    val term = ForwardActions.fold(text.trim())
    val foundRooms = if (roomId != null || term.isEmpty()) emptyList()
        else rooms.filter { ForwardActions.fold(it.name).contains(term) }
    val hits = page?.hits.orEmpty()

    fun pick(room: DesktopSearchRoom, event: String?) {
        if (event != null && roomId == null) model.remember(text)
        open(room, event)
    }

    LaunchedEffect(query) {
        if (query != null) { delay(150); model.run(query) }
    }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        val focusManager = LocalFocusManager.current
        LaunchedEffect(focus) { focus.requestFocus() }
        Column(Modifier.widthIn(min = 380.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                else -> false
            }
        }) {
            OutlinedTextField(text, { text = it.take(DesktopMessageSearch.MAX_QUERY) }, singleLine = true,
                label = { Text(title) }, modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) return@onPreviewKeyEvent false
                    val firstRoom = foundRooms.firstOrNull()
                    val firstHit = hits.firstOrNull()
                    when {
                        firstRoom != null -> { pick(firstRoom, null); true }
                        firstHit != null -> { pick(firstHit.room, firstHit.eventId); true }
                        else -> false
                    }
                })
            val locale = Locale.forLanguageTag(s.locale)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 395.dp)) {
                if (text.isBlank() && roomId == null) {
                    if (activeRoom != null) item {
                        TextButton(onClick = { openCurrent(activeRoom) }) { Text(s.text("bipe_search_in_chat")) }
                    }
                    if (recent.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.text("bipe_search_recent"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { model.clearRecent() }) { Text(s.text("bipe_search_recent_clear")) }
                        }
                    }
                    items(recent, key = { "recent/$it" }) { earlier ->
                        SearchRow(earlier, null, null) { text = earlier; focus.requestFocus() }
                    }
                }
                items(foundRooms, key = { "room/${it.id}" }) { room ->
                    val kind = s.text(if (room.isDm) "bipe_tab_chats" else "bipe_tab_groups")
                    SearchRow(room.name, kind, null) { pick(room, null) }
                }
                if (hits.isNotEmpty() && roomId == null) item {
                    Text(s.text("bipe_search_messages"), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
                items(hits, key = { "hit/${it.room.id}/${it.eventId}" }) { hit ->
                    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(hit.timestamp))
                    val heading = if (roomId == null) hit.room.name else hit.sender
                    val snippet = if (roomId == null) "${hit.sender} · ${hit.body}" else hit.body
                    SearchRow(heading, snippet, time) { pick(hit.room, hit.eventId) }
                }
                if (text.isNotBlank()) item {
                    when {
                        result == LoadPhase.Failed || joined == LoadPhase.Failed ->
                            TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
                        result == LoadPhase.Loading || (roomId == null && joined == LoadPhase.Loading) || page?.loading == true ->
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        page?.hasMore == true ->
                            TextButton(onClick = { query?.more() }) { Text(s.text("bipe_action_show_more")) }
                        foundRooms.isEmpty() && hits.isEmpty() ->
                            Text(s.text("bipe_app_search_no_results", text.trim()), Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(s.text("bipe_app_close")) } })
}

@Composable private fun SearchRow(title: String, details: String?, time: String?, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = open)
        .padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (time != null) Text(time, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (details != null) Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

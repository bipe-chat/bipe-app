// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.DesktopTimeline
import chat.bipe.desktop.chat.RoomPins

@Composable internal fun PinnedBanner(model: DesktopTimeline, s: DesktopCatalog, colors: DesktopColors, openList: () -> Unit) {
    val pins by model.pins.pins.collectAsState()
    var eventId by remember(model) { mutableStateOf<String?>(null) }
    val index = pins.indexOfFirst { it.eventId == eventId }.coerceAtLeast(0)
    val pin = pins.getOrNull(index) ?: return
    val heading = if (pins.size == 1) s.text("bipe_pinned_banner_title")
        else s.text("bipe_pinned_banner_count", (index + 1).toString(), pins.size.toString())
    Row(Modifier.fillMaxWidth().background(colors["surface.raised"]), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) {
            model.openOriginal(pin.eventId)
            eventId = pins[(index + 1) % pins.size].eventId
        }.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(heading, style = MaterialTheme.typography.labelMedium, color = colors["accent.ink"])
            PinPreview(pin)
        }
        TextButton(onClick = openList) { Text(s.text("bipe_pinned_view_all")) }
    }
}

@Composable internal fun PinnedList(model: DesktopTimeline, s: DesktopCatalog, close: () -> Unit) {
    val pins by model.pins.pins.collectAsState()
    val access by model.pins.access.collectAsState()
    val change by model.pins.change.collectAsState()
    AlertDialog(onDismissRequest = close, title = { Text(s.text("bipe_pinned_list_title")) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(pins, key = { it.eventId }) { pin ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) {
                                close()
                                model.openOriginal(pin.eventId)
                            }.padding(vertical = 8.dp)) { PinPreview(pin) }
                            if (access.canPin && pin.eventId in access.pinned)
                                TextButton(onClick = { model.pins.change(pin.eventId, pin = false) },
                                    enabled = change != RoomPins.Change.SENDING) { Text(s.text("bipe_unpin_action")) }
                        }
                    }
                }
                if (change == RoomPins.Change.FAILED) Text(s.text("bipe_error_generic"))
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(s.text("bipe_app_close")) } })
}

@Composable private fun PinPreview(pin: RoomPins.Pin) {
    Column {
        Text(pin.sender, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pin.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

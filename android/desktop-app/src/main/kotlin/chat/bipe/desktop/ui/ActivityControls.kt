// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.DmReceipt
import chat.bipe.desktop.chat.TypingPeople

private enum class TypingAnnouncement { APPEARING, PRESENT }

@Composable internal fun TypingIndicator(people: TypingPeople, s: DesktopCatalog, colors: DesktopColors) {
    val label = when (people.count) {
        1 -> s.text("bipe_app_typing_one", people.first)
        2 -> s.text("bipe_app_typing_two", people.first, people.second.orEmpty())
        else -> s.text("bipe_app_typing_many", people.first)
    }
    var announcement by remember { mutableStateOf(TypingAnnouncement.APPEARING) }
    LaunchedEffect(Unit) { withFrameNanos { }; announcement = TypingAnnouncement.PRESENT }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {
        if (announcement == TypingAnnouncement.APPEARING) liveRegion = LiveRegionMode.Polite
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Static dots also respect reduced motion; animation awaits Windows motion-preference acceptance.
        val ink = colors["ink.secondary"]
        Canvas(Modifier.size(22.dp, 6.dp).clearAndSetSemantics { }) {
            repeat(3) { drawCircle(ink, 2.dp.toPx(), Offset((3 + it * 8).dp.toPx(), size.height / 2)) }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = ink)
    }
}

@Composable internal fun DmReceiptFooter(receipt: DmReceipt, s: DesktopCatalog, colors: DesktopColors) {
    if (receipt.tick == DmReceipt.Tick.FAILED) return // Existing guarded Retry stays on the failed message.
    val key = when (receipt.tick) {
        DmReceipt.Tick.SENDING -> "bipe_app_timeline_sending"
        DmReceipt.Tick.SENT -> "bipe_app_receipt_sent"
        DmReceipt.Tick.DELIVERED -> "bipe_app_receipt_delivered"
        DmReceipt.Tick.READ -> "bipe_app_receipt_read"
        DmReceipt.Tick.FAILED -> return
    }
    val label = s.text(key)
    val ink = colors[if (receipt.tick == DmReceipt.Tick.READ) "accent.ink" else "ink.secondary"]
    val icon = when (receipt.tick) {
        DmReceipt.Tick.SENDING -> "clock"
        DmReceipt.Tick.SENT -> "check"
        else -> "checks"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(sharedIcon(icon), contentDescription = label, tint = ink, modifier = Modifier.size(16.dp))
        if (receipt.showsLabel || receipt.tick == DmReceipt.Tick.SENDING)
            Text(label, style = MaterialTheme.typography.labelSmall, color = ink, modifier = Modifier.clearAndSetSemantics { })
    }
}

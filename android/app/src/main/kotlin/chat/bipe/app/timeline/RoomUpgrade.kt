// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeButton
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import kotlinx.coroutines.launch

/** Spec chat-timeline notes, upgraded groups (bipe-chat/bipe#773): the old group points to the group that replaced it. */
@Composable
fun RoomUpgradeBar(model: ChatTimelineModel, open: (String) -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.bipe_room_upgrade_replaced), color = colors.inkPrimary, textAlign = TextAlign.Center)
        BipeButton(
            onClick = {
                joining = true
                scope.launch {
                    val roomId = model.joinSuccessor()
                    joining = false
                    if (roomId != null) open(roomId) else failed = true
                }
            },
            enabled = !joining,
        ) {
            if (joining) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.bipe_room_upgrade_go))
        }
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            text = { Text(stringResource(R.string.bipe_error_generic)) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.bipe_app_close)) } },
        )
    }
}

/** The line above the timeline of a new group: it opens the old group. */
@Composable
fun PredecessorLine(name: String, open: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).clickable(onClick = open).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.CHAT_CIRCLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.bipe_room_upgrade_continues, name), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

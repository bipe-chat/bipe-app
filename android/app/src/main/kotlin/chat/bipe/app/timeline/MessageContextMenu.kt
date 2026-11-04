// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.bipeHaptic
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic

/** Native long-press popup. The current account-owned row is resolved on every update. */
@Composable
fun MessageContextMenu(model: ChatTimelineModel, rowId: String, expanded: Boolean, onDismiss: () -> Unit) {
    val rows by model.rows.collectAsState()
    val row = rows.firstOrNull { it.id == rowId } ?: return
    val actions = model.actions(row)
    val reactions = model.showsReactions(row)
    if (actions.isEmpty() && !reactions) return
    val context = LocalContext.current
    val view = LocalView.current
    val colors = BipeTheme.colors
    val pauseLeft = rememberPauseSecondsLeft(model, rowId)
    Box {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (reactions) {
                // The quick reactions are one row of emoji at the top of the menu, not one row each (bipe-chat/bipe#965).
                val reactLabel = stringResource(R.string.bipe_a11y_react)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    QUICK_REACTIONS.forEach { key ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = reactLabel) { onDismiss(); view.bipeHaptic(BipeHaptic.Tick); model.react(rowId, key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.bipe_app_message_action_more_reactions)) }, onClick = { onDismiss(); model.moreReactions(rowId) })
                if (actions.isNotEmpty()) HorizontalDivider()
            }
            actions.forEach { action ->
                // Spec scam-warning money pause: Copiar waits, with the countdown as its label.
                val paused = action == MessageAction.COPY && pauseLeft > 0
                DropdownMenuItem(
                    enabled = !paused,
                    text = {
                        Text(
                            if (paused) stringResource(R.string.bipe_pause_countdown, pauseLeft.toString()) else stringResource(actionTitle(action, row.message)),
                            color = when {
                                action == MessageAction.DELETE -> colors.trustDangerInk
                                paused -> colors.inkDisabled
                                else -> colors.inkPrimary
                            },
                        )
                    },
                    leadingIcon = { Icon(painterResource(actionIcon(action).drawable), contentDescription = null, tint = colors.glyphSecondary) },
                    onClick = { onDismiss(); model.choose(action, rowId) { copyToClipboard(context, it) } },
                )
            }
        }
    }
}

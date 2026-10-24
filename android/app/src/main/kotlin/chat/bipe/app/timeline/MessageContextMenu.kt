// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    Box {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (reactions) {
                QUICK_REACTIONS.forEach { key ->
                    DropdownMenuItem(text = { Text(key) }, onClick = { onDismiss(); view.bipeHaptic(BipeHaptic.Tick); model.react(rowId, key) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.bipe_app_message_action_more_reactions)) }, onClick = { onDismiss(); model.moreReactions(rowId) })
            }
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(actionTitle(action)), color = if (action == MessageAction.DELETE) colors.trustDangerInk else colors.inkPrimary) },
                    leadingIcon = { Icon(painterResource(actionIcon(action).drawable), contentDescription = null, tint = colors.glyphSecondary) },
                    onClick = { onDismiss(); model.choose(action, rowId) { copyToClipboard(context, it) } },
                )
            }
        }
    }
}

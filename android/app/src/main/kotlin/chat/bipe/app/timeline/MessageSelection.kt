// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** Spec message-forward selection (bipe-chat/bipe#860): at most this many messages. */
const val MESSAGE_SELECTION_LIMIT = 10

/**
 * Spec message-forward selection (bipe-chat/bipe#860): in selection mode a tap on a selectable row picks it.
 * Rows take no other input while the mode is on. [selection] null: the mode is off and the row is as usual.
 */
@Composable
fun SelectableTimelineRow(
    selection: List<String>?,
    rowId: String,
    selectable: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (selection == null) {
        content()
        return
    }
    if (!selectable) {
        Box {
            content()
            InputCover(Modifier.matchParentSize())
        }
        return
    }
    val colors = BipeTheme.colors
    val isPicked = rowId in selection
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = isPicked, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A picked row shows a check mark, an empty ring when not: not only a colour.
        if (isPicked) {
            Icon(
                painterResource(PhosphorIcon.CHECK_CIRCLE.drawable),
                contentDescription = stringResource(R.string.bipe_select_action),
                tint = colors.accentInk,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Box(Modifier.size(24.dp).padding(2.dp).border(1.5.dp, colors.lineStrong, CircleShape))
        }
        Box(Modifier.weight(1f)) {
            content()
            // The cover takes the row's own taps and long press, so the tap reaches the toggle.
            InputCover(Modifier.matchParentSize())
        }
    }
}

/** The topmost child of a Box gets the touch; this one does nothing with it, so the row below does not run. */
@Composable
private fun InputCover(modifier: Modifier) {
    Box(modifier.pointerInput(Unit) {})
}

/** Spec message-forward selection: the bar in place of the composer, with the count, Encaminhar and Apagar. */
@Composable
fun MessageSelectionBar(
    count: Int,
    canForward: Boolean,
    canDelete: Boolean,
    onCancel: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk) }
        Text(
            stringResource(R.string.bipe_select_count, count.toString()),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BipeOutlinedButton(onClick = onForward, enabled = canForward) {
            Text(stringResource(R.string.bipe_forward_action))
        }
        BipeOutlinedButton(onClick = onDelete, enabled = canDelete) {
            Text(stringResource(R.string.bipe_app_message_action_delete), color = if (canDelete) colors.trustDangerInk else colors.inkDisabled)
        }
    }
}

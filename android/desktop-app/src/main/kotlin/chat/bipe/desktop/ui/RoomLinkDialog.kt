// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.link.MatrixRoomLink
import chat.bipe.desktop.link.MatrixRoomLinkResult
import chat.bipe.desktop.link.MatrixRoomLinkResult.Preview.Access
import chat.bipe.desktop.ui.common.BipeButton
import java.text.NumberFormat
import java.util.Locale

private sealed interface RoomLinkDialogPhase {
    data object Loading : RoomLinkDialogPhase
    data class Showing(val result: MatrixRoomLinkResult) : RoomLinkDialogPhase
    data class Submitting(val preview: MatrixRoomLinkResult.Preview) : RoomLinkDialogPhase
}

/** Spec room-link-open. Preview is read-only until the person confirms Join or Request to join. */
@Composable internal fun RoomLinkDialog(runtime: MatrixAccountRuntime, link: MatrixRoomLink, s: DesktopCatalog, colors: DesktopColors,
                                        openRoom: (MatrixRoomLinkResult.Joined) -> Unit, close: () -> Unit) {
    val currentOpenRoom by rememberUpdatedState(openRoom)
    var phase by remember(runtime, link) { mutableStateOf<RoomLinkDialogPhase>(RoomLinkDialogPhase.Loading) }
    LaunchedEffect(runtime, link, phase) {
        val request = phase
        val result = when (request) {
            RoomLinkDialogPhase.Loading -> link.open(runtime)
            is RoomLinkDialogPhase.Submitting -> link.submit(runtime, request.preview)
            is RoomLinkDialogPhase.Showing -> return@LaunchedEffect
        }
        if (phase !== request || !runtime.lifetime.isCurrent()) return@LaunchedEffect
        if (result is MatrixRoomLinkResult.Joined) currentOpenRoom(result)
        else phase = RoomLinkDialogPhase.Showing(result)
    }

    val current = phase
    val result = (current as? RoomLinkDialogPhase.Showing)?.result
    val submitting = current is RoomLinkDialogPhase.Submitting
    val preview = (current as? RoomLinkDialogPhase.Submitting)?.preview ?: result as? MatrixRoomLinkResult.Preview
    val name = preview?.name ?: s.text("bipe_group_common_no_room_name")
    val action = when (preview?.access) {
        Access.JOIN -> s.text("bipe_app_room_link_join")
        Access.KNOCK -> s.text("bipe_app_room_link_knock")
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (phase !is RoomLinkDialogPhase.Submitting) close() },
        title = { Text(when {
            preview != null -> name
            current == RoomLinkDialogPhase.Loading -> s.text("bipe_app_room_link_opening")
            else -> s.text("bipe_tab_groups")
        }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (preview != null) {
                    AvatarPhoto(name, preview.avatarUrl, runtime.media, runtime.identity, colors, size = 72.dp)
                    val members = NumberFormat.getIntegerInstance(Locale.forLanguageTag(s.locale))
                        .format(preview.memberCount.toString().toBigInteger())
                    Text(s.text("bipe_app_room_link_members", members), color = colors["ink.secondary"])
                    preview.topic?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
                when {
                    result == MatrixRoomLinkResult.Failed -> Text(s.text("bipe_error_network"))
                    result == MatrixRoomLinkResult.NotFound -> Text(s.text("bipe_app_room_link_not_found"))
                    preview?.access == Access.INVITE_ONLY -> Text(s.text("bipe_app_room_link_invite_only"))
                    preview?.access == Access.KNOCK_SENT -> Text(s.text("bipe_app_room_link_knock_sent"))
                    current == RoomLinkDialogPhase.Loading || submitting -> CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            if (preview != null && action != null) {
                BipeButton(action, { if (phase === current && !submitting) phase = RoomLinkDialogPhase.Submitting(preview) },
                    enabled = !submitting)
            } else if (result == MatrixRoomLinkResult.Failed) {
                BipeButton(s.text("bipe_app_retry"), { if (phase === current) phase = RoomLinkDialogPhase.Loading })
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !submitting) { Text(s.text("bipe_app_close")) } },
    )
}

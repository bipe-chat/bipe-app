// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.BipeButton
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.people.PeopleService
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.MatrixId
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RoomType
import org.matrix.rustcomponents.sdk.parseMatrixEntityFrom

/** A matrix.to or matrix: link to a room, an alias or an event (spec room-link-open). User links stay profile links. */
data class RoomLink(
    /** The room ID or the alias. */
    val idOrAlias: String,
    val isAlias: Boolean,
    /** The servers the link names, for the preview and the join. */
    val via: List<String>,
) {
    companion object {
        /** `null` when [uri] is not a group link: a user link, another kind of link or nothing Matrix. */
        fun of(uri: String): RoomLink? {
            if (!uri.startsWith("matrix:") && !uri.startsWith("https://matrix.to/")) return null
            val entity = runCatching { parseMatrixEntityFrom(uri) }.getOrNull() ?: return null
            return when (val id = entity.id) {
                is MatrixId.Room -> RoomLink(id.id, isAlias = false, entity.via)
                is MatrixId.EventOnRoomId -> RoomLink(id.roomId, isAlias = false, entity.via)
                is MatrixId.RoomAlias -> RoomLink(id.alias, isAlias = true, entity.via)
                is MatrixId.EventOnRoomAlias -> RoomLink(id.alias, isAlias = true, entity.via)
                is MatrixId.User -> null
            }
        }
    }
}

/** State phase of spec room-link-open. */
private enum class RoomLinkPhase { LOADING, PREVIEW, KNOCK_SENT, INVITE_ONLY, NOT_FOUND, FAILED }

private data class RoomLinkPreview(val name: String?, val avatarUrl: String?, val topic: String?, val memberCount: Long, val canKnock: Boolean)

private fun ClientException.isKind(kind: ErrorKind) = (this as? ClientException.MatrixApi)?.kind == kind

/**
 * Action open of spec room-link-open: the room ID when the person is already in the group, else the preview and the
 * phase it starts in.
 */
private suspend fun previewRoom(client: Client, link: RoomLink): Triple<String?, RoomLinkPreview?, RoomLinkPhase> {
    if (!link.isAlias) {
        val room = runCatching { client.getRoom(link.idOrAlias) }.getOrNull()
        if (room != null && runCatching { room.roomInfo().membership }.getOrNull() == Membership.JOINED) {
            return Triple(room.id(), null, RoomLinkPhase.PREVIEW)
        }
    }
    val info = try {
        val preview = if (link.isAlias) client.getRoomPreviewFromRoomAlias(link.idOrAlias) else client.getRoomPreviewFromRoomId(link.idOrAlias, link.via)
        preview.info()
    } catch (error: ClientException) {
        return Triple(null, null, if (error.isKind(ErrorKind.NotFound)) RoomLinkPhase.NOT_FOUND else RoomLinkPhase.FAILED)
    }
    if (info.membership == Membership.JOINED) return Triple(info.roomId, null, RoomLinkPhase.PREVIEW)
    if (info.roomType is RoomType.Space) return Triple(null, null, RoomLinkPhase.NOT_FOUND)
    val preview = RoomLinkPreview(
        name = info.name?.takeIf { it.isNotBlank() },
        avatarUrl = info.avatarUrl,
        topic = info.topic?.takeIf { it.isNotBlank() },
        memberCount = info.numJoinedMembers.toLong(),
        canKnock = info.joinRule is JoinRule.Knock || info.joinRule is JoinRule.KnockRestricted,
    )
    val phase = when (info.joinRule) {
        is JoinRule.Invite, is JoinRule.Private -> RoomLinkPhase.INVITE_ONLY
        else -> RoomLinkPhase.PREVIEW
    }
    return Triple(null, preview, phase)
}

/** Spec room-link-open: the group of a link, with join or knock. [openChat] closes the people screens. */
@Composable
fun RoomLinkOpenScreen(people: PeopleService, link: RoomLink, openChat: (String) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val client = people.session.client
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(RoomLinkPhase.LOADING) }
    var preview by remember { mutableStateOf<RoomLinkPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        phase = RoomLinkPhase.LOADING
        val (joinedRoomId, found, start) = previewRoom(client, link)
        if (joinedRoomId != null) {
            openChat(joinedRoomId)
            return@LaunchedEffect
        }
        preview = found
        phase = start
    }

    // Actions join and knock: a refusal is inviteOnly, any other error is failed.
    fun act(knock: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (knock) {
                    client.knock(link.idOrAlias, null, link.via)
                    phase = RoomLinkPhase.KNOCK_SENT
                } else {
                    openChat(client.joinRoomByIdOrAlias(link.idOrAlias, link.via).id())
                }
            } catch (error: ClientException) {
                phase = if (error.isKind(ErrorKind.Forbidden)) RoomLinkPhase.INVITE_ONLY else RoomLinkPhase.FAILED
            } finally {
                busy = false
            }
        }
    }

    PeopleScaffold(stringResource(R.string.bipe_app_room_link_opening), BipeScreen.ROOM_LINK_OPEN.id, onClose) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val shown = preview
            when {
                phase == RoomLinkPhase.LOADING -> {
                    CircularProgressIndicator(color = colors.accentFill)
                    Text(stringResource(R.string.bipe_app_room_link_opening), color = colors.inkSecondary)
                }
                phase == RoomLinkPhase.NOT_FOUND ->
                    Text(stringResource(R.string.bipe_app_room_link_not_found), color = colors.inkPrimary, textAlign = TextAlign.Center)
                phase == RoomLinkPhase.FAILED || shown == null -> {
                    Text(stringResource(R.string.bipe_error_network), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    BipeButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                else -> {
                    val name = shown.name ?: stringResource(R.string.bipe_group_common_no_room_name)
                    Avatar(name, shown.avatarUrl, people.media, size = 72.dp)
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.bipe_app_room_link_members, shown.memberCount.toString()), color = colors.inkSecondary)
                    shown.topic?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    when (phase) {
                        RoomLinkPhase.KNOCK_SENT -> Text(stringResource(R.string.bipe_app_room_link_knock_sent), color = colors.inkPrimary, textAlign = TextAlign.Center)
                        RoomLinkPhase.INVITE_ONLY -> Text(stringResource(R.string.bipe_app_room_link_invite_only), color = colors.inkPrimary, textAlign = TextAlign.Center)
                        else -> BipeButton(onClick = { act(knock = shown.canKnock) }, enabled = !busy) {
                            Text(stringResource(if (shown.canKnock) R.string.bipe_app_room_link_knock else R.string.bipe_app_room_link_join))
                        }
                    }
                }
            }
            if (phase != RoomLinkPhase.LOADING) TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
        }
    }
}

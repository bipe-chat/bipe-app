// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.BipeButton
import chat.bipe.app.timeline.ChatTimelineModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import kotlinx.coroutines.launch

/** Spec call start: the voice and video buttons of the chat header. */
@Composable
fun CallHeaderButtons(model: ChatTimelineModel, session: AccountSession, roomName: String) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEncrypted by model.isEncrypted.collectAsState()
    var focus by remember(session.userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(session.userId) { focus = CallCenter.focusUrl(session) }
    if (!isEncrypted || focus == null) return
    for (video in listOf(false, true)) {
        IconButton(onClick = {
            val room = model.matrixRoom ?: return@IconButton
            scope.launch { CallCenter.start(context, session, room, roomName, video) }
        }) {
            Icon(
                painterResource(if (video) PhosphorIcon.VIDEO_CAMERA.drawable else PhosphorIcon.PHONE.drawable),
                contentDescription = stringResource(if (video) R.string.bipe_call_video else R.string.bipe_call_voice),
                tint = colors.glyphPrimary,
            )
        }
    }
}

/** Spec call join: the bar above the timeline while a call runs in the room. */
@Composable
fun OngoingCallBar(model: ChatTimelineModel, session: AccountSession, roomName: String) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEncrypted by model.isEncrypted.collectAsState()
    val hasCall by model.hasCall.collectAsState()
    val active by CallCenter.active.collectAsState()
    val inThisCall = active?.roomId == model.roomId
    if (!isEncrypted || !(hasCall || inThisCall)) return
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.accentWash).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.PHONE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.bipe_call_ongoing),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.inkPrimary,
            modifier = Modifier.weight(1f),
        )
        BipeButton(onClick = {
            if (inThisCall) {
                CallCenter.show(context)
            } else {
                val room = model.matrixRoom ?: return@BipeButton
                scope.launch { CallCenter.start(context, session, room, roomName, video = false) }
            }
        }) {
            Text(stringResource(if (inThisCall) R.string.bipe_call_return else R.string.bipe_call_join))
        }
    }
}

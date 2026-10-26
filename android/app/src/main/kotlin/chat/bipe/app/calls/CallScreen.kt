// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay

/** Spec call: the full-screen call with the video tiles, the own preview, the controls and the time. */
@Composable
fun CallScreen(
    call: CallSession,
    media: MediaLoader?,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = BipeTheme.colors
    val ui by call.ui.collectAsState()
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CALL.id), color = colors.surfaceSunken) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    IconButton(onClick = onMinimise) {
                        Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_call_minimise),
                            tint = colors.glyphPrimary)
                    }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(call.roomName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(status(ui), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                    Spacer(Modifier.size(48.dp))
                }
                when {
                    ui.microphoneDenied -> Denied(stringResource(R.string.bipe_call_microphone_denied), onOpenSettings, onClose)
                    ui.phase == CallSession.Phase.FAILED -> Failed(onClose)
                    else -> {
                        Tiles(call, ui, media, Modifier.weight(1f))
                        if (ui.cameraDenied) Denied(stringResource(R.string.bipe_call_camera_denied), onOpenSettings, null)
                        Controls(call, ui)
                    }
                }
            }
            val local = ui.localTrack
            if (ui.cameraOn && local != null) {
                VideoRenderer(call, local, mirror = ui.frontCamera,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 120.dp).size(110.dp, 160.dp)
                        .clip(RoundedCornerShape(12.dp)))
            }
        }
    }
}

@Composable
private fun status(ui: CallUi): String = when (ui.phase) {
    CallSession.Phase.CONNECTING -> stringResource(R.string.bipe_call_state_connecting)
    CallSession.Phase.CALLING -> stringResource(R.string.bipe_call_state_calling)
    CallSession.Phase.RECONNECTING -> stringResource(R.string.bipe_call_state_reconnecting)
    CallSession.Phase.DECLINED -> stringResource(R.string.bipe_call_declined)
    CallSession.Phase.NO_ANSWER -> stringResource(R.string.bipe_call_no_answer)
    CallSession.Phase.ENDED -> stringResource(R.string.bipe_call_state_ended)
    CallSession.Phase.FAILED -> stringResource(R.string.bipe_call_error_connect)
    CallSession.Phase.CONNECTED -> {
        val start = ui.connectedAt ?: System.currentTimeMillis()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(start) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        val seconds = ((now - start) / 1000).coerceAtLeast(0)
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

@Composable
private fun Tiles(call: CallSession, ui: CallUi, media: MediaLoader?, modifier: Modifier) {
    val colors = BipeTheme.colors
    val shown = ui.tiles.take(CallSession.MAX_TILES)
    val extra = ui.tiles.drop(CallSession.MAX_TILES)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (shown.size <= 1) 1 else 2),
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { tile -> Tile(call, tile, media, tall = shown.size <= 2) }
        }
        if (extra.isNotEmpty()) {
            Text(stringResource(R.string.bipe_call_members), style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary,
                fontWeight = FontWeight.SemiBold)
            extra.forEach { tile ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (media != null) Avatar(tile.name, tile.avatarUrl, media, size = 28.dp, userId = tile.userId)
                    Text(tile.name, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!tile.microphoneOn) {
                        Icon(painterResource(PhosphorIcon.MICROPHONE_SLASH.drawable), contentDescription = stringResource(R.string.bipe_call_muted),
                            tint = colors.glyphSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(call: CallSession, tile: CallTile, media: MediaLoader?, tall: Boolean) {
    val colors = BipeTheme.colors
    val cameraOff = stringResource(R.string.bipe_call_camera_off)
    Box(
        modifier = Modifier.fillMaxWidth().height(if (tall) 260.dp else 170.dp).clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceRaised)
            .border(3.dp, if (tile.speaking) colors.accentFill else Color.Transparent, RoundedCornerShape(16.dp))
            .semantics { if (!tile.cameraOn) stateDescription = cameraOff },
    ) {
        val track = tile.track
        if (tile.cameraOn && track != null) {
            VideoRenderer(call, track, mirror = false, modifier = Modifier.fillMaxSize())
        } else if (media != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Avatar(tile.name, tile.avatarUrl, media, size = 72.dp, userId = tile.userId)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(colors.surfaceBase)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!tile.microphoneOn) {
                Icon(painterResource(PhosphorIcon.MICROPHONE_SLASH.drawable), contentDescription = stringResource(R.string.bipe_call_muted),
                    tint = colors.glyphPrimary, modifier = Modifier.size(14.dp))
            }
            Text(tile.name, style = MaterialTheme.typography.labelMedium, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A LiveKit video track in a TextureViewRenderer. */
@Composable
private fun VideoRenderer(call: CallSession, track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    val room = call.media ?: return
    val context = LocalContext.current
    val renderer = remember(room) { TextureViewRenderer(context).also { room.initVideoRenderer(it) } }
    AndroidView(modifier = modifier, factory = { renderer }, update = { it.setMirror(mirror) })
    DisposableEffect(track, renderer) {
        track.addRenderer(renderer)
        onDispose { track.removeRenderer(renderer) }
    }
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
}

@Composable
private fun Controls(call: CallSession, ui: CallUi) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Control(if (ui.microphoneOn) PhosphorIcon.MICROPHONE else PhosphorIcon.MICROPHONE_SLASH, stringResource(R.string.bipe_call_microphone),
            ui.microphoneOn, call::toggleMicrophone)
        Control(if (ui.cameraOn) PhosphorIcon.VIDEO_CAMERA else PhosphorIcon.VIDEO_CAMERA_SLASH, stringResource(R.string.bipe_call_camera),
            ui.cameraOn, call::toggleCamera)
        if (ui.cameraOn) Control(PhosphorIcon.CAMERA_ROTATE, stringResource(R.string.bipe_call_camera_flip), null, call::flipCamera)
        Control(if (ui.speakerOn) PhosphorIcon.SPEAKER_HIGH else PhosphorIcon.SPEAKER_LOW, stringResource(R.string.bipe_call_speaker),
            ui.speakerOn, call::toggleSpeaker)
        FilledIconButton(
            onClick = call::end,
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.trustDangerInk, contentColor = colors.inkOnAccent),
        ) {
            Icon(painterResource(PhosphorIcon.PHONE_DISCONNECT.drawable), contentDescription = stringResource(R.string.bipe_call_end),
                modifier = Modifier.size(26.dp))
        }
    }
}

/** A round call control. [isOn] null: a plain action. */
@Composable
private fun Control(icon: PhosphorIcon, label: String, isOn: Boolean?, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isOn == false) colors.glyphSecondary else colors.surfaceRaised,
            contentColor = if (isOn == false) colors.inkOnAccent else colors.glyphPrimary,
        ),
    ) {
        Icon(painterResource(icon.drawable), contentDescription = label, modifier = Modifier.size(22.dp))
    }
}

/** No permission: the text, the settings button, and close when the call cannot go on. */
@Composable
private fun Denied(text: String, onOpenSettings: () -> Unit, onClose: (() -> Unit)?) {
    val colors = BipeTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, fontWeight = FontWeight.SemiBold)
        BipeOutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.bipe_contacts_open_settings_android)) }
        if (onClose != null) BipeOutlinedButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
    }
}

@Composable
private fun Failed(onClose: () -> Unit) {
    val colors = BipeTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bipe_call_error_connect), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary,
            fontWeight = FontWeight.SemiBold)
        BipeOutlinedButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
    }
}

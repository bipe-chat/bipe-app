// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.media.voice.ListenerTranscripts
import chat.bipe.app.media.voice.VoiceMediaExpiry
import chat.bipe.app.media.voice.VoiceMediaStore
import chat.bipe.shared.api.BipeKeptMedia
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import androidx.core.content.ContextCompat

/** Over this many characters the transcript folds to 3 lines (same rule as the forks). */
private const val FOLD_LENGTH = 140
private const val BAR_COUNT = 36

/**
 * Spec voice-message: play and pause, the waveform with progress, the duration and the sender's transcript.
 * The bubble, sender, footer and reactions come from the message row of spec timeline-rows.
 */
@Composable
fun VoiceMessageRow(
    message: TimelineMessage,
    voice: VoiceMessage,
    state: VoicePlayer.State,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    /** Action cycleSpeed (bipe#824). */
    onCycleSpeed: () -> Unit = {},
    /** The media loader, for the listener transcript (bipe#500). null: no Transcrever button. */
    loader: MediaLoader? = null,
    /** Expiry and keep state of the account (ADR 0164). null: no keep line. */
    voiceMedia: VoiceMediaStore? = null,
    /** The scam card shows on this message (spec scam-warning). */
    hasScamWarning: Boolean = false,
) {
    val colors = BipeTheme.colors
    val playback = state.playback(message.id)
    val expiredMedia by VoiceMediaExpiry.expired.collectAsState()
    val expired = voice.source.url in expiredMedia
    val keptReason = voiceMedia?.kept?.collectAsState()?.value?.get(voice.source.url)
    val isSent = message.sendState == TimelineMessage.SendState.SENT
    // Actions register and keepScam of spec voice-message (ADR 0164).
    LaunchedEffect(voice.source.url, isSent, hasScamWarning) {
        val store = voiceMedia ?: return@LaunchedEffect
        if (!voice.isVoice) return@LaunchedEffect
        store.load()
        if (message.isOwn && isSent) {
            store.register(voice.source.url, voice.transcript != null)
        } else if (!message.isOwn && hasScamWarning) {
            store.keepForScam(voice.source.url)
        }
    }
    var transcriptExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier.widthIn(max = 256.dp).testTag(BipeScreen.VOICE_MESSAGE.id),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!voice.isVoice) {
            Text(
                voice.filename,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (expired) {
            // State media expired: no player and no waveform; the transcript stays.
            Text(stringResource(R.string.bipe_voice_expired), style = MaterialTheme.typography.titleSmall, color = colors.inkSecondary)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val playLabel = stringResource(
                if (playback == VoicePlayer.Playback.PLAYING) R.string.bipe_app_voice_pause else R.string.bipe_app_voice_play,
            )
            Box(
                modifier = Modifier
                    .bipeTapTarget()
                    .size(40.dp)
                    .background(colors.accentFill, CircleShape)
                    .clickable(onClick = onTogglePlay)
                    .semantics { contentDescription = playLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (playback == VoicePlayer.Playback.LOADING) {
                    CircularProgressIndicator(color = colors.inkOnAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        painterResource(if (playback == VoicePlayer.Playback.PLAYING) PhosphorIcon.PAUSE.drawable else PhosphorIcon.PLAY.drawable),
                        contentDescription = null,
                        tint = colors.inkOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val voiceLabel = stringResource(R.string.bipe_app_media_voice)
            Waveform(
                bars = voice.waveform,
                progress = state.progress(message.id),
                onSeek = onSeek,
                modifier = Modifier.weight(1f).height(32.dp).semantics { contentDescription = voiceLabel },
            )
            Text(voice.durationLabel, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            // Action cycleSpeed (bipe#824).
            val speedLabel = stringResource(R.string.bipe_voice_speed_label, state.speed.label)
            TextButton(onClick = onCycleSpeed, modifier = Modifier.semantics { contentDescription = speedLabel }) {
                Text(
                    stringResource(R.string.bipe_voice_speed, state.speed.label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentInk,
                )
            }
        }
        if (playback == VoicePlayer.Playback.FAILED && !expired) {
            Text(stringResource(R.string.bipe_app_media_failed), style = MaterialTheme.typography.labelSmall, color = colors.trustDangerInk)
        }
        val sender = voice.transcript
        if (sender == null && voice.isVoice && !message.isOwn && loader != null && !expired) {
            ListenerTranscript(message.id, voice, loader) { text ->
                TranscriptBlock(text, transcriptExpanded) { transcriptExpanded = !transcriptExpanded }
            }
        }
        sender?.let { transcript ->
            TranscriptBlock(transcript, transcriptExpanded) { transcriptExpanded = !transcriptExpanded }
        }
        when (keptReason) {
            BipeKeptMedia.Reason.KEPT ->
                Text(stringResource(R.string.bipe_voice_kept), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            BipeKeptMedia.Reason.SCAM ->
                Text(stringResource(R.string.bipe_voice_kept_scam), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            null -> Unit
        }
    }
}

/** The transcript block of ADR 0023: the title, then the text in 3 lines; show more or less over 140 characters. */
@Composable
private fun TranscriptBlock(transcript: String, transcriptExpanded: Boolean, onToggle: () -> Unit) {
    val colors = BipeTheme.colors
    val folds = transcript.length > FOLD_LENGTH
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.bipe_voice_transcript_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.inkSecondary,
        )
        Text(
            transcript,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkPrimary,
            maxLines = if (folds && !transcriptExpanded) 3 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
        if (folds) {
            // Action toggleTranscript.
            Text(
                stringResource(if (transcriptExpanded) R.string.bipe_action_show_less else R.string.bipe_action_show_more),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentInk,
                modifier = Modifier.bipeTapTarget().clickable(role = Role.Button, onClick = onToggle),
            )
        }
    }
}

/** Spec voice-message, action transcribe and state localTranscript (bipe#500): on a tap, on this phone only. */
@Composable
private fun ListenerTranscript(id: String, voice: VoiceMessage, loader: MediaLoader, block: @Composable (String) -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val states by ListenerTranscripts.states.collectAsState()
    val start = { ListenerTranscripts.transcribe(context, id) { loader.file(context, voice.source, voice.filename, voice.mimeType) } }
    // The on-device recogniser needs the microphone permission, even for a file.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else ListenerTranscripts.denied(id)
    }
    val caption = MaterialTheme.typography.labelSmall
    when (val state = states[id] ?: ListenerTranscripts.State.None) {
        ListenerTranscripts.State.None, ListenerTranscripts.State.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state == ListenerTranscripts.State.Failed) {
                Text(stringResource(R.string.bipe_voice_transcribe_failed), style = caption, color = colors.trustDangerInk)
            }
            Text(
                stringResource(R.string.bipe_voice_transcribe),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentInk,
                modifier = Modifier.bipeTapTarget().clickable(role = Role.Button) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        start()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
        ListenerTranscripts.State.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(color = colors.accentFill, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.bipe_voice_transcribing), style = caption, color = colors.inkSecondary)
        }
        is ListenerTranscripts.State.Ready -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block(state.text)
            Text(stringResource(R.string.bipe_voice_transcribed_here), style = caption, color = colors.inkSecondary)
        }
        ListenerTranscripts.State.Unavailable ->
            Text(stringResource(R.string.bipe_voice_transcribe_unavailable), style = caption, color = colors.inkSecondary)
        ListenerTranscripts.State.Denied ->
            Text(stringResource(R.string.bipe_voice_transcribe_denied), style = caption, color = colors.inkSecondary)
    }
}

/** Bars of a voice message, 0 to 1024 each, with the played part in the accent colour. A tap or drag seeks. */
@Composable
fun Waveform(bars: List<Int>, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    val values = resample(bars, BAR_COUNT)
    val positionLabel = stringResource(R.string.bipe_a11y_voice_position)
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { onSeek(it.x / size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek(change.position.x / size.width) }
            }
            // TalkBack seeks with the volume keys or a swipe up or down, like a slider (bipe#499).
            .semantics {
                contentDescription = positionLabel
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f, steps = 9)
                setProgress { value -> onSeek(value.coerceIn(0f, 1f)); true }
            },
    ) {
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { index, value ->
            val barHeight = maxOf(3.dp.toPx(), size.height * value)
            val played = index.toFloat() / values.size < progress
            drawRoundRect(
                color = if (played) colors.accentFill else colors.lineStrong,
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Averages the event bars into count values from 0 to 1. Empty draws a flat line. */
fun resample(bars: List<Int>, count: Int): List<Float> {
    if (bars.isEmpty()) return List(count) { 0f }
    return List(count) { index ->
        val start = index * bars.size / count
        val end = maxOf(start + 1, (index + 1) * bars.size / count).coerceAtMost(bars.size)
        val slice = bars.subList(start, end)
        (slice.sum().toFloat() / slice.size / 1024f).coerceIn(0f, 1f)
    }
}

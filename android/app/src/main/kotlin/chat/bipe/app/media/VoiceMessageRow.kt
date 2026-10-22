// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Over this many characters the transcript folds to 3 lines (same rule as the forks). */
private const val FOLD_LENGTH = 140
private const val BAR_COUNT = 36

/** Spec voice-message: play and pause, the waveform with progress, the duration and the sender's transcript. */
@Composable
fun VoiceMessageRow(
    message: TimelineMessage,
    voice: VoiceMessage,
    state: VoicePlayer.State,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onRetrySend: () -> Unit,
) {
    val colors = BipeTheme.colors
    val playback = state.playback(message.id)
    var transcriptExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().testTag(BipeScreen.VOICE_MESSAGE.id),
        horizontalArrangement = if (message.isOwn) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(if (message.isOwn) colors.bubbleOwn else colors.bubbleOther, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!message.isOwn) {
                Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
            }
            if (!voice.isVoice) {
                Text(
                    voice.filename,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val playLabel = stringResource(
                    if (playback == VoicePlayer.Playback.PLAYING) R.string.bipe_app_voice_pause else R.string.bipe_app_voice_play,
                )
                Box(
                    modifier = Modifier
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
            }
            if (playback == VoicePlayer.Playback.FAILED) {
                Text(stringResource(R.string.bipe_app_media_failed), style = MaterialTheme.typography.labelSmall, color = colors.trustDangerInk)
            }
            voice.transcript?.let { transcript ->
                // The transcript block of ADR 0023: the title, then the text in 3 lines; show more or less over 140 characters.
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
                            modifier = Modifier.clickable { transcriptExpanded = !transcriptExpanded },
                        )
                    }
                }
            }
            when (message.sendState) {
                TimelineMessage.SendState.FAILED -> Row(
                    modifier = Modifier.clickable(onClick = onRetrySend),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.bipe_app_upload_failed), style = MaterialTheme.typography.labelSmall, color = colors.trustDangerInk)
                }
                TimelineMessage.SendState.SENDING -> Text(
                    stringResource(R.string.bipe_app_timeline_sending),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
                TimelineMessage.SendState.SENT -> Unit
            }
        }
    }
}

/** Bars of a voice message, 0 to 1024 each, with the played part in the accent colour. A tap or drag seeks. */
@Composable
fun Waveform(bars: List<Int>, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    val values = resample(bars, BAR_COUNT)
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { onSeek(it.x / size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek(change.position.x / size.width) }
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

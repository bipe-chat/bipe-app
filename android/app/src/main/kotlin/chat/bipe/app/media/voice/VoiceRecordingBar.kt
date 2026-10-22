// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.voice

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import chat.bipe.app.media.MediaFormat
import chat.bipe.app.media.Waveform
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec voice-recording: replaces the composer field while the person records and checks the voice message. */
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun VoiceRecordingBar(model: VoiceRecordingModel, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val isClosed by model.isClosed.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(isClosed) { if (isClosed) onClose() }
    val phase by model.phase.collectAsState()
    val duration by model.duration.collectAsState()
    val waveform by model.waveform.collectAsState()
    val transcript by model.transcript.collectAsState()
    val transcriptText by model.transcriptText.collectAsState()
    val isPlaying by model.isPlaying.collectAsState()
    val durationLabel = MediaFormat.duration(duration)
    val durationDescription = stringResource(R.string.bipe_app_voice_duration, durationLabel)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag(BipeScreen.VOICE_RECORDING.id),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIcon(PhosphorIcon.TRASH, stringResource(R.string.bipe_app_voice_delete), colors.trustDangerInk, onClick = model::delete)
            when (phase) {
                VoiceRecordingModel.Phase.RECORDING, VoiceRecordingModel.Phase.PAUSED -> {
                    Column {
                        Text(
                            stringResource(if (phase == VoiceRecordingModel.Phase.RECORDING) R.string.bipe_app_voice_recording else R.string.bipe_app_voice_resume),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (phase == VoiceRecordingModel.Phase.RECORDING) colors.trustDangerInk else colors.inkSecondary,
                        )
                        Text(
                            durationLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkPrimary,
                            modifier = Modifier.semantics { contentDescription = durationDescription },
                        )
                    }
                    Waveform(waveform, 0f, {}, Modifier.weight(1f).height(28.dp))
                    if (phase == VoiceRecordingModel.Phase.RECORDING) {
                        RoundIcon(PhosphorIcon.PAUSE, stringResource(R.string.bipe_app_voice_pause), colors.glyphPrimary, onClick = model::pause)
                    } else {
                        RoundIcon(PhosphorIcon.MICROPHONE, stringResource(R.string.bipe_app_voice_resume), colors.trustDangerInk, onClick = model::resume)
                    }
                    RoundIcon(PhosphorIcon.STOP, stringResource(R.string.bipe_app_voice_stop), colors.inkOnAccent, colors.accentFill, onClick = model::stop)
                }
                VoiceRecordingModel.Phase.PREVIEW, VoiceRecordingModel.Phase.SENDING -> {
                    RoundIcon(
                        if (isPlaying) PhosphorIcon.PAUSE else PhosphorIcon.PLAY,
                        stringResource(if (isPlaying) R.string.bipe_app_voice_pause else R.string.bipe_app_voice_play),
                        colors.accentInk,
                        onClick = model::play,
                    )
                    Waveform(waveform, 0f, {}, Modifier.weight(1f).height(28.dp))
                    Text(
                        durationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                        modifier = Modifier.semantics { contentDescription = durationDescription },
                    )
                    IconButton(
                        onClick = model::send,
                        enabled = phase == VoiceRecordingModel.Phase.PREVIEW,
                        modifier = Modifier.background(colors.accentFill, CircleShape),
                    ) {
                        if (phase == VoiceRecordingModel.Phase.SENDING) {
                            CircularProgressIndicator(color = colors.inkOnAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(painterResource(PhosphorIcon.PAPER_PLANE_RIGHT.drawable), contentDescription = stringResource(R.string.bipe_app_timeline_send), tint = colors.inkOnAccent)
                        }
                    }
                }
            }
        }
        if (phase == VoiceRecordingModel.Phase.PREVIEW || phase == VoiceRecordingModel.Phase.SENDING) {
            // The transcript the phone made (ADR 0023): the person can correct or clear it before sending.
            when (transcript) {
                VoiceRecordingModel.Transcript.OFF -> Unit
                VoiceRecordingModel.Transcript.WORKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(color = colors.accentFill, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.bipe_voice_transcribing), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                }
                VoiceRecordingModel.Transcript.READY -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.bipe_voice_transcript_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.inkSecondary)
                    val placeholder = stringResource(R.string.bipe_voice_transcript_placeholder)
                    BasicTextField(
                        value = transcriptText,
                        onValueChange = model::editTranscript,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary),
                        cursorBrush = SolidColor(colors.accentFill),
                        modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            Box {
                                if (transcriptText.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                                inner()
                            }
                        },
                    )
                }
                VoiceRecordingModel.Transcript.UNAVAILABLE -> Text(
                    stringResource(R.string.bipe_voice_transcript_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

/** The microphone permission was denied: bipe_microphone_permission_body with a button to the app settings. */
@Composable
fun MicrophoneDeniedBar(onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bipe_microphone_permission_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text(stringResource(R.string.bipe_contacts_open_settings_android), color = colors.accentInk) }
        RoundIcon(PhosphorIcon.X, stringResource(R.string.bipe_app_cancel), colors.glyphSecondary, onClick = onClose)
    }
}

@Composable
private fun RoundIcon(icon: PhosphorIcon, label: String, tint: Color, fill: Color? = null, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    IconButton(onClick = onClick, modifier = Modifier.background(fill ?: colors.surfaceSunken, CircleShape)) {
        Icon(painterResource(icon.drawable), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.*
import java.text.NumberFormat
import java.util.Locale
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class TimelineVoicePlayer(private val scope: CoroutineScope, private val media: AccountMedia) {
    sealed interface Phase {
        val row: String?
        data object Idle : Phase { override val row = null }
        data class Loading(override val row: String) : Phase
        data class Loaded(override val row: String, val output: VoiceOutput, val duration: Long) : Phase
        data class Failed(override val row: String) : Phase
    }

    private val speeds = listOf(1f, 1.5f, 2f)
    private val preferences by lazy { runCatching { Preferences.userRoot().node("chat/bipe/desktop") }.getOrNull() }
    var speed by mutableStateOf(1f)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    private var job: Job? = null

    init {
        speed = runCatching { preferences?.getFloat("voiceSpeed", 1f) }.getOrNull()?.takeIf { it in speeds } ?: 1f
    }

    fun nextSpeed() {
        speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
        (phase as? Phase.Loaded)?.output?.speed(speed)
        runCatching { preferences?.putFloat("voiceSpeed", speed) }
    }

    fun toggle(row: String, attachment: MessageMedia) {
        val current = phase
        if (current.row == row && current is Phase.Loaded) {
            val output = current.output
            if (output.status.value == VoiceOutput.Status.PLAYING) output.play(false)
            else {
                if (output.status.value == VoiceOutput.Status.FINISHED) output.seek(0f)
                output.play(true)
            }
            return
        }
        if (current is Phase.Loading && current.row == row) return
        stop()
        phase = Phase.Loading(row)
        job = scope.launch {
            try {
                val bytes = checkNotNull(media.eventPicture(attachment.sourceJson))
                val pcm = VoicePcm.decode(bytes)
                val output = VoiceOutput(pcm)
                output.speed(speed)
                output.play(true)
                phase = Phase.Loaded(row, output, pcm.durationMillis)
                output.run()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { phase = Phase.Failed(row) }
        }
    }

    fun stop() { job?.cancel(); job = null; phase = Phase.Idle }
}

@Composable internal fun VoiceControls(player: TimelineVoicePlayer, row: String, media: MessageMedia, s: DesktopCatalog) {
    val phase = player.phase.takeIf { it.row == row }
    val loaded = phase as? TimelineVoicePlayer.Phase.Loaded
    val output = loaded?.output
    val status = output?.status?.collectAsState()?.value
    val position = output?.position?.collectAsState()?.value ?: 0f
    val playing = status == VoiceOutput.Status.PLAYING
    val duration = loaded?.duration ?: media.durationMillis
    val speedLabel = NumberFormat.getNumberInstance(Locale.forLanguageTag(s.locale)).apply { maximumFractionDigits = 1 }.format(player.speed)
    Column(Modifier.widthIn(min = 240.dp, max = 360.dp)) {
        if (media.kind == MessageMedia.Kind.AUDIO) Text(media.filename, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player.toggle(row, media) }, enabled = phase !is TimelineVoicePlayer.Phase.Loading) {
                Text(s.text(when (phase) {
                    is TimelineVoicePlayer.Phase.Loading -> "bipe_web_loading"
                    is TimelineVoicePlayer.Phase.Failed -> "bipe_app_retry"
                    else -> if (playing) "bipe_app_voice_pause" else "bipe_app_voice_play"
                }))
            }
            Slider(position, onValueChange = { output?.seek(it) }, enabled = output != null,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = s.text("bipe_app_voice_duration", durationLabel(duration ?: 0))
                })
            TextButton(onClick = player::nextSpeed, modifier = Modifier.semantics {
                contentDescription = s.text("bipe_voice_speed_label", speedLabel)
            }) { Text(s.text("bipe_voice_speed", speedLabel)) }
        }
        duration?.let {
            Text(durationLabel((it * position).toLong()) + " / " + durationLabel(it),
                style = MaterialTheme.typography.labelSmall)
        }
        if (phase is TimelineVoicePlayer.Phase.Failed) Text(s.text("bipe_app_media_failed"), style = MaterialTheme.typography.labelSmall)
    }
}

private sealed interface CapturePhase {
    data object Recording : CapturePhase
    data object MicrophoneDenied : CapturePhase
    data object TooShort : CapturePhase
    data class Ready(val audio: VoicePcm, val sendFailed: Boolean = false) : CapturePhase
    data class Sending(val audio: VoicePcm) : CapturePhase
}

@Composable internal fun RecordVoice(action: VoiceSend, reply: String?, s: DesktopCatalog, close: () -> Unit) {
    var attempt by remember(action) { mutableStateOf(0) }
    val capture = remember(action, attempt) { VoiceCapture() }
    val duration by capture.duration.collectAsState()
    var phase by remember(action, attempt) { mutableStateOf<CapturePhase>(CapturePhase.Recording) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(capture) {
        phase = try {
            val audio = capture.record()
            if (audio.durationMillis < 1000) CapturePhase.TooShort else CapturePhase.Ready(audio)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CapturePhase.MicrophoneDenied }
    }
    val sending = phase is CapturePhase.Sending
    AlertDialog(onDismissRequest = { if (!sending) close() }, title = { Text(s.text("bipe_app_composer_record")) },
        text = {
            when (val current = phase) {
                CapturePhase.Recording -> Column {
                    Text(s.text("bipe_app_voice_recording"))
                    Text(s.text("bipe_app_voice_duration", durationLabel(duration)))
                }
                CapturePhase.MicrophoneDenied -> Text(s.text("bipe_call_microphone_denied"))
                CapturePhase.TooShort -> Text(s.text("bipe_app_voice_too_short"))
                is CapturePhase.Ready -> RecordingReview(current.audio, current.sendFailed, s)
                is CapturePhase.Sending -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !sending) { Text(s.text("bipe_app_cancel")) } },
        confirmButton = {
            when (val current = phase) {
                CapturePhase.Recording -> TextButton(onClick = capture::stop) { Text(s.text("bipe_app_voice_stop")) }
                CapturePhase.MicrophoneDenied, CapturePhase.TooShort ->
                    TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
                is CapturePhase.Ready -> TextButton(onClick = {
                    if (phase !== current) return@TextButton
                    phase = CapturePhase.Sending(current.audio)
                    scope.launch {
                        try { action.enqueue(current.audio, reply); close() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { phase = current.copy(sendFailed = true) }
                    }
                }) { Text(s.text("bipe_app_timeline_send")) }
                is CapturePhase.Sending -> Unit
            }
        },
    )
}

@Composable private fun RecordingReview(audio: VoicePcm, failed: Boolean, s: DesktopCatalog) {
    val output = remember(audio) { VoiceOutput(audio) }
    var available by remember(audio) { mutableStateOf(true) }
    val state by output.status.collectAsState()
    LaunchedEffect(output) {
        try { output.run() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { available = false }
    }
    Column {
        Text(s.text("bipe_app_voice_duration", durationLabel(audio.durationMillis)))
        TextButton(enabled = available, onClick = {
            if (state == VoiceOutput.Status.PLAYING) output.play(false)
            else { if (state == VoiceOutput.Status.FINISHED) output.seek(0f); output.play(true) }
        }) { Text(s.text(if (state == VoiceOutput.Status.PLAYING) "bipe_app_voice_pause" else "bipe_app_voice_play")) }
        if (!available) Text(s.text("bipe_app_media_failed"))
        if (failed) Text(s.text("bipe_app_voice_failed"))
    }
}

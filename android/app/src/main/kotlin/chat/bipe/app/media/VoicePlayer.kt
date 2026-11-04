// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.media.voice.VoiceMediaExpiry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind

/** The player of the voice-message rows of one chat. One row plays at a time (action togglePlay). */
class VoicePlayer(
    private val context: Context,
    private val loader: MediaLoader,
    private val scope: CoroutineScope,
) {
    /** playback of spec voice-message. */
    enum class Playback { IDLE, LOADING, PLAYING, PAUSED, FAILED }

    /** State speed of spec voice-message: one speed for every row on this phone (bipe#824). */
    enum class Speed(val rate: Float) {
        NORMAL(1f),
        ONE_AND_HALF(1.5f),
        DOUBLE(2f),
        ;

        /** Action cycleSpeed: normal, oneAndHalf, double, then normal again. */
        val next: Speed get() = entries[(ordinal + 1) % entries.size]

        /** The number with the phone's decimal sign ("1,5" in pt-BR), for bipe_voice_speed. */
        val label: String get() = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(rate)
    }

    data class State(
        val rowId: String? = null,
        val playback: Playback = Playback.IDLE,
        /** Played part of the current row, 0 to 1. */
        val progress: Float = 0f,
        val failedRows: Set<String> = emptySet(),
        /** Played part of rows that are not playing, so a row resumes where it stopped. */
        val positions: Map<String, Float> = emptyMap(),
        val speed: Speed = Speed.NORMAL,
    ) {
        fun playback(id: String): Playback = when {
            rowId == id -> playback
            id in failedRows -> Playback.FAILED
            else -> Playback.IDLE
        }

        fun progress(id: String): Float = if (rowId == id) progress else positions[id] ?: 0f
    }

    private val prefs = context.getSharedPreferences("voice_player", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        State(speed = Speed.entries.firstOrNull { it.name == prefs.getString(KEY_SPEED, null) } ?: Speed.NORMAL),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var load: Job? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var atEar = false
    private val speaker = attributes(AudioAttributes.USAGE_MEDIA)
    private val earpiece = attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speaker)
        .setOnAudioFocusChangeListener { change ->
            // A call or other audio stops playback (spec voice-message notes).
            if (change < 0) pause()
        }
        .build()

    /** Speaker by default; the earpiece when the phone is at the ear (spec voice-message notes). */
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = proximity ?: return
            val near = event.values.firstOrNull()?.let { it < sensor.maximumRange } ?: false
            if (near != atEar) {
                atEar = near
                reroute()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val path = HashMap<String, String>()

    /** Action togglePlay: downloads the audio when needed, then plays or pauses. Starting one row stops any other. */
    fun togglePlay(id: String, voice: VoiceMessage) {
        val current = _state.value
        if (current.rowId == id && current.playback == Playback.PLAYING) {
            pause()
            return
        }
        val existing = player
        if (current.rowId == id && current.playback == Playback.PAUSED && existing != null) {
            start(existing)
            return
        }
        stop()
        _state.value = _state.value.copy(rowId = id, playback = Playback.LOADING, failedRows = _state.value.failedRows - id)
        load = scope.launch {
            val download = runCatching { loader.file(context, voice.source, voice.filename, voice.mimeType) }
            // The media repository answered 404: the server deleted the audio after its time (ADR 0164).
            download.exceptionOrNull()?.let { if (isGone(it)) VoiceMediaExpiry.markExpired(voice.source.url) }
            val file = download.getOrNull()
            if (_state.value.rowId != id) return@launch
            val prepared = file?.let { prepare(id, it.path) }
            if (prepared == null) {
                _state.value = _state.value.copy(playback = Playback.FAILED, failedRows = _state.value.failedRows + id)
                return@launch
            }
            _state.value.positions[id]?.let { prepared.seekTo((prepared.duration * it).toInt()) }
            player = prepared
            start(prepared)
        }
    }

    private fun isGone(error: Throwable): Boolean = when (error) {
        is ClientException.MatrixApi -> error.kind is ErrorKind.NotFound
        // Media downloads can report the HTTP error as text.
        is ClientException.Generic -> error.msg.contains("M_NOT_FOUND")
        else -> false
    }

    private fun prepare(id: String, filePath: String): MediaPlayer? = runCatching {
        path[id] = filePath
        MediaPlayer().apply {
            setAudioAttributes(if (atEar) earpiece else speaker)
            setDataSource(filePath)
            prepare()
            setOnCompletionListener {
                _state.value = _state.value.copy(positions = _state.value.positions - id)
                stop()
            }
        }
    }.getOrNull()

    /** Plays on from the same position through the other output. */
    private fun reroute() {
        val current = player ?: return
        val id = _state.value.rowId ?: return
        val filePath = path[id] ?: return
        if (_state.value.playback != Playback.PLAYING) return
        val position = current.currentPosition
        current.release()
        audioManager?.mode = if (atEar) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        val next = prepare(id, filePath) ?: return stop()
        next.seekTo(position)
        player = next
        start(next)
    }

    /** Action cycleSpeed. A playing row changes speed at once; PlaybackParams keeps the pitch. */
    fun cycleSpeed() {
        val speed = _state.value.speed.next
        prefs.edit().putString(KEY_SPEED, speed.name).apply()
        _state.value = _state.value.copy(speed = speed)
        // On a paused or prepared player a new speed would start playback, so it waits for start().
        val current = player
        if (current != null && _state.value.playback == Playback.PLAYING) applySpeed(current)
    }

    private fun applySpeed(player: MediaPlayer) {
        runCatching { player.playbackParams = player.playbackParams.setSpeed(_state.value.speed.rate) }
    }

    /** Action seek. */
    fun seek(id: String, fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        val current = player
        if (_state.value.rowId == id && current != null) {
            current.seekTo((current.duration * value).toInt())
            _state.value = _state.value.copy(progress = value)
        } else {
            _state.value = _state.value.copy(positions = _state.value.positions + (id to value))
        }
    }

    fun pause() {
        val current = player ?: return
        if (_state.value.playback != Playback.PLAYING) return
        current.pause()
        val rowId = _state.value.rowId
        _state.value = _state.value.copy(
            playback = Playback.PAUSED,
            positions = if (rowId != null) _state.value.positions + (rowId to _state.value.progress) else _state.value.positions,
        )
        ticker?.cancel()
        abandonFocus()
    }

    fun stop() {
        if (atEar) audioManager?.mode = AudioManager.MODE_NORMAL
        atEar = false
        load?.cancel()
        ticker?.cancel()
        player?.release()
        player = null
        abandonFocus()
        _state.value = _state.value.copy(rowId = null, playback = Playback.IDLE, progress = 0f)
    }

    private fun start(player: MediaPlayer) {
        audioManager?.requestAudioFocus(focusRequest)
        proximity?.let { sensorManager?.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        player.start()
        applySpeed(player)
        _state.value = _state.value.copy(playback = Playback.PLAYING)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val duration = player.duration
                if (duration > 0 && _state.value.playback == Playback.PLAYING) {
                    _state.value = _state.value.copy(progress = player.currentPosition.toFloat() / duration)
                }
                delay(50)
            }
        }
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
        sensorManager?.unregisterListener(proximityListener)
    }

    private companion object {
        const val KEY_SPEED = "speed"
    }

    private fun attributes(usage: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

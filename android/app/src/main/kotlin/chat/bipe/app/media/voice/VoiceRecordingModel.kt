// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import chat.bipe.app.media.upload.MediaPreparation
import chat.bipe.voice.AndroidBipeVoiceTranscriber
import chat.bipe.voice.BipeVoiceTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import java.io.File
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.abs

/**
 * Spec voice-recording: records in the composer, with pause and resume, then a preview with the
 * transcript the phone made. Send puts the transcript in the caption of the voice event (ADR 0023).
 * Recording needs the Opus encoder of Android 10; the composer offers it from Android 10 on.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class VoiceRecordingModel(
    private val context: Context,
    private val timeline: Timeline,
    private val replyToEventId: String?,
    private val scope: CoroutineScope,
) {
    enum class Phase { RECORDING, PAUSED, PREVIEW, SENDING }

    /** transcript of spec voice-recording. */
    enum class Transcript { OFF, WORKING, READY, UNAVAILABLE }

    private val _phase = MutableStateFlow(Phase.RECORDING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Recorded length in milliseconds. */
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** 0 to 1024 each; the live levels while recording, about 100 samples after stop. */
    private val _waveform = MutableStateFlow<List<Int>>(emptyList())
    val waveform: StateFlow<List<Int>> = _waveform.asStateFlow()
    private val _transcript = MutableStateFlow(Transcript.OFF)
    val transcript: StateFlow<Transcript> = _transcript.asStateFlow()
    private val _transcriptText = MutableStateFlow("")
    val transcriptText: StateFlow<String> = _transcriptText.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _tooShort = MutableStateFlow(false)
    val tooShort: StateFlow<Boolean> = _tooShort.asStateFlow()

    /** true when the recording ended: the composer goes back to text. */
    private val _isClosed = MutableStateFlow(false)
    val isClosed: StateFlow<Boolean> = _isClosed.asStateFlow()

    private val folder = MediaPreparation.workFolder(context)
    private val file = File(folder, "voice-${System.currentTimeMillis()}.ogg")
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var writer: OggOpusWriter? = null
    private var loop: Job? = null
    private var session: BipeVoiceTranscriber.Session? = null
    private var player: MediaPlayer? = null
    private val levels = mutableListOf<Float>()
    private var samplesRecorded = 0L

    /** Action start: the composer asked for the microphone; records Opus, mono, 48 kHz. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val started = runCatching {
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 4 * 2))
            // Opus at 24 kbit/s: a lower bitrate saves data on Brazilian carriers (spec voice-recording notes).
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 24000)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            audio.startRecording()
            record = audio
            codec = encoder
            writer = OggOpusWriter(file)
        }.isSuccess
        if (!started) {
            release()
            _isClosed.value = true
            return
        }
        session = AndroidBipeVoiceTranscriber(context).startSession(scope, SAMPLE_RATE)
        _phase.value = Phase.RECORDING
        runLoop()
    }

    /** Action pause. */
    fun pause() {
        if (_phase.value != Phase.RECORDING) return
        _phase.value = Phase.PAUSED
        record?.stop()
    }

    /** Action resume: records on into the same file. */
    fun resume() {
        if (_phase.value != Phase.PAUSED) return
        record?.startRecording()
        _phase.value = Phase.RECORDING
    }

    /** Action stop: under 1 second goes back with bipe_app_voice_too_short; else preview, and the transcript finishes. */
    fun stop() {
        if (_phase.value != Phase.RECORDING && _phase.value != Phase.PAUSED) return
        _phase.value = Phase.PREVIEW
        scope.launch {
            loop?.cancel()
            loop?.join()
            withContext(Dispatchers.IO) { drain(endOfStream = true) }
            writer?.finish()
            writer = null
            release()
            _duration.value = samplesRecorded * 1000 / SAMPLE_RATE
            if (_duration.value < MIN_DURATION_MS) {
                session?.cancel()
                _tooShort.value = true
                delete()
                return@launch
            }
            _waveform.value = resample(levels, WAVEFORM_SAMPLES)
            val transcriber = session
            if (transcriber == null) {
                _transcript.value = Transcript.OFF
            } else {
                _transcript.value = Transcript.WORKING
                val text = transcriber.finish()
                if (_phase.value != Phase.PREVIEW) return@launch
                if (text != null) {
                    _transcriptText.value = text
                    _transcript.value = Transcript.READY
                } else {
                    _transcript.value = Transcript.UNAVAILABLE
                }
            }
        }
    }

    /** Action play: plays or pauses the recording. */
    fun play() {
        if (_phase.value != Phase.PREVIEW) return
        val current = player
        if (current != null && current.isPlaying) {
            current.pause()
            _isPlaying.value = false
            return
        }
        val playing = current ?: runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { _isPlaying.value = false }
            }
        }.getOrNull()?.also { player = it } ?: return
        playing.start()
        _isPlaying.value = true
    }

    /** Action editTranscript. */
    fun editTranscript(text: String) {
        _transcriptText.value = text
    }

    /** Action delete: drops the file and the transcript. */
    fun delete() {
        loop?.cancel()
        session?.cancel()
        release()
        player?.release()
        player = null
        folder.deleteRecursively()
        _isClosed.value = true
    }

    /**
     * Action send: the voice attachment with the transcript as caption when it is ready and not blank.
     * A transcript still working is dropped: the user never waits for it (ADR 0023).
     */
    fun send() {
        if (_phase.value != Phase.PREVIEW) return
        _phase.value = Phase.SENDING
        player?.release()
        player = null
        val caption = if (_transcript.value == Transcript.READY) _transcriptText.value.trim().takeIf { it.isNotEmpty() } else null
        session?.cancel()
        runCatching {
            timeline.sendVoiceMessage(
                UploadParameters(
                    source = UploadSource.File(file.path),
                    caption = caption,
                    formattedCaption = null,
                    mentions = null,
                    inReplyTo = replyToEventId,
                    extraContentJson = null,
                ),
                AudioInfo(duration = Duration.ofMillis(_duration.value), size = file.length().toULong(), mimetype = "audio/ogg"),
                _waveform.value.map { it / 1024f },
            )
        }
        _isClosed.value = true
    }

    private fun runLoop() {
        loop = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME)
            while (isActive) {
                val audio = record ?: break
                if (_phase.value != Phase.RECORDING) {
                    drain(endOfStream = false)
                    delay(50)
                    continue
                }
                val read = audio.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val chunk = buffer.copyOf(read)
                session?.write(chunk)
                feed(chunk)
                drain(endOfStream = false)
                samplesRecorded += read
                val peak = chunk.maxOf { abs(it.toInt()) } / 32768f
                synchronized(levels) { levels.add(peak) }
                _duration.value = samplesRecorded * 1000 / SAMPLE_RATE
                _waveform.value = resample(synchronized(levels) { levels.takeLast(40) }, 40)
            }
        }
    }

    private fun feed(pcm: ShortArray) {
        val encoder = codec ?: return
        val index = encoder.dequeueInputBuffer(10_000)
        if (index < 0) return
        val input = encoder.getInputBuffer(index) ?: return
        input.clear()
        input.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
        encoder.queueInputBuffer(index, 0, pcm.size * 2, samplesRecorded * 1_000_000 / SAMPLE_RATE, 0)
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        if (endOfStream) {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) encoder.queueInputBuffer(index, 0, 0, samplesRecorded * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        val info = MediaCodec.BufferInfo()
        var waits = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // At the end, wait up to half a second for the last packets.
                if (!endOfStream || ++waits > 50) return
                continue
            }
            if (index < 0) continue
            val output = encoder.getOutputBuffer(index)
            // Codec config buffers hold the encoder's own OpusHead; the writer writes the headers itself.
            if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val packet = ByteArray(info.size)
                output.position(info.offset)
                output.get(packet)
                writer?.append(packet)
            }
            encoder.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun release() {
        runCatching { record?.stop() }
        record?.release()
        record = null
        runCatching { codec?.stop() }
        codec?.release()
        codec = null
    }

    companion object {
        private const val SAMPLE_RATE = 48000

        /** 20 ms of audio. */
        private const val FRAME = 960
        private const val MIN_DURATION_MS = 1000L

        /** About 100 samples for the bubble. */
        private const val WAVEFORM_SAMPLES = 100

        fun resample(levels: List<Float>, count: Int): List<Int> {
            if (levels.isEmpty()) return emptyList()
            return List(count) { index ->
                val start = (index * levels.size / count).coerceAtMost(levels.size - 1)
                val end = maxOf(start + 1, (index + 1) * levels.size / count).coerceAtMost(levels.size)
                (levels.subList(start, end).max() * 1024).toInt().coerceIn(0, 1024)
            }
        }
    }
}

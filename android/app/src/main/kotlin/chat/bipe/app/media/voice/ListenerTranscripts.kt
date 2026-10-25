// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import chat.bipe.voice.AndroidBipeVoiceTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/**
 * Spec voice-message, action transcribe (bipe-chat/bipe#500): the listener's own transcript of a received voice
 * message, made on the phone only (SpeechRecognizer.createOnDeviceSpeechRecognizer). The audio and the text never leave
 * the phone; the text lives in memory for the session.
 */
object ListenerTranscripts {
    /** State localTranscript of spec voice-message. */
    sealed interface State {
        data object None : State
        data object Working : State
        data class Ready(val text: String) : State
        data object Unavailable : State
        data object Denied : State
        data object Failed : State
    }

    private const val SAMPLE_RATE = 16_000
    private const val CHUNK = 3_200

    private val scope: CoroutineScope = MainScope()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun denied(id: String) = _states.update { it + (id to State.Denied) }

    /** Action transcribe. [file] downloads the audio to the cache. */
    fun transcribe(context: Context, id: String, file: suspend () -> File) {
        if (_states.value[id] == State.Working) return
        _states.update { it + (id to State.Working) }
        scope.launch {
            val session = AndroidBipeVoiceTranscriber(context.applicationContext).startSession(scope, SAMPLE_RATE)
            if (session == null) {
                _states.update { it + (id to State.Unavailable) }
                return@launch
            }
            val pcm = runCatching { withContext(Dispatchers.IO) { decode(file()) } }.getOrNull()
            if (pcm == null) {
                session.cancel()
                _states.update { it + (id to State.Failed) }
                return@launch
            }
            var start = 0
            while (start < pcm.size) {
                session.write(pcm.copyOfRange(start, minOf(pcm.size, start + CHUNK)))
                start += CHUNK
            }
            val text = session.finish()
            _states.update { it + (id to (text?.let { State.Ready(it) } ?: State.Failed)) }
        }
    }

    /** Decodes the voice file (Ogg Opus or any format the phone reads) to 16 kHz mono 16-bit PCM. */
    private fun decode(file: File): ShortArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val samples = ArrayList<Short>()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        out >= 0 -> {
                            val shorts = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frame = ShortArray(shorts.remaining()).also { shorts.get(it) }
                            // Mono: the mean of the channels.
                            var i = 0
                            while (i + channels <= frame.size) {
                                var sum = 0
                                for (c in 0 until channels) sum += frame[i + c]
                                samples.add((sum / channels).toShort())
                                i += channels
                            }
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return resample(samples, rate)
        } finally {
            extractor.release()
        }
    }

    /** Linear resampling to SAMPLE_RATE. */
    private fun resample(input: List<Short>, rate: Int): ShortArray {
        if (rate == SAMPLE_RATE || input.isEmpty()) return input.toShortArray()
        val count = (input.size.toLong() * SAMPLE_RATE / rate).toInt()
        return ShortArray(count) { index ->
            val position = index.toDouble() * rate / SAMPLE_RATE
            val left = position.toInt().coerceAtMost(input.size - 1)
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = position - left
            (input[left] * (1 - fraction) + input[right] * fraction).toInt().toShort()
        }
    }
}

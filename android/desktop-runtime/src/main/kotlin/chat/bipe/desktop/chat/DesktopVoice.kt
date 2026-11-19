// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.core.windowsDecodeVoice
import chat.bipe.core.windowsEncodeVoice
import chat.bipe.desktop.account.AccountTaskGroup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource

private const val RATE = 48_000
private const val MAX_SECONDS = 600
private const val MAX_PCM_BYTES = RATE * MAX_SECONDS * 4

class VoicePcm internal constructor(internal val bytes: ByteArray, val rate: Float, val channels: Int) {
    val durationMillis: Long get() = (bytes.size.toDouble() / (2 * channels) * 1000 / rate).toLong()
    private val format get() = AudioFormat(rate, 16, channels, true, false)
    internal fun line(): SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        try { open(format, (rate.toInt() * channels / 5).coerceAtLeast(4096)) }
        catch (error: Exception) { close(); throw error }
    }

    companion object {
        suspend fun decode(bytes: ByteArray): VoicePcm = withContext(Dispatchers.Default) {
            val ogg = bytes.size >= 4 && bytes[0] == 79.toByte() && bytes[1] == 103.toByte() &&
                bytes[2] == 103.toByte() && bytes[3] == 83.toByte()
            if (ogg) {
                val pcm = checkNotNull(windowsDecodeVoice(bytes))
                VoicePcm(pcm.pcm, RATE.toFloat(), pcm.channels.toInt())
            } else {
                AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { audio ->
                    val source = audio.format
                    check(source.channels in 1..2 && source.sampleRate in 8000f..96000f)
                    val pcmFormat = AudioFormat(source.sampleRate, 16, source.channels, true, false)
                    AudioSystem.getAudioInputStream(pcmFormat, audio).use { decoded ->
                        val data = decoded.readNBytes(MAX_PCM_BYTES + pcmFormat.frameSize)
                        check(data.isNotEmpty() && data.size <= MAX_PCM_BYTES)
                        VoicePcm(data, pcmFormat.sampleRate, pcmFormat.channels)
                    }
                }
            }
        }
    }
}

class VoiceCapture {
    private val stopping = AtomicBoolean()
    private val mutableDuration = MutableStateFlow(0L)
    val duration = mutableDuration.asStateFlow()

    fun stop() { stopping.set(true) }

    /** Runs only while the recording dialog owns the microphone. Neither PCM nor Ogg goes to disk. */
    suspend fun record(): VoicePcm = withContext(Dispatchers.IO) {
        val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
        val buffer = ByteArray(1920)
        val pcm = ByteArrayOutputStream()
        try {
            line.open(format)
            line.start()
            while (!stopping.get() && pcm.size() < RATE * MAX_SECONDS * 2) {
                currentCoroutineContext().ensureActive()
                val count = line.read(buffer, 0, buffer.size)
                check(count > 0)
                pcm.write(buffer, 0, count)
                mutableDuration.value = pcm.size().toLong() * 1000 / (RATE * 2)
            }
            VoicePcm(pcm.toByteArray(), RATE.toFloat(), 1)
        } finally {
            line.close()
            buffer.fill(0)
        }
    }
}

class VoiceSend internal constructor(private val owner: AccountTaskGroup, private val room: () -> Room,
                                     private val timeline: () -> Timeline, private val composer: RoomComposer) {
    suspend fun enqueue(recording: VoicePcm, reply: String?) = owner.async {
        check(recording.durationMillis >= 1000 && recording.rate == RATE.toFloat() && recording.channels == 1)
        val ogg = checkNotNull(windowsEncodeVoice(recording.bytes))
        check(room().membership() == Membership.JOINED)
        val elapsed = Duration.ofMillis(recording.durationMillis)
        val params = UploadParameters(UploadSource.Data(ogg, "voice-${elapsed.seconds}.ogg"), null, null, null, reply)
        val info = AudioInfo(elapsed, ogg.size.toULong(), "audio/ogg")
        val waveform = (0 until 64).map { slot ->
            val frames = recording.bytes.size / 2
            val start = slot * frames / 64
            val end = (slot + 1) * frames / 64
            var peak = 0
            for (frame in start until end step 16) peak = maxOf(peak, kotlin.math.abs(sample(recording.bytes, frame)))
            peak / 32768f
        }
        owner.requireCurrent()
        withContext(NonCancellable) {
            val handle = timeline().sendVoiceMessage(params, info, waveform)
            try { handle.join() } finally { handle.destroy() }
            if (reply != null) composer.acceptedAttachmentReply(reply)
        }
    }.await()
}

/** A single decoder/output session. Queue at most 100 ms to keep pause, seek and rate changes responsive. */
class VoiceOutput(private val pcm: VoicePcm) {
    enum class Status { PAUSED, PLAYING, FINISHED, FAILED }
    private data class Command(val playing: Boolean = false, val seek: Seek? = null, val speed: Float = 1f)
    private data class Seek(val fraction: Float)
    private val commands = MutableStateFlow(Command())
    private val mutablePosition = MutableStateFlow(0f)
    private val mutableStatus = MutableStateFlow(Status.PAUSED)
    val position = mutablePosition.asStateFlow()
    val status = mutableStatus.asStateFlow()

    fun play(value: Boolean) { commands.value = commands.value.copy(playing = value) }
    fun seek(fraction: Float) { commands.value = commands.value.copy(seek = Seek(fraction.coerceIn(0f, 1f))) }
    fun speed(value: Float) {
        require(value == 1f || value == 1.5f || value == 2f)
        commands.value = commands.value.copy(speed = value)
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        val line = pcm.line()
        val stretch = Stretch(pcm)
        var observed: Seek? = null
        var rate = 1f
        var playing = false
        var ended = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val command = commands.value
                if (command.seek !== observed) {
                    observed = command.seek
                    line.flush()
                    stretch.seek(command.seek?.fraction ?: 0f)
                    mutablePosition.value = stretch.position
                    ended = false
                }
                if (rate != command.speed) {
                    rate = command.speed
                    stretch.discontinuity()
                }
                if (playing != command.playing) {
                    playing = command.playing
                    if (playing) { line.start(); ended = false } else line.stop()
                }
                if (!playing) {
                    if (!ended) mutableStatus.value = Status.PAUSED
                    delay(15)
                    continue
                }
                mutableStatus.value = Status.PLAYING
                if (line.available() < stretch.chunkBytes) {
                    delay(8)
                    continue
                }
                val written = stretch.render(rate)
                if (written == 0) {
                    // A final partial buffer still needs to reach the speakers before Finished is published.
                    if (line.available() < line.bufferSize) { delay(8); continue }
                    line.stop()
                    mutableStatus.value = Status.FINISHED
                    mutablePosition.value = 1f
                    commands.compareAndSet(command, command.copy(playing = false))
                    playing = false
                    ended = true
                    continue
                }
                line.write(stretch.output, 0, written)
                mutablePosition.value = stretch.position
            }
        } finally { line.close() }
    }

    private class Stretch(private val pcm: VoicePcm) {
        private val frames = pcm.bytes.size / (pcm.channels * 2)
        private val hop = (pcm.rate / 100).toInt()
        private val tail = ShortArray(hop * pcm.channels)
        val chunkBytes = hop * pcm.channels * 2
        val output = ByteArray(chunkBytes)
        private var at = 0.0
        private var hasTail = false
        val position get() = if (frames == 0) 0f else (at / frames).coerceIn(0.0, 1.0).toFloat()

        fun seek(fraction: Float) { at = (fraction * frames).toDouble(); discontinuity() }
        fun discontinuity() { hasTail = false }

        /** Waveform-similarity overlap-add keeps the original sample rate and pitch at 1.5×/2×. */
        fun render(speed: Float): Int {
            val expected = at.toInt()
            val length = minOf(hop, frames - expected)
            if (length <= 0) return 0
            val start = if (speed > 1f && hasTail && expected + 2 * hop < frames) aligned(expected) else expected
            for (frame in 0 until length) for (channel in 0 until pcm.channels) {
                val index = frame * pcm.channels + channel
                val incoming = get(start + frame, channel)
                val value = if (speed <= 1f || !hasTail) incoming
                    else ((tail[index].toInt() * (hop - frame)) + incoming * frame) / hop
                output[index * 2] = value.toByte()
                output[index * 2 + 1] = (value shr 8).toByte()
            }
            if (speed > 1f) {
                for (frame in 0 until hop) for (channel in 0 until pcm.channels)
                    tail[frame * pcm.channels + channel] = get(start + hop + frame, channel).toShort()
                hasTail = true
            } else hasTail = false
            at = minOf(frames.toDouble(), at + length.toDouble() * speed)
            return length * pcm.channels * 2
        }

        private fun aligned(expected: Int): Int {
            val radius = (pcm.rate / 300).toInt()
            var best = expected
            var score = Double.NEGATIVE_INFINITY
            for (offset in -radius..radius step 8) {
                val start = expected + offset
                if (start < 0 || start + 2 * hop > frames) continue
                var similarity = 0.0
                var power = 1.0
                for (frame in 0 until hop step 8) {
                    val value = get(start + frame, 0).toDouble()
                    similarity += value * tail[frame * pcm.channels]
                    power += value * value
                }
                val normalized = similarity / kotlin.math.sqrt(power)
                if (normalized > score) { score = normalized; best = start }
            }
            return best
        }

        private fun get(frame: Int, channel: Int) = if (frame in 0 until frames)
            sample(pcm.bytes, frame * pcm.channels + channel) else 0
    }
}

private fun sample(bytes: ByteArray, index: Int): Int =
    ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort().toInt()

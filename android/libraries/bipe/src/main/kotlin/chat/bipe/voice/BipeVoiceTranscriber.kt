// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from bipe-android libraries/bipe voice/BipeVoiceTranscriber.kt (Bipe code): without the DI
// annotations and the logger of the fork.

package chat.bipe.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * On-device speech to text for voice messages (bipe/docs/audio.md, ADR 0023). Audio never leaves the phone.
 * Port of `VoiceTranscriber` in BipeKit on iOS. The recorder's PCM goes to the recognizer while the person records.
 */
fun interface BipeVoiceTranscriber {
    /**
     * A session for one recording, or `null` when this phone has no on-device recognizer that reads an audio stream
     * (Android 12 and older, or no on-device recognition service). The message then goes without a transcript.
     */
    fun startSession(scope: CoroutineScope, sampleRateHz: Int): Session?

    interface Session {
        /** 16-bit mono PCM, in recording order. */
        fun write(pcm: ShortArray)

        /** Ends the audio. The transcript, or `null` when nothing was recognised or the recogniser failed. */
        suspend fun finish(): String?

        fun cancel()
    }
}

/** The transcript of a recorded voice message, which the sender can correct before sending (ADR 0023). */
@Immutable
sealed interface BipeVoiceTranscriptDraft {
    data object Transcribing : BipeVoiceTranscriptDraft

    data class Ready(val text: String) : BipeVoiceTranscriptDraft

    data object Unavailable : BipeVoiceTranscriptDraft
}

/** The text for the event: only a finished transcript, never an empty string. A transcript still in progress is dropped. */
val BipeVoiceTranscriptDraft?.caption: String?
    get() = (this as? BipeVoiceTranscriptDraft.Ready)?.text?.trim()?.takeIf { it.isNotEmpty() }

class AndroidBipeVoiceTranscriber(
    private val context: Context,
) : BipeVoiceTranscriber {
    override fun startSession(scope: CoroutineScope, sampleRateHz: Int): BipeVoiceTranscriber.Session? {
        // EXTRA_AUDIO_SOURCE and segmented sessions came with Android 13.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        return OnDeviceSession(context, scope, sampleRateHz).apply { start() }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class OnDeviceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sampleRateHz: Int,
) : BipeVoiceTranscriber.Session {
    private val chunks = Channel<ShortArray>(Channel.UNLIMITED)
    private val result = CompletableDeferred<String?>()
    private val isClosed = AtomicBoolean(false)

    // [0] is read by the recogniser, [1] is written here.
    private val pipe: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()

    // Main thread only.
    private var recognizer: SpeechRecognizer? = null
    private val segments = mutableListOf<String>()

    fun start() {
        scope.launch(Dispatchers.IO) { writeChunks() }
        scope.launch(Dispatchers.Main) {
            val speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = speechRecognizer
            val language = installedLanguage(speechRecognizer)
            if (language == null || result.isCompleted) {
                complete(null)
                return@launch
            }
            speechRecognizer.setRecognitionListener(listener)
            speechRecognizer.startListening(intent(language))
        }
    }

    override fun write(pcm: ShortArray) {
        chunks.trySend(pcm)
    }

    override suspend fun finish(): String? {
        chunks.close()
        // The recogniser has the audio as it was recorded; the rest comes soon after the stream closes.
        return withTimeoutOrNull(FINISH_TIMEOUT) { result.await() } ?: run {
            cancel()
            null
        }
    }

    override fun cancel() {
        chunks.close()
        complete(null)
    }

    private suspend fun writeChunks() {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                // Ends when the channel closes: the stream closes and the recogniser ends the session.
                for (chunk in chunks) {
                    val bytes = ByteBuffer.allocate(chunk.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(chunk)
                    output.write(bytes.array())
                }
            }
        } catch (_: IOException) {
            // The read side closed: the session ended or failed.
        }
    }

    private val listener = object : RecognitionListener {
        override fun onSegmentResults(segmentResults: Bundle) = addBest(segmentResults)

        override fun onEndOfSegmentedSession() = complete(TranscriptParagraphs.layout(TranscriptParagraphs.pieces(segments)))

        override fun onResults(results: Bundle) {
            // A recogniser without segmented sessions ends with one result.
            addBest(results)
            complete(TranscriptParagraphs.layout(TranscriptParagraphs.pieces(segments)))
        }

        override fun onError(error: Int) {
            complete(TranscriptParagraphs.layout(TranscriptParagraphs.pieces(segments)))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun addBest(bundle: Bundle) {
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(segments::add)
    }

    private fun complete(text: String?) {
        result.complete(text?.trim()?.takeIf { it.isNotEmpty() })
        if (!isClosed.compareAndSet(false, true)) return
        try {
            pipe[0].close()
        } catch (_: IOException) {
            // Already closed.
        }
        scope.launch(Dispatchers.Main) {
            recognizer?.destroy()
            recognizer = null
        }
    }

    /** The first of the phone's languages with an installed on-device model, then pt-BR. No language detection, as on iOS. */
    private suspend fun installedLanguage(speechRecognizer: SpeechRecognizer): String? {
        val installed = suspendCancellableCoroutine { continuation ->
            speechRecognizer.checkRecognitionSupport(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        continuation.resume(recognitionSupport.installedOnDeviceLanguages)
                    }

                    override fun onError(error: Int) {
                        continuation.resume(emptyList())
                    }
                },
            )
        }
        val candidates = LocaleList.getDefault().toLanguageTags().split(",") + PT_BR
        return candidates.firstNotNullOfOrNull { candidate ->
            installed.firstOrNull { it.equals(candidate, ignoreCase = true) }
                ?: installed.firstOrNull { it.substringBefore('-').equals(candidate.substringBefore('-'), ignoreCase = true) }
        }
    }

    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        // Fail closed: on-device only.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
        // One session for the whole recording: it ends when the audio stream closes.
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
    }

    private companion object {
        const val PT_BR = "pt-BR"
        val FINISH_TIMEOUT = 30.seconds
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.voice

import chat.bipe.shared.generated.BipeRegistries

/**
 * Paragraphs in a voice transcript (registries/voice-transcript.json; the rule is in the notes of spec voice-recording).
 * Port of `TranscriptParagraphs` on iOS. The recogniser gives one segment per stretch of speech; sentence ends and the
 * silences between segments split them. The rule never changes words.
 */
object TranscriptParagraphs {
    /** [pause]: the silence before the piece, in seconds. 0 for the first piece. */
    data class Piece(val text: String, val pause: Double)

    /**
     * The segments of a segmented session as pieces. The recogniser gives no timestamps, so the silence before a
     * segment counts as pauseSeconds. Without formatting (Android 13) no segment has sentence punctuation: then each
     * segment ends with a full stop.
     */
    fun pieces(segments: List<String>): List<Piece> {
        val punctuated = segments.any { segment -> segment.any { it in SENTENCE_ENDS } }
        return segments.mapIndexed { index, segment ->
            val text = if (punctuated || endsSentence(segment)) segment else "${segment.trimEnd()}."
            Piece(
                text = if (index == 0) text else " $text",
                pause = if (index == 0) 0.0 else BipeRegistries.VOICE_TRANSCRIPT_PAUSE_SECONDS,
            )
        }
    }

    fun layout(pieces: List<Piece>): String {
        val pause = BipeRegistries.VOICE_TRANSCRIPT_PAUSE_SECONDS
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            val length = current.trim().length
            if (length > 0) {
                val ends = endsSentence(current)
                val byPause = length >= BipeRegistries.VOICE_TRANSCRIPT_MIN_CHARACTERS &&
                    ((piece.pause >= pause && ends) || piece.pause >= 2 * pause)
                val byLength = length >= BipeRegistries.VOICE_TRANSCRIPT_MAX_CHARACTERS && ends
                if (byPause || byLength) {
                    paragraphs += current.toString()
                    current.clear()
                }
            }
            current.append(piece.text)
        }
        paragraphs += current.toString()
        return paragraphs.map(::clean).filter { it.isNotEmpty() }.joinToString(BipeRegistries.VOICE_TRANSCRIPT_SEPARATOR)
    }

    private fun endsSentence(text: CharSequence): Boolean = text.trim().lastOrNull()?.let { it in SENTENCE_ENDS } ?: false

    /** One space between words, a capital letter first, a full stop when the paragraph has no sentence end. */
    private fun clean(paragraph: String): String {
        val text = paragraph.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isEmpty()) return ""
        val capitalised = text.replaceFirstChar { it.uppercase() }
        return if (endsSentence(capitalised)) capitalised else "$capitalised."
    }

    private const val SENTENCE_ENDS = ".!?…"
    private val WHITESPACE = Regex("\\s+")
}

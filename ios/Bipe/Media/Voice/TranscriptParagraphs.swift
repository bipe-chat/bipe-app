// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Speech

/// Paragraphs in a voice transcript (registries/voice-transcript.json; the rule is in the notes of spec
/// voice-recording). The recogniser gives one long run of text; silences and sentence ends split it.
/// The rule never changes words.
enum TranscriptParagraphs {
    struct Piece: Equatable {
        let text: String
        /// The silence before the piece, in seconds. 0 for the first piece.
        let pause: Double
    }

    /// The formatted text of the transcription as one piece per word, with the silence before each word
    /// from the word timestamps. Ranges that do not fit the text give one piece: the text without paragraphs.
    static func pieces(of transcription: SFTranscription) -> [Piece] {
        let text = transcription.formattedString as NSString
        let segments = transcription.segments
        let whole = [Piece(text: transcription.formattedString, pause: 0)]
        guard !segments.isEmpty else { return whole }
        var pieces: [Piece] = []
        for (index, segment) in segments.enumerated() {
            let start = index == 0 ? 0 : segment.substringRange.location
            let end = index + 1 < segments.count ? segments[index + 1].substringRange.location : text.length
            guard start <= end, end <= text.length else { return whole }
            let previous = index == 0 ? nil : segments[index - 1]
            let pause = previous.map { max(0, segment.timestamp - ($0.timestamp + $0.duration)) } ?? 0
            pieces.append(Piece(text: text.substring(with: NSRange(location: start, length: end - start)), pause: pause))
        }
        return pieces
    }

    static func layout(_ pieces: [Piece]) -> String {
        let pause = BipeRegistries.voiceTranscriptPauseSeconds
        var paragraphs: [String] = []
        var current = ""
        for piece in pieces {
            let length = current.trimmingCharacters(in: .whitespacesAndNewlines).count
            if length > 0 {
                let ends = endsSentence(current)
                let byPause = length >= BipeRegistries.voiceTranscriptMinCharacters
                    && ((piece.pause >= pause && ends) || piece.pause >= 2 * pause)
                let byLength = length >= BipeRegistries.voiceTranscriptMaxCharacters && ends
                if byPause || byLength {
                    paragraphs.append(current)
                    current = ""
                }
            }
            current += piece.text
        }
        paragraphs.append(current)
        return paragraphs.map(clean).filter { !$0.isEmpty }.joined(separator: BipeRegistries.voiceTranscriptSeparator)
    }

    private static func endsSentence(_ text: String) -> Bool {
        guard let last = text.trimmingCharacters(in: .whitespacesAndNewlines).last else { return false }
        return ".!?…".contains(last)
    }

    /// One space between words, a capital letter first, a full stop when the paragraph has no sentence end.
    private static func clean(_ paragraph: String) -> String {
        let text = paragraph.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard let first = text.first else { return "" }
        let capitalised = first.uppercased() + text.dropFirst()
        return endsSentence(capitalised) ? capitalised : capitalised + "."
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Speech

/// The sender-side transcript of a voice message (ADR 0023): Apple Speech on the device only, never a
/// server. Language: the first of the phone's preferred languages that the phone transcribes offline,
/// then pt-BR; no language detection (spec voice-recording notes).
enum VoiceTranscriber {
    /// A recogniser that works offline, or nil: then the message goes without a transcript.
    static func recognizer() -> SFSpeechRecognizer? {
        let candidates = Locale.preferredLanguages.map { Locale(identifier: $0) } + [Locale(identifier: "pt-BR")]
        for locale in candidates {
            if let recognizer = SFSpeechRecognizer(locale: locale), recognizer.supportsOnDeviceRecognition, recognizer.isAvailable {
                return recognizer
            }
        }
        return nil
    }

    /// Asks for speech recognition the first time. false: no transcript.
    static func authorize() async -> Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return true
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
            }
        default:
            return false
        }
    }

    /// The transcript of the recorded file, or nil when nothing was recognised or recognition failed.
    static func transcribe(_ file: URL, with recognizer: SFSpeechRecognizer) async -> String? {
        let request = SFSpeechURLRecognitionRequest(url: file)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        request.addsPunctuation = true
        return await withCheckedContinuation { continuation in
            var resumed = false
            let lock = NSLock()
            func finish(_ text: String?) {
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                continuation.resume(returning: text)
            }
            recognizer.recognitionTask(with: request) { result, error in
                if let result, result.isFinal {
                    finish(TranscriptParagraphs.layout(TranscriptParagraphs.pieces(of: result.bestTranscription)).nilIfBlank)
                } else if error != nil {
                    finish(nil)
                }
            }
        }
    }
}

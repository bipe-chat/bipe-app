// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Speech

/// Spec voice-message, action transcribe (bipe-chat/bipe#500): the listener's own transcript of a received voice
/// message, made on the phone only (Apple Speech with requiresOnDeviceRecognition). The audio and the text never
/// leave the phone; the text lives in memory for the session.
@MainActor
final class ListenerTranscripts: ObservableObject {
    static let shared = ListenerTranscripts()

    /// State localTranscript of spec voice-message.
    enum State: Equatable {
        case none
        case working
        case ready(String)
        case unavailable
        case denied
        case failed
    }

    @Published private(set) var states: [String: State] = [:]

    func state(of id: String) -> State {
        states[id] ?? .none
    }

    /// Action transcribe: the decoded audio file comes from the player's cache.
    func transcribe(id: String, file: @escaping () async throws -> URL) async {
        guard state(of: id) != .working else { return }
        states[id] = .working
        guard await VoiceTranscriber.authorize() else {
            states[id] = .denied
            return
        }
        guard let recognizer = VoiceTranscriber.recognizer() else {
            states[id] = .unavailable
            return
        }
        guard let url = try? await file(), let text = await VoiceTranscriber.transcribe(url, with: recognizer) else {
            states[id] = .failed
            return
        }
        states[id] = .ready(text)
    }
}

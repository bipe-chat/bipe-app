// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// An audio message on the Mac. Playback is bipe-chat/bipe#1220 (paused by ADR 0288); until then the row names the
/// message, shows its length and says to listen on the phone (bipe-chat/bipe#1507).
struct MacVoice: Equatable {
    /// The MSC3245 voice marker: a recorded voice message, not an audio file.
    let isVoice: Bool
    let filename: String
    let duration: TimeInterval?

    /// A voice message never shows its file name (voice-<seconds>.ogg); an audio file does, as on web.
    var title: String { isVoice || filename.isEmpty ? BipeStrings.appMediaVoice : filename }
    var durationLabel: String? { duration.flatMap { $0 > 0 ? MediaFormat.duration($0) : nil } }
}

struct MacVoiceView: View {
    let voice: MacVoice

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                Image(decorative: "microphone").renderingMode(.template).resizable().scaledToFit().frame(width: 24, height: 24)
                    .foregroundStyle(BipeColor.accentInk)
                VStack(alignment: .leading, spacing: 2) {
                    Text(voice.title).macFont(.body).fontWeight(.semibold).lineLimit(2)
                    if let length = voice.durationLabel {
                        Text(length).macFont(.caption).monospacedDigit().foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
            Text(BipeStrings.appMediaVoiceOnPhone).macFont(.caption).foregroundStyle(BipeColor.inkSecondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(voice.title)
        .accessibilityValue([voice.durationLabel.map(BipeStrings.appVoiceDuration), BipeStrings.appMediaVoiceOnPhone]
            .compactMap { $0 }.joined(separator: ", "))
    }
}

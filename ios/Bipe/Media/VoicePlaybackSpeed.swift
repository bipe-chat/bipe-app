// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Also compiled into the Mac app (macos/project.yml): keep it free of app-only types.

/// State speed of spec voice-message.
enum VoicePlaybackSpeed: String, CaseIterable {
    case normal
    case oneAndHalf
    case double

    static let key = "voicePlaybackSpeed"

    var rate: Float {
        switch self {
        case .normal: 1
        case .oneAndHalf: 1.5
        case .double: 2
        }
    }

    /// Action cycleSpeed: normal, oneAndHalf, double, then normal again.
    var next: VoicePlaybackSpeed {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }

    /// The number with the phone's decimal sign ("1,5" in pt-BR), for bipe_voice_speed.
    var label: String {
        let formatter = NumberFormatter()
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 1
        return formatter.string(from: NSNumber(value: rate)) ?? String(rate)
    }
}

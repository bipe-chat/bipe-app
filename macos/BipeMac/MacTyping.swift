// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// Spec typing-indicator sharesTyping: the privacy row privacy.presence. On unless turned off. The key is the iOS key
/// (SettingsKeys.sharePresence), so the meaning stays the same. It is a setting of this device, as on the phones.
enum MacPresence {
    static let key = "bipe.settings.sharePresence"
    static func sharesTyping(_ defaults: UserDefaults = .standard) -> Bool { defaults.object(forKey: key) as? Bool ?? true }

    /// Spec typing-indicator notes: one name, two names, or the first name and others.
    static func text(_ names: [String]) -> String? {
        switch names.count {
        case 0: nil
        case 1: BipeStrings.appTypingOne(names[0])
        case 2: BipeStrings.appTypingTwo(names[0], names[1])
        default: BipeStrings.appTypingMany(names[0])
        }
    }
}

/// Spec typing-indicator: the line above the composer. Screen readers hear it once when it appears.
struct MacTypingLine: View {
    let names: [String]

    var body: some View {
        if let text = MacPresence.text(names) {
            HStack(spacing: 6) {
                MacTypingDots()
                Text(text).macFont(.caption).foregroundStyle(BipeColor.inkSecondary).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
            .onAppear {
                NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                                     userInfo: [.announcement: text, .priority: NSAccessibilityPriorityLevel.medium.rawValue])
            }
        }
    }
}

/// The calm three dots of the typing line. They stay still with Reduce Motion.
private struct MacTypingDots: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 15, paused: reduceMotion)) { context in
            let phase = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.2) / 1.2 * .pi * 2
            HStack(spacing: 3) {
                ForEach(0..<3) { index in
                    Circle()
                        .fill(BipeColor.glyphSecondary)
                        .frame(width: 5, height: 5)
                        .opacity(reduceMotion ? 0.7 : 0.35 + 0.65 * max(0, sin(phase - Double(index) * 0.8)))
                }
            }
        }
        .accessibilityHidden(true)
    }
}

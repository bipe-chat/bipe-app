// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The two pills of the timeline (spec timeline-rows). Both follow the app theme, and the outline and weight tell them apart without colour.
enum TimelinePillKind {
    /// Information or grouping: date separators, the chat start and the not-encrypted notice.
    case info
    /// Something that happened or can be done: calls, state lines, handle changes and the floating date button.
    case action
}

extension View {
    func timelinePill(_ kind: TimelinePillKind) -> some View {
        timelinePillType()
            .fontWeight(kind == .action ? .semibold : .regular)
            .foregroundStyle(BipeColor.accentInk)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(kind == .action ? BipeColor.accentWash : BipeColor.bubbleOwn))
            .overlay {
                if kind == .action { Capsule().stroke(BipeColor.accentFill) }
            }
    }

    #if os(macOS)
    private func timelinePillType() -> some View { macFont(.caption) }
    #else
    private func timelinePillType() -> some View { font(.caption) }
    #endif
}

/// A centred pill between the messages of the timeline.
struct SystemLine: View {
    let text: String
    var kind: TimelinePillKind = .info

    var body: some View {
        Text(text)
            .timelinePill(kind)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
    }
}

/// Spec timeline-rows notes: today, yesterday, else the system date in the app language, with the year only when it is not this year.
func daySeparatorText(_ day: Date) -> String {
    let calendar = Calendar.current
    if calendar.isDateInToday(day) { return BipeStrings.appTimelineToday }
    if calendar.isDateInYesterday(day) { return BipeStrings.appTimelineYesterday }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "pt-BR")
    let sameYear = calendar.component(.year, from: day) == calendar.component(.year, from: Date())
    formatter.setLocalizedDateFormatFromTemplate(sameYear ? "EEEEdMMMM" : "dMMMMyyyy")
    return formatter.string(from: day)
}

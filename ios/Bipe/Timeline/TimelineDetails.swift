// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec message-reply. The Apple clients share the quote states, names and visual treatment.
struct MessageReply: Equatable {
    enum State: Equatable { case loading, ready, unavailable }
    var eventId: String
    var state: State
    var senderName: String?
    var preview: String?
    /// Untruncated source text and link destinations, when the renderer supplies them.
    var originalText: String? = nil

    var safetyText: String {
        guard state == .ready else { return "" }
        return [senderName, preview, originalText].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    static func make(_ details: InReplyToDetails, ownUserID: String, loadTried: Bool,
                     name: (String) -> String, preview: (TimelineItemContent, Bool) -> String,
                     sourceText: ((TimelineItemContent) -> String)? = nil) -> MessageReply {
        let eventId = details.eventId()
        switch details.event() {
        case .ready(let content, let sender, let profile, _, _):
            var realName: String?
            if case .ready(let displayName, _, _, _, _) = profile {
                realName = BipeChatRowName.realName(displayName, userID: sender)
            }
            return MessageReply(eventId: eventId, state: .ready,
                                senderName: sender == ownUserID ? BipeStrings.appYou : realName ?? name(sender),
                                preview: preview(content, sender == ownUserID), originalText: sourceText?(content))
        case .error: return MessageReply(eventId: eventId, state: .unavailable)
        case .unavailable: return MessageReply(eventId: eventId, state: loadTried ? .unavailable : .loading)
        case .pending: return MessageReply(eventId: eventId, state: .loading)
        }
    }
}

/// Spec message-reply: a short quote inside the reply bubble, with the shared theme tokens.
struct ReplyQuote: View {
    let reply: MessageReply

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 1.5).fill(BipeColor.accentFill).frame(width: 3)
            VStack(alignment: .leading, spacing: 2) {
                switch reply.state {
                case .loading:
                    Text(BipeStrings.appMessageReplyLoading).foregroundStyle(BipeColor.inkSecondary)
                case .unavailable:
                    Text(BipeStrings.appMessageReplyUnavailable).foregroundStyle(BipeColor.inkSecondary)
                case .ready:
                    Text(reply.senderName ?? "").bold().foregroundStyle(BipeColor.accentInk)
                    Text(reply.preview ?? "").foregroundStyle(BipeColor.inkSecondary).lineLimit(1)
                }
            }
            .timelineDetailsFont(.caption)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 8).fill(BipeColor.surfaceSunken.opacity(0.6)))
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Every event keeps its original clock time; accessibility also exposes its full date.
struct MessageTimestamp: View {
    let sentAt: Date

    var body: some View {
        Text(sentAt.formatted(date: .omitted, time: .shortened))
            .timelineDetailsFont(.caption2).monospacedDigit()
            .foregroundStyle(BipeColor.inkSecondary)
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityLabel(sentAt.formatted(date: .complete, time: .shortened))
    }
}

struct TimelineReadMarker: View {
    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(BipeColor.accentFill).frame(height: 1)
            Text(BipeStrings.appTimelineNewMessages).timelineDetailsFont(.caption).bold()
                .foregroundStyle(BipeColor.accentInk)
            Rectangle().fill(BipeColor.accentFill).frame(height: 1)
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(BipeStrings.appTimelineNewMessages)
        .accessibilityAddTraits(.isHeader)
    }
}

private enum TimelineDetailFont: Equatable { case caption, caption2 }

private extension View {
    @ViewBuilder func timelineDetailsFont(_ style: TimelineDetailFont) -> some View {
        #if os(macOS)
        macFont(style == .caption ? .caption : .caption2)
        #else
        font(style == .caption ? .caption : .caption2)
        #endif
    }
}

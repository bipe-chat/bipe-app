// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Phosphor
import SwiftUI

/// An official notice from a verified institution (spec chat-timeline, bipe#245): a card, not a chat bubble.
/// The institution comes from its signed badge only. The safety line is fixed; the sender cannot change it.
struct NoticeCard: View {
    let message: TimelineMessage
    let institution: BadgePayload

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                PhosphorIcon.sealCheck.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.trustVerifiedInk)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(institution.name)
                        .font(.subheadline.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(BipeStrings.noticeLevel)
                        .font(.caption)
                        .foregroundStyle(BipeColor.trustVerifiedInk)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(BipeStrings.noticeA11y(institution.name))
            Text(BipeStrings.noticeLabel)
                .font(.caption.bold())
                .foregroundStyle(BipeColor.inkSecondary)
            Text(OfficialNoticeText.attributed(message.body ?? "", domains: institution.domains))
                .foregroundStyle(BipeColor.inkPrimary)
                .tint(BipeColor.accentInk)
            Divider()
            Text(BipeStrings.noticeSafety)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            Text(message.sentAt.formatted(date: .omitted, time: .shortened))
                .font(.caption2)
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustVerifiedWash))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(BipeColor.trustVerifiedLine, lineWidth: 2))
        .accessibilityElement(children: .contain)
    }
}

/// The notice body with a link only where BipeNoticeLinks allows it (ADR 0036). Every other link stays plain text.
enum OfficialNoticeText {
    static func attributed(_ body: String, domains: [String]) -> AttributedString {
        var text = AttributedString(body)
        guard !domains.isEmpty,
              let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return text
        }
        let whole = NSRange(body.startIndex..., in: body)
        for match in detector.matches(in: body, range: whole) {
            guard let range = Range(match.range, in: body) else { continue }
            let raw = String(body[range])
            guard BipeNoticeLinks.isClickable(raw, domains: domains), let url = URL(string: raw),
                  let lower = AttributedString.Index(range.lowerBound, within: text),
                  let upper = AttributedString.Index(range.upperBound, within: text) else { continue }
            text[lower..<upper].link = url
        }
        return text
    }
}

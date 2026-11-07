// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-request and person-profile profileFacts (ADR 0049): account age, photo and name changes of a person.
/// Reads POST /v1/profile-facts once when it shows. No lines or a failed call: nothing shows.
struct ProfileFactsLines: View {
    let people: PeopleService
    let userId: String
    var alignment: TextAlignment = .center
    /// person-profile keeps the account age at any age (ADR 0275 point 13).
    var alwaysCreated = false
    @State private var texts: [String] = []

    var body: some View {
        VStack(spacing: 2) {
            ForEach(Array(texts.enumerated()), id: \.offset) { _, text in
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(alignment)
            }
        }
        .accessibilityElement(children: .combine)
        .task(id: userId) {
            guard !userId.isEmpty, let facts = await people.profileFacts(userId) else { return }
            texts = BipeProfileFactLines(facts: facts, alwaysCreated: alwaysCreated).texts()
        }
    }
}

/// Spec chat-timeline profileNotice (ADR 0049): an amber bar above the composer of a 1:1 chat when the other
/// person changed name and photo close together, recently. Reads POST /v1/profile-facts at most once an hour.
/// It never hides a message; a failed call shows nothing.
struct ProfileNoticeBar: View {
    let people: PeopleService
    let userId: String
    let name: String
    @State private var notice: String?

    /// Spec chat-timeline: at most one read an hour for the same person.
    static let maxAge: TimeInterval = 3600

    var body: some View {
        // A VStack, not a Group: the task must run while the bar is hidden.
        VStack(spacing: 0) {
            if let notice {
                HStack(alignment: .top, spacing: 8) {
                    PhosphorIcon.warning.image
                        .resizable()
                        .frame(width: 18, height: 18)
                        .foregroundStyle(BipeColor.trustCautionInk)
                        .accessibilityHidden(true)
                    Text(notice)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(BipeColor.trustCautionWash)
                .overlay(alignment: .top) {
                    Rectangle().fill(BipeColor.trustCautionLine).frame(height: 1)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .task(id: userId) {
            guard let facts = await people.profileFacts(userId, maxAge: Self.maxAge) else {
                notice = nil
                return
            }
            notice = BipeProfileFactLines(facts: facts).noticeText(name: name)
        }
    }
}

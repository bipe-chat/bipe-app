// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec question-card (bipe-chat/bipe#244, ADR 0081): a company's structured question with large options and one tap
/// to answer. The card shows no counts: the company reads the answers.
struct QuestionCard: View {
    let poll: PollData
    let companyId: String
    let companyName: String
    let seal: BipeCompanySealState
    @ObservedObject var model: ChatTimelineModel

    @State private var sendingId: String?
    @State private var error: String?
    @State private var showsSeal = false

    /// At least 56 points per option (spec question-card notes), larger with the text size.
    @ScaledMetric(relativeTo: .body) private var optionHeight: CGFloat = 56

    private var chosen: PollData.Option? {
        poll.options.first { $0.answerId == poll.yourVote }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button { showsSeal = true } label: {
                HStack(spacing: 6) {
                    PhosphorIcon.sealCheck.labelledImage(BipeStrings.badgeCompany)
                        .resizable()
                        .frame(width: 18, height: 18)
                        .foregroundStyle(BipeColor.trustVerifiedInk)
                    Text(BipeStrings.appQuestionFrom(companyName))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.leading)
                }
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isHeader)

            Text(poll.question)
                .font(.title3.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
                .fixedSize(horizontal: false, vertical: true)

            ForEach(poll.options) { option in
                optionButton(option)
            }

            VStack(alignment: .leading, spacing: 2) {
                if poll.isEnded {
                    Text(BipeStrings.appQuestionClosed)
                } else if let chosen {
                    Text(BipeStrings.appQuestionYourAnswer(chosen.text))
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(BipeStrings.appQuestionChangeHint)
                } else {
                    Text(BipeStrings.appQuestionHint)
                }
            }
            .font(.callout)
            .foregroundStyle(BipeColor.inkSecondary)
            .fixedSize(horizontal: false, vertical: true)

            if let error {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
        }
        .accessibilityIdentifier(BipeScreen.questionCard.rawValue)
        .sheet(isPresented: $showsSeal) {
            CompanySealSheet(userId: companyId, state: seal)
        }
    }

    private func optionButton(_ option: PollData.Option) -> some View {
        let isChosen = option.isYourVote
        return Button {
            Task { await answer(option.answerId) }
        } label: {
            HStack(spacing: 10) {
                Text(option.text)
                    .font(.body.weight(isChosen ? .semibold : .regular))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if sendingId == option.answerId {
                    ProgressView()
                } else if isChosen {
                    PhosphorIcon.checkCircle.image
                        .resizable()
                        .frame(width: 22, height: 22)
                }
            }
            .foregroundStyle(isChosen ? BipeColor.inkOnAccent : BipeColor.inkPrimary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: optionHeight, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(isChosen ? BipeColor.accentFill : BipeColor.surfaceRaised))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(isChosen ? BipeColor.accentFill : BipeColor.inkPrimary, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(poll.isEnded || sendingId != nil)
        .accessibilityLabel(option.text)
        .accessibilityAddTraits(isChosen ? .isSelected : [])
    }

    /// Action answer. Tapping the chosen option again does nothing; a failure keeps the old answer.
    private func answer(_ answerId: String) async {
        guard !poll.isEnded, answerId != poll.yourVote, sendingId == nil else { return }
        error = nil
        sendingId = answerId
        defer { sendingId = nil }
        do {
            try await model.vote(eventId: poll.eventId, answerId: answerId)
        } catch {
            self.error = BipeStrings.appPollVoteError
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// The poll card in chat-timeline (spec poll-detail notes): the question, the options, the user's vote and, when
/// results are visible, the counts. Tapping it opens poll-detail.
struct PollCard: View {
    let poll: PollData

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                PhosphorIcon.chartBar.image
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundStyle(BipeColor.accentInk)
                    .accessibilityHidden(true)
                Text(poll.isEnded ? BipeStrings.appPollEnded : BipeStrings.appPollTitle)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.accentInk)
            }
            Text(poll.question)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            ForEach(poll.options) { option in
                PollOptionLine(option: option, total: poll.totalVotes)
            }
            Text(footer)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
        }
    }

    private var footer: String {
        if let total = poll.totalVotes {
            return BipeStrings.appPollVotes(total.formatted())
        }
        return BipeStrings.appPollResultsHidden
    }
}

/// One option with its bar: the share of votes when results are visible.
struct PollOptionLine: View {
    let option: PollData.Option
    let total: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(option.text)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer(minLength: 8)
                if option.isYourVote {
                    PhosphorIcon.check.image
                        .resizable()
                        .frame(width: 14, height: 14)
                        .foregroundStyle(BipeColor.accentInk)
                        .accessibilityLabel(BipeStrings.appPollYourVote)
                }
                if let count = option.voteCount {
                    Text(count.formatted())
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            if let count = option.voteCount, let total, total > 0 {
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(BipeColor.surfaceSunken)
                        Capsule()
                            .fill(option.isWinner || option.isYourVote ? BipeColor.accentFill : BipeColor.glyphSecondary)
                            .frame(width: proxy.size.width * CGFloat(count) / CGFloat(total))
                    }
                }
                .frame(height: 6)
            }
        }
    }
}

/// Spec poll-create. Opened from the composer.
struct PollCreateView: View {
    @ObservedObject var timeline: ChatTimelineModel
    @Environment(\.dismiss) private var dismiss
    @State private var question = ""
    @State private var options = ["", ""]
    @State private var hideResults = false
    @State private var sending = false
    @State private var failed = false

    static let maxOptions = 20
    static let minOptions = 2

    private var filledOptions: [String] {
        options.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
    }

    private var canSend: Bool {
        !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && filledOptions.count >= Self.minOptions && !sending
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(BipeStrings.appPollQuestionPlaceholder, text: $question, axis: .vertical)
                } header: {
                    Text(BipeStrings.appPollQuestion)
                }
                Section {
                    ForEach(options.indices, id: \.self) { index in
                        HStack {
                            TextField(BipeStrings.appPollOption((index + 1).formatted()), text: $options[index])
                            if options.count > Self.minOptions {
                                Button {
                                    options.remove(at: index)
                                } label: {
                                    PhosphorIcon.x.image.resizable().frame(width: 16, height: 16)
                                        .foregroundStyle(BipeColor.glyphSecondary)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(BipeStrings.appPollRemoveOption)
                            }
                        }
                    }
                    if options.count < Self.maxOptions {
                        Button {
                            options.append("")
                        } label: {
                            Label {
                                Text(BipeStrings.appPollAddOption)
                            } icon: {
                                PhosphorIcon.plus.image.resizable().frame(width: 18, height: 18)
                            }
                        }
                    }
                }
                Section {
                    Toggle(BipeStrings.appPollHideResults, isOn: $hideResults)
                } footer: {
                    Text(BipeStrings.appPollHideResultsFooter)
                }
                if failed {
                    Section {
                        Text(BipeStrings.appPollSendError)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .disabled(sending)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.appPollCreateTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if sending {
                        ProgressView()
                    } else {
                        Button(BipeStrings.appTimelineSend) {
                            Task { await send() }
                        }
                        .disabled(!canSend)
                    }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.pollCreate.rawValue)
    }

    /// Action send.
    private func send() async {
        sending = true
        failed = false
        do {
            try await timeline.createPoll(question: question.trimmingCharacters(in: .whitespacesAndNewlines),
                                          answers: filledOptions,
                                          hideResults: hideResults)
            dismiss()
        } catch {
            failed = true
        }
        sending = false
    }
}

/// Spec poll-detail. Follows the timeline, so new votes show live.
struct PollDetailView: View {
    @ObservedObject var timeline: ChatTimelineModel
    let session: AccountSession
    let eventId: String
    @Environment(\.dismiss) private var dismiss
    @State private var names: [String: String] = [:]
    @State private var confirmingEnd = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if let poll = timeline.poll(eventId: eventId) {
                    content(poll)
                } else {
                    ProgressView()
                }
            }
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.appPollTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.pollDetail.rawValue)
        .alert(error ?? "", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .task { await loadNames() }
    }

    private func content(_ poll: PollData) -> some View {
        List {
            Section {
                Text(poll.question)
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                if poll.isEnded {
                    Text(BipeStrings.appPollEnded)
                        .foregroundStyle(BipeColor.inkSecondary)
                } else if !poll.resultsVisible {
                    Text(BipeStrings.appPollResultsHidden)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Section {
                ForEach(poll.options) { option in
                    Button {
                        Task { await vote(poll, option.answerId) }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            PollOptionLine(option: option, total: poll.totalVotes)
                            if option.isYourVote {
                                Text(BipeStrings.appPollYourVote)
                                    .font(.caption)
                                    .foregroundStyle(BipeColor.accentInk)
                            }
                            if option.isWinner {
                                Text(BipeStrings.appPollMostVotes)
                                    .font(.caption.bold())
                                    .foregroundStyle(BipeColor.accentInk)
                            }
                            if !option.voterIds.isEmpty {
                                Text(option.voterIds.map { names[$0] ?? $0 }.joined(separator: ", "))
                                    .font(.caption)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                        }
                    }
                    .disabled(poll.isEnded)
                    .accessibilityAddTraits(option.isYourVote ? .isSelected : [])
                }
            } footer: {
                if let total = poll.totalVotes {
                    Text(BipeStrings.appPollVotes(total.formatted()))
                }
            }
            if poll.canEnd {
                Section {
                    Button(BipeStrings.appPollEnd, role: .destructive) {
                        confirmingEnd = true
                    }
                }
            }
        }
        .confirmationDialog(BipeStrings.appPollEndConfirm, isPresented: $confirmingEnd, titleVisibility: .visible) {
            Button(BipeStrings.appPollEnd, role: .destructive) {
                Task { await end(poll) }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
    }

    /// Action vote. Tapping the current vote again does nothing.
    private func vote(_ poll: PollData, _ answerId: String) async {
        guard !poll.isEnded, poll.yourVote != answerId else { return }
        do {
            try await timeline.vote(eventId: poll.eventId, answerId: answerId)
        } catch {
            self.error = BipeStrings.appPollVoteError
        }
    }

    /// Action end.
    private func end(_ poll: PollData) async {
        try? await timeline.endPoll(eventId: poll.eventId, text: BipeStrings.appPollEndedText)
    }

    /// Voters show by name (Person.displayName).
    private func loadNames() async {
        guard let room = try? session.client.getRoom(roomId: timeline.roomId),
              let members = await GroupPeople.members(of: room, cachedOnly: true) else { return }
        var names: [String: String] = [:]
        for member in members {
            names[member.userId] = Person(member: member).displayName
        }
        self.names = names
    }
}

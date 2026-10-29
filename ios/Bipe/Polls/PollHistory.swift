// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec poll-history (bipe#669): the polls of a group, newest first.
@MainActor
final class PollHistoryModel: ObservableObject {
    /// Action appear loads pages until this many polls or the start of the group.
    static let firstFill = 20

    @Published private(set) var polls: [PollData] = []
    @Published private(set) var isLoading = true
    @Published private(set) var reachedStart = false

    private let session: AccountSession
    private let roomId: String
    private var timeline: Timeline?
    private var handle: TaskHandle?
    private var items: [TimelineItem] = []
    private var paging = false

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    /// Action appear.
    func appear() async {
        guard timeline == nil, let room = try? session.client.getRoom(roomId: roomId) else {
            isLoading = false
            return
        }
        let filter = TimelineEventFilter.includeEventTypes(eventTypes: [.messageLike(eventType: .pollStart)])
        let configuration = TimelineConfiguration(focus: .live(hideThreadedEvents: false),
                                                  filter: .eventFilter(filter: filter),
                                                  internalIdPrefix: "polls",
                                                  dateDividerMode: .daily,
                                                  trackReadReceipts: .disabled,
                                                  reportUtds: false)
        guard let timeline = try? await room.timelineWithConfiguration(configuration: configuration) else {
            isLoading = false
            return
        }
        self.timeline = timeline
        let stream = TimelineDiffStream()
        handle = await timeline.addListener(listener: stream)
        Task { [weak self] in
            guard let self else { return }
            while !reachedStart, polls.count < Self.firstFill {
                guard await loadMore() else { break }
            }
            isLoading = false
        }
        for await diffs in stream.updates {
            for diff in diffs {
                items.apply(diff.vectorDiff)
            }
            rebuild()
        }
    }

    /// Action loadMore. false when the page failed.
    @discardableResult
    func loadMore() async -> Bool {
        guard let timeline, !reachedStart, !paging else { return true }
        paging = true
        defer { paging = false }
        guard let hitStart = try? await timeline.paginateBackwards(numEvents: 50) else { return false }
        reachedStart = hitStart
        return true
    }

    private func rebuild() {
        polls = items.reversed().compactMap { item in
            guard let event = item.asEvent(), case .eventId(let eventId) = event.eventOrTransactionId,
                  case .msgLike(let content) = event.content,
                  case .poll(let question, let kind, let maxSelections, let answers, let votes, let endTime, _) = content.kind
            else { return nil }
            return PollData(eventId: eventId, question: question, kind: kind, maxSelections: maxSelections, answers: answers,
                            votes: votes, endTime: endTime, isOwnPoll: event.isOwn, ownUserId: session.userId)
        }
    }
}

/// Spec poll-history.
struct PollHistoryView: View {
    @StateObject private var model: PollHistoryModel

    init(session: AccountSession, roomId: String) {
        _model = StateObject(wrappedValue: PollHistoryModel(session: session, roomId: roomId))
    }

    var body: some View {
        List {
            if model.polls.isEmpty {
                if model.isLoading {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text(BipeStrings.pollHistoryEmpty)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            ForEach(model.polls) { poll in
                VStack(alignment: .leading, spacing: 4) {
                    Text(poll.question)
                        .foregroundStyle(BipeColor.inkPrimary)
                    HStack(spacing: 8) {
                        Text(poll.isEnded ? BipeStrings.pollStateEnded : BipeStrings.pollStateOpen)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(poll.isEnded ? BipeColor.inkSecondary : BipeColor.accentInk)
                        if let total = poll.totalVotes {
                            Text(BipeStrings.appPollVotes(total.formatted()))
                                .font(.caption)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    if let leader = leader(of: poll) {
                        HStack(spacing: 4) {
                            Text(BipeStrings.appPollMostVotes)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(BipeColor.inkSecondary)
                            Text(leader)
                                .font(.caption)
                                .foregroundStyle(BipeColor.inkPrimary)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                .onAppear { if poll.id == model.polls.last?.id { Task { await model.loadMore() } } }
            }
        }
        .listStyle(.plain)
        .navigationTitle(BipeStrings.pollHistory)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.pollHistory.rawValue)
        .task { await model.appear() }
    }

    /// State leader: the answer with the most votes, when results show and a vote exists.
    private func leader(of poll: PollData) -> String? {
        guard poll.resultsVisible, let top = poll.options.max(by: { ($0.voteCount ?? 0) < ($1.voteCount ?? 0) }),
              (top.voteCount ?? 0) > 0 else { return nil }
        return top.text
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// A poll from the timeline item with MsgLikeKind poll (spec poll-detail notes). The poll card in chat-timeline and
/// poll-detail draw it.
struct PollData: Equatable, Identifiable {
    /// Type PollOption of spec poll-detail, without the voters (poll-detail adds them).
    struct Option: Identifiable, Equatable {
        var answerId: String
        var text: String
        /// Absent while results are hidden.
        var voteCount: Int?
        var voterIds: [String]
        var isYourVote: Bool
        var isWinner: Bool

        var id: String { answerId }
    }

    var eventId: String
    var id: String { eventId }
    var question: String
    var options: [Option]
    /// People who voted. Absent while results are hidden.
    var totalVotes: Int?
    var resultsVisible: Bool
    var isEnded: Bool
    var canEnd: Bool
    /// Spec poll-detail notes (bipe#669): the creator, while the poll is open and has no vote, also undisclosed.
    var canEdit: Bool
    /// PollKind undisclosed: results show only at the end.
    var hidesResults: Bool
    var yourVote: String?
    /// One choice with 2 to 10 options: the shape of a company's structured question (spec question-card, ADR 0081).
    var isQuestionShape: Bool

    init(eventId: String, question: String, kind: PollKind, maxSelections: UInt64 = 1, answers: [PollAnswer],
         votes: [String: [String]], endTime: Timestamp?, isOwnPoll: Bool, ownUserId: String) {
        let isEnded = endTime != nil
        let resultsVisible = kind == .disclosed || isEnded
        let yourVote = answers.first { votes[$0.id]?.contains(ownUserId) == true }?.id
        let most = answers.map { votes[$0.id]?.count ?? 0 }.max() ?? 0
        self.eventId = eventId
        self.question = question
        self.isEnded = isEnded
        self.resultsVisible = resultsVisible
        self.canEnd = isOwnPoll && !isEnded
        self.canEdit = isOwnPoll && !isEnded && votes.values.allSatisfy(\.isEmpty)
        self.hidesResults = kind == .undisclosed
        self.yourVote = yourVote
        isQuestionShape = maxSelections == 1 && (2...10).contains(answers.count)
        options = answers.map { answer in
            let voters = votes[answer.id] ?? []
            return Option(answerId: answer.id,
                          text: answer.text,
                          voteCount: resultsVisible ? voters.count : nil,
                          voterIds: resultsVisible ? voters : [],
                          isYourVote: answer.id == yourVote,
                          isWinner: isEnded && most > 0 && voters.count == most)
        }
        totalVotes = resultsVisible ? Set(votes.values.flatMap { $0 }).count : nil
    }
}

extension ChatTimelineModel {
    /// Spec poll-create, action send.
    func createPoll(question: String, answers: [String], hideResults: Bool) async throws {
        guard let timeline else { throw PollError.noTimeline }
        try await timeline.createPoll(question: question, answers: answers, maxSelections: 1, pollKind: hideResults ? .undisclosed : .disclosed)
    }

    /// Spec poll-detail, action vote.
    func vote(eventId: String, answerId: String) async throws {
        guard let timeline else { throw PollError.noTimeline }
        try await timeline.sendPollResponse(pollStartEventId: eventId, answers: [answerId])
    }

    /// Spec poll-create edit (bipe#669): the whole poll again, for an open poll with no vote.
    func editPoll(eventId: String, question: String, answers: [String], hideResults: Bool) async throws {
        guard let timeline else { throw PollError.noTimeline }
        let poll = MatrixRustSDK.PollData(question: question, answers: answers, maxSelections: 1,
                                          pollKind: hideResults ? .undisclosed : .disclosed)
        try await timeline.edit(eventOrTransactionId: .eventId(eventId: eventId), newContent: .pollStart(pollData: poll))
    }

    /// Spec poll-detail, action end.
    func endPoll(eventId: String, text: String) async throws {
        guard let timeline else { throw PollError.noTimeline }
        try await timeline.endPoll(pollStartEventId: eventId, text: text)
    }

    func poll(eventId: String) -> PollData? {
        messages.first { $0.poll?.eventId == eventId }?.poll
    }
}

enum PollError: Error {
    case noTimeline
}

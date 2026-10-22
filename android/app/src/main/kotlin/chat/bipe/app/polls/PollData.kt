// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.polls

import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PollKind
import org.matrix.rustcomponents.sdk.Timeline

/**
 * A poll from the timeline item with MsgLikeKind poll (spec poll-detail notes). The poll card in chat-timeline and
 * poll-detail draw it.
 */
data class PollData(
    val eventId: String,
    val question: String,
    val options: List<Option>,
    /** People who voted. Null while results are hidden. */
    val totalVotes: Int?,
    val resultsVisible: Boolean,
    val isEnded: Boolean,
    val canEnd: Boolean,
    val yourVote: String?,
) {
    /** Type PollOption of spec poll-detail, without the voters' names (poll-detail adds them). */
    data class Option(
        val answerId: String,
        val text: String,
        /** Null while results are hidden. */
        val voteCount: Int?,
        val voterIds: List<String>,
        val isYourVote: Boolean,
        val isWinner: Boolean,
    )

    companion object {
        fun of(eventId: String, poll: MsgLikeKind.Poll, isOwnPoll: Boolean, ownUserId: String): PollData {
            val isEnded = poll.endTime != null
            val resultsVisible = poll.kind == PollKind.DISCLOSED || isEnded
            val yourVote = poll.answers.firstOrNull { poll.votes[it.id]?.contains(ownUserId) == true }?.id
            val most = poll.answers.maxOfOrNull { poll.votes[it.id]?.size ?: 0 } ?: 0
            return PollData(
                eventId = eventId,
                question = poll.question,
                options = poll.answers.map { answer ->
                    val voters = poll.votes[answer.id].orEmpty()
                    Option(
                        answerId = answer.id,
                        text = answer.text,
                        voteCount = if (resultsVisible) voters.size else null,
                        voterIds = if (resultsVisible) voters else emptyList(),
                        isYourVote = answer.id == yourVote,
                        isWinner = isEnded && most > 0 && voters.size == most,
                    )
                },
                totalVotes = if (resultsVisible) poll.votes.values.flatten().toSet().size else null,
                resultsVisible = resultsVisible,
                isEnded = isEnded,
                canEnd = isOwnPoll && !isEnded,
                yourVote = yourVote,
            )
        }
    }
}

/** Spec poll-create, action send. */
suspend fun Timeline.createBipePoll(question: String, answers: List<String>, hideResults: Boolean) {
    createPoll(question, answers, 1u, if (hideResults) PollKind.UNDISCLOSED else PollKind.DISCLOSED)
}

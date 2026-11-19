// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.polls.PollData
import chat.bipe.app.polls.sendBipePoll
import chat.bipe.desktop.account.AccountTaskGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.matrix.rustcomponents.sdk.*

class PollActions internal constructor(private val owner: AccountTaskGroup, private val room: () -> Room,
    private val timeline: () -> Timeline, private val ownId: () -> String, private val fallbackName: String) {
    private val busy = Mutex()

    suspend fun save(eventId: String?, question: String, answers: List<String>, hidden: Boolean) = action {
        val trimmed = question.trim()
        val choices = answers.map(String::trim).filter(String::isNotEmpty)
        check(trimmed.isNotEmpty() && choices.size in 2..MAX_OPTIONS)
        if (eventId == null) {
            val info = room().roomInfo()
            try { check(!info.isDm && !info.isSpace) } finally { info.destroy() }
        } else check(current(eventId).canEdit)
        owner.requireCurrent()
        timeline().sendBipePoll(eventId, trimmed, choices, hidden)
    }

    suspend fun vote(eventId: String, optionId: String) = action {
        val poll = current(eventId)
        check(!poll.isEnded && poll.options.any { it.answerId == optionId })
        if (poll.yourVote != optionId) {
            owner.requireCurrent()
            timeline().sendPollResponse(eventId, listOf(optionId))
        }
    }

    suspend fun end(eventId: String, label: String) = action {
        check(current(eventId).canEnd)
        owner.requireCurrent()
        timeline().endPoll(eventId, label)
    }

    suspend fun voterNames(eventId: String): Map<String, String> {
        val task = owner.async {
            val poll = current(eventId)
            if (!poll.resultsVisible) return@async emptyMap()
            poll.options.flatMap { it.voterIds }.distinct().associateWith { id ->
                try { realName(room().memberDisplayName(id), id, fallbackName) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fallbackName }
            }
        }
        try { return task.await() } finally { task.cancel() }
    }

    private suspend fun action(block: suspend () -> Unit) = owner.async {
        check(busy.tryLock())
        try { check(room().membership() == Membership.JOINED); block() }
        finally { busy.unlock() }
    }.await()

    private suspend fun current(eventId: String): PollData {
        val event = timeline().getEventTimelineItemByEventId(eventId)
        try {
            val content = (event.content as? TimelineItemContent.MsgLike)?.content
            val poll = content?.kind as? MsgLikeKind.Poll ?: error("Poll unavailable")
            check(event.isRemote && event.localSendState == null)
            return PollData.of(eventId, poll, event.isOwn, ownId())
        } finally { event.destroy() }
    }

    companion object { const val MAX_OPTIONS = 20 }
}

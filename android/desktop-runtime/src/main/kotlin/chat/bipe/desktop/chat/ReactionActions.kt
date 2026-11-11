// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.rustcomponents.sdk.Reaction

sealed interface ReactionActionPhase {
    data object Idle : ReactionActionPhase
    data class Sending(val rowId: String, val key: String) : ReactionActionPhase
    data class Failed(val rowId: String, val key: String) : ReactionActionPhase
}

/** SDK timeline updates own optimistic counts. This state owns only the in-flight action and retryable error. */
class ReactionActions internal constructor(
    private val group: AccountTaskGroup,
    private val row: (String) -> MessageRow?,
    private val performToggle: suspend (String, String) -> Unit,
) {
    private val mutablePhase = MutableStateFlow<ReactionActionPhase>(ReactionActionPhase.Idle)
    val phase = mutablePhase.asStateFlow()

    fun toggle(rowId: String, key: String) {
        var eventId: String? = null
        group.publish {
            if (key.isNotBlank() && mutablePhase.value !is ReactionActionPhase.Sending) {
                eventId = row(rowId)?.eventId
                if (eventId != null) mutablePhase.value = ReactionActionPhase.Sending(rowId, key)
            }
        }
        val target = eventId ?: return
        group.launch {
            try {
                performToggle(target, key)
                group.publish { mutablePhase.value = ReactionActionPhase.Idle }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { group.publish { mutablePhase.value = ReactionActionPhase.Failed(rowId, key) } }
        }
    }

    fun dismissFailure() = group.publish {
        if (mutablePhase.value is ReactionActionPhase.Failed) mutablePhase.value = ReactionActionPhase.Idle
    }
}

internal fun reactionSnapshots(reactions: List<Reaction>, ownUserId: String): List<MessageReaction> = reactions
    .filter { it.senders.isNotEmpty() }
    .map { reaction -> MessageReaction(reaction.key,
        reaction.senders.map { ReactionSender(it.senderId, it.timestamp.toLong()) }.sortedByDescending { it.reactedAt },
        reaction.senders.any { it.senderId == ownUserId }) }

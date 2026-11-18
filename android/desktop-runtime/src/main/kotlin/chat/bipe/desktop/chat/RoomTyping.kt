// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

data class TypingPeople(val first: String, val second: String?, val count: Int)
sealed interface TypingPhase {
    data object Hidden : TypingPhase
    data class Showing(val people: TypingPeople) : TypingPhase
}
internal interface TypingBackend {
    suspend fun notice(typing: Boolean)
    suspend fun name(userId: String): String?
}
interface TypingPreference {
    val enabled: Boolean
    val changes: Flow<Boolean>
    data object Hidden : TypingPreference { override val enabled = false; override val changes = flowOf(false) }
}

/** One room-owned actor orders on/off notices. Draft content never enters the actor or a network call. */
class RoomTyping internal constructor(private val group: AccountTaskGroup, private val ownId: String,
                                     private val fallback: String, private val backend: TypingBackend,
                                     private val allowedNow: () -> Boolean = { true },
                                     private val now: () -> Long = { System.nanoTime() / 1_000_000 }) : AccountResource {
    private sealed interface Input {
        data class Availability(val enabled: Boolean) : Input
        data class Visible(val visible: Boolean) : Input
        data class Draft(val nonBlank: Boolean) : Input
        data class People(val ids: List<String>) : Input
        data class Names(val revision: Long, val people: TypingPeople) : Input
    }
    private sealed interface Outgoing {
        data object Quiet : Outgoing
        data class Announced(val at: Long) : Outgoing
        data object Closed : Outgoing
    }
    private data class Context(val enabled: Boolean = false, val visible: Boolean = false) {
        val allowed get() = enabled && visible
    }
    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val mutablePhase = MutableStateFlow<TypingPhase>(TypingPhase.Hidden)
    val phase = mutablePhase.asStateFlow()
    private var context = Context()
    private var outgoing: Outgoing = Outgoing.Quiet
    private var revision = 0L

    init { group.launch {
        for (input in inbox) when (input) {
            is Input.Availability -> { context = context.copy(enabled = input.enabled); if (!context.allowed) hide() }
            is Input.Visible -> { context = context.copy(visible = input.visible); if (!context.allowed) hide() }
            is Input.Draft -> {
                if (!context.allowed || !allowedNow() || !input.nonBlank) stop()
                else {
                    val previous = outgoing as? Outgoing.Announced
                    val clock = now()
                    if (previous == null || clock - previous.at >= 4_000) {
                        // A failed/cancelled request can already have reached the server; close still sends off.
                        outgoing = Outgoing.Announced(clock)
                        notice(true)
                    }
                }
            }
            is Input.People -> {
                val attempt = ++revision
                val ids = input.ids.filter { it != ownId }.distinct()
                if (!context.allowed || !allowedNow() || ids.isEmpty()) publish(TypingPhase.Hidden)
                else group.launch {
                    val names = ids.take(2).map { id ->
                        val name = try { backend.name(id) } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        realName(name, id, fallback)
                    }
                    enqueue(Input.Names(attempt, TypingPeople(names.first(), names.getOrNull(1), ids.size)))
                }
            }
            is Input.Names -> if (input.revision == revision && context.allowed && allowedNow()) publish(TypingPhase.Showing(input.people))
        }
    } }

    fun available(enabled: Boolean) = enqueue(Input.Availability(enabled))
    fun visible(visible: Boolean) = enqueue(Input.Visible(visible))
    fun draftChanged(nonBlank: Boolean) = enqueue(Input.Draft(nonBlank))
    fun incoming(ids: List<String>) = enqueue(Input.People(ids.toList()))
    private fun enqueue(input: Input) = group.publish { inbox.trySend(input) }
    private fun publish(value: TypingPhase) = group.publish { mutablePhase.value = value }
    private suspend fun hide() { revision++; publish(TypingPhase.Hidden); stop() }
    private suspend fun stop() {
        if (outgoing is Outgoing.Announced && notice(false)) outgoing = Outgoing.Quiet
    }
    private suspend fun notice(value: Boolean): Boolean {
        if (value && !allowedNow()) return false
        return try { withTimeoutOrNull(3_000) { backend.notice(value) } != null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false } // Ephemeral failure never blocks a draft or retries into another room.
    }

    /** Group tasks have joined; the captured native room/client are still owned until this resource returns. */
    override suspend fun close() {
        if (outgoing == Outgoing.Closed) return
        val needsOff = outgoing is Outgoing.Announced
        outgoing = Outgoing.Closed
        inbox.cancel()
        mutablePhase.value = TypingPhase.Hidden
        if (needsOff) notice(false)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessageEditDraft internal constructor(val rowId: String, val eventId: String, val originalText: String,
                                                 val text: String, val revision: Long = 0) {
    val canSave get() = text.trim().isNotEmpty() && text.trim() != originalText.trim()
    override fun toString() = "MessageEditDraft(<redacted>)"
}
sealed interface EditPhase {
    val draft: MessageEditDraft?
    data object Idle : EditPhase { override val draft: MessageEditDraft? = null }
    data class Composing(override val draft: MessageEditDraft) : EditPhase
    data class Saving(override val draft: MessageEditDraft) : EditPhase
    data class Failed(override val draft: MessageEditDraft) : EditPhase
}

/** A room-owned edit overlays the composer; the normal unsent draft stays in its encrypted SDK store. */
class EditActions internal constructor(private val group: AccountTaskGroup, private val composer: RoomComposer,
                                      private val row: (String) -> MessageRow?, private val canBegin: () -> Boolean,
                                      private val perform: suspend (String, String) -> Unit) {
    private val mutablePhase = MutableStateFlow<EditPhase>(EditPhase.Idle)
    val phase = mutablePhase.asStateFlow()

    fun canEdit(rowId: String): Boolean {
        var result = false
        group.publish { result = mutablePhase.value !is EditPhase.Saving && canBegin() &&
            composer.state.value is ComposerState.Editing && editable(rowId) != null }
        return result
    }
    private fun editable(rowId: String): MessageRow? = row(rowId)?.takeIf {
        it.id == rowId && it.own && it.canEdit && it.kind == MessageRow.Kind.TEXT &&
            it.delivery == MessageRow.Delivery.SENT && it.eventId != null
    }

    fun start(rowId: String): Boolean {
        var accepted = false
        group.publish {
            if (!canEdit(rowId)) return@publish
            val current = editable(rowId) ?: return@publish
            // This matches the phone: end the reply, preserve its text, and never save edit text as a new-message draft.
            composer.cancelReply()
            mutablePhase.value = EditPhase.Composing(MessageEditDraft(rowId, current.eventId!!, current.body, current.body))
            accepted = true
        }
        return accepted
    }

    fun change(text: String) = group.publish {
        val current = mutablePhase.value
        if (current is EditPhase.Saving) return@publish
        current.draft?.let { mutablePhase.value = EditPhase.Composing(it.copy(text = text, revision = it.revision + 1)) }
    }

    fun cancel(): Boolean {
        var accepted = false
        group.publish {
            if (mutablePhase.value !is EditPhase.Saving) { mutablePhase.value = EditPhase.Idle; accepted = true }
        }
        return accepted
    }

    fun save() {
        var submitted: MessageEditDraft? = null
        group.publish {
            val current = mutablePhase.value
            if (current is EditPhase.Saving) return@publish
            current.draft?.takeIf { it.canSave }?.let {
                submitted = it; mutablePhase.value = EditPhase.Saving(it)
            }
        }
        val draft = submitted ?: return
        group.launch {
            try {
                // Re-read the live row after scheduling; an old menu cannot edit a redacted/replaced target.
                val current = editable(draft.rowId)
                check(current?.eventId == draft.eventId)
                perform(draft.eventId, draft.text.trim())
                group.publish {
                    if ((mutablePhase.value as? EditPhase.Saving)?.draft === draft) mutablePhase.value = EditPhase.Idle
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish {
                if ((mutablePhase.value as? EditPhase.Saving)?.draft === draft) mutablePhase.value = EditPhase.Failed(draft)
            } }
        }
    }
}

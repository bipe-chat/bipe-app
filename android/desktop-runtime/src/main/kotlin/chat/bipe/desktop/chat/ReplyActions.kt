// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup

/** Dispatch reads the current row, never a retained menu snapshot or another account's selected room. */
class ReplyActions internal constructor(private val group: AccountTaskGroup, private val composer: RoomComposer,
                                       private val row: (String) -> MessageRow?) {
    fun start(rowId: String): Boolean {
        var accepted = false
        group.publish {
            val current = row(rowId) ?: return@publish
            val event = current.eventId ?: return@publish
            if (!current.canReply || current.kind == MessageRow.Kind.DELETED || current.kind == MessageRow.Kind.UNDECRYPTABLE ||
                current.delivery != MessageRow.Delivery.SENT || composer.state.value !is ComposerState.Editing) return@publish
            composer.replyTo(event)
            accepted = true
        }
        return accepted
    }
}

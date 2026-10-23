// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.people.Person
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeInviteStatus
import chat.bipe.shared.api.myInvites
import chat.bipe.shared.invites.BipeInviteStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.messageEventContentNew

/**
 * The inviter side of specs invites and invite-note (ADR 0136), at app start and on each return to the foreground:
 * sends the note waiting for a used invite into the DM with the person who joined, then deletes it from this phone,
 * and gives the bipe_invites_joined toast once for a new join. Same steps as InviteJoinWatcher on iOS.
 */
class InviteJoinWatcher(private val context: Context, private val session: AccountSession, private val api: BipeApiClient) {
    private val store = BipeInviteStore(context, session.userId)

    /** The used invites already announced, per account. `null` until the first check on this phone. Not secret. */
    private val seenJoins = context.getSharedPreferences("bipe_invite_joins", Context.MODE_PRIVATE)
    private val seenKey = "seen_" + session.userId
    private val mutex = Mutex()

    /** Returns the join toast text for a new join, else `null`. */
    suspend fun check(): String? = mutex.withLock {
        // GET /v1/me/invites: each used invite names who joined, which ties a note (kept by invite ID) to the person.
        val mine = runCatching { session.authorized { api.myInvites(it) } }.getOrNull() ?: return null
        val used = mine.invites.filter { it.status == BipeInviteStatus.USED && it.joinedUserId != null }
        val notes = store.notes()
        for (invite in used) {
            val note = notes[invite.id] ?: continue
            val userId = invite.joinedUserId ?: continue
            // Without a DM yet, the note waits for the next check.
            if (send(note, userId)) store.removeNote(invite.id)
        }
        val usedIds = used.map { it.id }.toSet()
        val seen = seenJoins.getStringSet(seenKey, null)?.toSet()
        seenJoins.edit().putStringSet(seenKey, seen.orEmpty() + usedIds).apply()
        // The first check on this phone marks the old joins as seen without announcing them.
        if (seen == null) return null
        val userId = used.firstOrNull { it.id !in seen }?.joinedUserId ?: return null
        return context.getString(R.string.bipe_invites_joined, name(userId))
    }

    /** Client.getDmRoom, Room.timeline and Timeline.send: the note as a normal end-to-end encrypted text message. */
    private suspend fun send(note: String, userId: String): Boolean = runCatching {
        val room = session.client.getDmRoom(userId) ?: return false
        room.timeline().send(messageEventContentNew(MessageType.Text(TextMessageContent(note, null))))
        true
    }.getOrDefault(false)

    private suspend fun name(userId: String): String {
        val profileName = runCatching { session.client.getProfile(userId).displayName }.getOrNull()?.trim()
        return profileName?.takeIf { it.isNotEmpty() && it != userId } ?: Person.localpart(userId)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.shared.clearedchats.ClearedChats
import chat.bipe.shared.generated.BipeDmCandidate
import chat.bipe.shared.generated.BipeDmSelection
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Membership

/** SDK adapter for generated ADR 0141 selection. Only this account's local rooms/account data are read. */
object NativeDmSelection {
    suspend fun visible(chats: List<ChatSummary>, session: AccountSession): List<ChatSummary> {
        val peers = chats.filter { it.isDm }.mapNotNull { it.peerUserId }.toSet()
        if (peers.isEmpty()) return chats
        return try {
            val selected = BipeDmSelection.newestRooms(candidates(peers, session))
            chats.filter { !it.isDm || it.peerUserId == null || selected[it.peerUserId] == it.roomId }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { chats } // Keep content on a local read failure; later SDK updates retry.
    }

    /** Reuse a cleared existing DM if all are cleared. Read failures propagate, preventing duplicate creation. */
    suspend fun roomId(peer: String, session: AccountSession): String? {
        val candidates = candidates(setOf(peer), session)
        return BipeDmSelection.newestRooms(candidates)[peer]
            ?: BipeDmSelection.newestRooms(candidates, includingCleared = true)[peer]
    }

    private suspend fun candidates(peers: Set<String>, session: AccountSession): List<BipeDmCandidate> {
        val cleared = ClearedChats.parse(session.client.accountData(BipeRegistries.CLEARED_CHATS_EVENT_TYPE))
        val result = mutableListOf<BipeDmCandidate>()
        for (peer in peers.sorted()) {
            // Local SDK results include DMs beyond the current page, unread filter or search results.
            for (room in session.client.getDmRooms(peer)) {
                currentCoroutineContext().ensureActive()
                val info = room.roomInfo()
                if (!info.isDm || info.isSpace || info.membership != Membership.JOINED) continue
                val timestamp = when (val latest = room.latestEvent()) {
                    is LatestEventValue.Remote -> latest.timestamp.toLong()
                    is LatestEventValue.Local -> latest.timestamp.toLong()
                    is LatestEventValue.RemoteInvite -> latest.timestamp.toLong()
                    LatestEventValue.None -> null
                }
                result += BipeDmCandidate(info.id, peer, timestamp, eligible = true, cleared = cleared.isHidden(info.id, timestamp))
            }
        }
        return result
    }
}

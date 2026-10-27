// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.serverAccountData
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.settings.BipeNewsDirectCleanup
import chat.bipe.shared.settings.BipeNewsState
import chat.bipe.shared.settings.newsState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import org.matrix.rustcomponents.sdk.Membership

/**
 * Novidades do Bipe is a named room, not a DM (bipe-chat/bipe#809, spec chat-list notes). bipe-api made news rooms
 * with is_direct before, so the account's m.direct lists them and the room took the DM name rule (ADR 0141).
 * Once per account on this phone, after the first sync: the news room ID comes from GET /v1/me/news (room_id), and
 * the app takes that room out of m.direct when it is there. A failure tries again at the next start or return to
 * the foreground.
 */
object NewsRoomCleanup {
    private const val PREFS = "bipe_news_room_not_dm"
    private val running = mutableSetOf<String>()

    suspend fun applyOnce(context: Context, session: AccountSession, api: BipeApiClient) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val userId = session.userId
        if (prefs.getBoolean(userId, false)) return
        if (!synchronized(running) { running.add(userId) }) return
        try {
            val state: BipeNewsState = try {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { api.newsState(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return
            }
            val roomId = state.roomId
            // No news room (news off): a room made later is not a DM.
            if (!state.subscribed || roomId == null) {
                prefs.edit().putBoolean(userId, true).apply()
                return
            }
            // The room arrives with the first sync. An invite from before the fix says is_direct, and joining it adds
            // it to m.direct, so the cleanup runs only on a joined room.
            var joined = false
            for (attempt in 1..10) {
                if (runCatching { session.client.getRoom(roomId)?.membership() }.getOrNull() == Membership.JOINED) {
                    joined = true
                    break
                }
                delay(3_000)
            }
            if (!joined) return
            try {
                val current = serverAccountData(session.client, BipeNewsDirectCleanup.DIRECT_EVENT_TYPE)
                BipeNewsDirectCleanup.removing(roomId, current)?.let {
                    session.client.setAccountData(BipeNewsDirectCleanup.DIRECT_EVENT_TYPE, it)
                }
                prefs.edit().putBoolean(userId, true).apply()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Tried again at the next start.
            }
        } finally {
            synchronized(running) { running.remove(userId) }
        }
    }
}

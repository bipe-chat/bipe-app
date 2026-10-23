// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import chat.bipe.app.BipeApplication
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.messageEventContentNew

/** Spec notification-service, actions reply and markAsRead, without opening the app. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BipeApplication
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) reply(app, roomId, eventId, text)
                    }
                    ACTION_MARK_READ -> markAsRead(app, roomId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Action reply: sends the text as a reply to the event, then marks the chat as read. */
    private suspend fun reply(app: BipeApplication, roomId: String, eventId: String, text: String) {
        var isDm = true
        try {
            val session = app.accountStart.awaitSession() ?: error("no account")
            val room = session.client.getRoom(roomId) ?: error("no room")
            isDm = runCatching { room.roomInfo().isDm }.getOrDefault(true)
            val timeline = room.timeline()
            timeline.sendReply(messageEventContentNew(MessageType.Text(TextMessageContent(text, null))), eventId)
            waitUntilSent(timeline)
            runCatching { room.markAsRead(ReceiptType.READ) }
            app.notifications.clear(roomId)
        } catch (e: Exception) {
            app.notifications.replyFailed(roomId, isDm)
        }
    }

    /** Action markAsRead: a public read receipt, then the chat's notifications go away. */
    private suspend fun markAsRead(app: BipeApplication, roomId: String) {
        app.accountStart.awaitSession()?.let { session ->
            runCatching { session.client.getRoom(roomId)?.markAsRead(ReceiptType.READ) }
        }
        app.notifications.clear(roomId)
    }

    /** The send queue sends in the background; the receiver stays alive until the reply left the phone. */
    private suspend fun waitUntilSent(timeline: Timeline) {
        val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
        val handle = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                diffs.trySend(diff)
            }
        })
        try {
            withTimeout(SEND_TIMEOUT_MS) {
                val items = mutableListOf<TimelineItem>()
                for (batch in diffs) {
                    batch.forEach { items.applyDiff(it.toVectorDiff()) }
                    val own = items.mapNotNull { it.asEvent() }.filter { it.isOwn }
                    if (own.any { it.localSendState is EventSendState.SendingFailed }) error("reply failed")
                    if (own.none { it.localSendState is EventSendState.NotSentYet }) return@withTimeout
                }
            }
        } finally {
            handle.cancel()
        }
    }

    companion object {
        const val ACTION_REPLY = "chat.bipe.app.REPLY"
        const val ACTION_MARK_READ = "chat.bipe.app.MARK_READ"
        const val KEY_REPLY_TEXT = "reply_text"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_EVENT_ID = "event_id"
        private const val SEND_TIMEOUT_MS = 20_000L

        /** The reply needs a mutable intent: the system adds the typed text. */
        fun intent(context: Context, action: String, roomId: String, eventId: String, mutable: Boolean): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_EVENT_ID, eventId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, (action + roomId).hashCode(), intent, flags)
        }
    }
}

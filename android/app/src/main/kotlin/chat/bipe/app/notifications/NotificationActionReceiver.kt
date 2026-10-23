// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.app.account.AccountSession
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
        val target = app.notifications.target(intent) ?: return
        val route = intent.getStringExtra(PhoneNotifications.EXTRA_ACCOUNT_ROUTE) ?: return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) reply(app, target, route, eventId, text)
                    }
                    ACTION_MARK_READ -> markAsRead(app, target, route)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Action reply: sends the text as a reply to the event, then marks the chat as read. */
    private suspend fun reply(app: BipeApplication, target: BipeNotificationTarget, route: String, eventId: String, text: String) {
        var isDm = true
        var owner: AccountSession? = null
        try {
            val session = app.accountStart.awaitSession(target.accountUserId) ?: return
            owner = session
            val room = session.client.getRoom(target.roomId) ?: error("no room")
            isDm = runCatching { room.roomInfo().isDm }.getOrDefault(true)
            val timeline = room.timeline()
            if (!app.notifications.owns(target, route) || app.accountStart.sessions.value.none { it === session }) return
            timeline.sendReply(messageEventContentNew(MessageType.Text(TextMessageContent(text, null))), eventId)
            waitUntilSent(timeline)
            if (!app.notifications.owns(target, route) || app.accountStart.sessions.value.none { it === session }) return
            runCatching { room.markAsRead(ReceiptType.READ) }
            if (app.notifications.owns(target, route) && app.accountStart.sessions.value.any { it === session }) app.notifications.clear(target)
        } catch (e: Exception) {
            if (owner != null && app.accountStart.sessions.value.any { it === owner }) app.notifications.replyFailed(target, route, isDm)
        }
    }

    /** Action markAsRead: a public read receipt, then the chat's notifications go away. */
    private suspend fun markAsRead(app: BipeApplication, target: BipeNotificationTarget, route: String) {
        val session = app.accountStart.awaitSession(target.accountUserId) ?: return
        val room = runCatching { session.client.getRoom(target.roomId) }.getOrNull() ?: return
        if (!app.notifications.owns(target, route) || app.accountStart.sessions.value.none { it === session }) return
        runCatching { room.markAsRead(ReceiptType.READ) }.onSuccess {
            if (app.notifications.owns(target, route) && app.accountStart.sessions.value.any { it === session }) app.notifications.clear(target)
        }
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
        fun intent(context: Context, action: String, target: BipeNotificationTarget, route: String, eventId: String, mutable: Boolean): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.Builder().scheme("bipe-notification").authority("action").appendPath(action).appendPath(target.identifier).appendPath(route).build())
                .putExtra(PhoneNotifications.EXTRA_ACCOUNT_USER_ID, target.accountUserId)
                .putExtra(PhoneNotifications.EXTRA_ACCOUNT_ROUTE, route)
                .putExtra(EXTRA_ROOM_ID, target.roomId)
                .putExtra(EXTRA_EVENT_ID, eventId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }
    }
}

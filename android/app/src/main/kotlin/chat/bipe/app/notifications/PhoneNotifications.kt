// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import chat.bipe.app.MainActivity
import chat.bipe.app.account.AccountSession
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TimelineEvent
import org.matrix.rustcomponents.sdk.TimelineEventContent

/** The message sound on this phone (spec notification-settings, state sound). A phone setting, not a push rule. */
enum class NotificationSound { PHONE, SOFT, SILENT }

/**
 * Spec notification-service on Android. A push carries only the room ID, the event ID and the unread count
 * (push-registration: format event_id_only). The app fetches the event in its own process with the sync
 * service (NotificationProcessSetup SingleProcess), decrypts it on the phone and shows it. Each chat is one
 * conversation notification with its messages.
 */
class PhoneNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private var notificationClient: NotificationClient? = null
    private var clientSession: AccountSession? = null

    /** The chat on screen: its pushes show no notification. */
    @Volatile
    var visibleRoomId: String? = null

    var sound: NotificationSound
        get() = prefs.getString(KEY_SOUND, null)?.let { runCatching { NotificationSound.valueOf(it) }.getOrNull() } ?: NotificationSound.PHONE
        private set(value) {
            prefs.edit().putString(KEY_SOUND, value.name).apply()
        }

    init {
        createChannels()
    }

    /** Action setSound: moves the messages channel to that sound (a channel keeps its sound once created). */
    fun setSound(sound: NotificationSound): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(messagesChannelId())
        }
        this.sound = sound
        createChannels()
    }.isSuccess

    /** Action pushReceived. */
    suspend fun pushReceived(session: AccountSession, roomId: String, eventId: String) {
        if (roomId == visibleRoomId) return
        val status = runCatching {
            withTimeout(FETCH_TIMEOUT_MS) { client(session).getNotification(roomId, eventId) }
        }.getOrNull()
        when (status) {
            is NotificationStatus.Event -> show(status.item, roomId, eventId)
            NotificationStatus.EventFilteredOut, NotificationStatus.EventRedacted, NotificationStatus.EventNotFound -> Unit
            null -> showFallback(roomId, eventId)
        }
    }

    /** Opening a chat, a read on another device, or markAsRead removes that chat's notifications. */
    fun clear(roomId: String) {
        manager.cancel(roomId, NOTIFICATION_ID)
        manager.cancel(roomId, INVITE_ID)
        manager.cancel(roomId, FAILURE_ID)
    }

    /** A failed inline reply shows a new notification with the failure string. */
    @SuppressLint("MissingPermission")
    fun replyFailed(roomId: String, isDm: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val text = context.getString(if (isDm) R.string.bipe_dm_inline_reply_failed else R.string.bipe_group_notification_inline_reply_failed)
        val notification = NotificationCompat.Builder(context, messagesChannelId())
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .setContentText(text)
            .setContentIntent(openIntent(roomId))
            .setAutoCancel(true)
            .build()
        manager.notify(roomId, FAILURE_ID, notification)
    }

    private suspend fun client(session: AccountSession): NotificationClient {
        val current = notificationClient
        if (current != null && clientSession === session) return current
        return session.client.notificationClient(NotificationProcessSetup.SingleProcess(session.syncService)).also {
            notificationClient = it
            clientSession = session
        }
    }

    private fun show(item: NotificationItem, roomId: String, eventId: String) {
        val room = item.roomInfo
        val senderName = item.senderInfo.displayName?.takeIf { it.isNotEmpty() }
        when (val event = item.event) {
            is NotificationEvent.Timeline -> {
                val text = messageText(event.event) ?: return showFallback(roomId, eventId)
                val sender = senderName ?: event.event.senderId()
                // ADR 0141: a DM shows the other person, never the room name.
                val title = if (room.isDm) sender else room.displayName
                post(roomId, eventId, title, isGroup = !room.isDm, sender, text, event.event.timestamp().toLong(), isNoisy = item.isNoisy ?: true)
            }
            is NotificationEvent.Invite -> {
                val sender = senderName ?: event.sender
                val text = if (room.isDm) {
                    context.getString(R.string.bipe_app_notification_chat_request, sender)
                } else {
                    context.getString(R.string.bipe_group_notification_room_invite_body_with_sender, sender)
                }
                postSimple(roomId, if (room.isDm) sender else room.displayName, text, INVITES_CHANNEL)
            }
        }
    }

    /** Kind fallback: the event could not be fetched or decrypted in time. */
    private fun showFallback(roomId: String, eventId: String) {
        postSimple(roomId, context.getString(R.string.bipe_app_name), context.getString(R.string.bipe_app_notification_new_message), messagesChannelId(), eventId)
    }

    /** One conversation notification per chat, with its messages since the chat was last read. */
    @SuppressLint("MissingPermission")
    private fun post(roomId: String, eventId: String, title: String, isGroup: Boolean, sender: String, text: String, sentAt: Long, isNoisy: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        // The earlier messages come from the notification on screen: decrypted text never goes to storage.
        val me = Person.Builder().setName(context.getString(R.string.bipe_app_name)).build()
        val shown = manager.activeNotifications.firstOrNull { it.tag == roomId && it.id == NOTIFICATION_ID }
        val style = shown?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            ?: NotificationCompat.MessagingStyle(me)
        style.setGroupConversation(isGroup)
        style.setConversationTitle(if (isGroup) title else null)
        style.addMessage(text, sentAt, Person.Builder().setName(sender).build())
        while (style.messages.size > MAX_MESSAGES) style.messages.removeAt(0)
        val replyInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.bipe_app_timeline_composer_placeholder))
            .build()
        val reply = NotificationCompat.Action.Builder(
            PhosphorIcon.PAPER_PLANE_RIGHT.drawable,
            context.getString(R.string.bipe_app_notification_reply),
            NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_REPLY, roomId, eventId, mutable = true),
        ).addRemoteInput(replyInput).setAllowGeneratedReplies(false).build()
        val markRead = NotificationCompat.Action.Builder(
            PhosphorIcon.CHECK.drawable,
            context.getString(R.string.bipe_app_notification_mark_read),
            NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_MARK_READ, roomId, eventId, mutable = false),
        ).build()
        val builder = NotificationCompat.Builder(context, messagesChannelId())
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(if (isGroup) "$sender: $text" else text)
            .setWhen(sentAt)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(roomId))
            .addAction(reply)
            .addAction(markRead)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (!isNoisy) builder.setSilent(true)
        manager.notify(roomId, NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun postSimple(roomId: String, title: String, text: String, channel: String, eventId: String? = null) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(roomId))
            .setAutoCancel(true)
            .build()
        manager.notify(roomId, if (eventId == null) INVITE_ID else NOTIFICATION_ID, notification)
    }

    /** Action open: the app on chat-timeline of the chat. */
    private fun openIntent(roomId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_ROOM_ID, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, roomId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Channels: messages (with the sound of notification-settings), and requests and invites. */
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java)
        val messages = NotificationChannel(messagesChannelId(), context.getString(R.string.bipe_app_notification_channel_messages), NotificationManager.IMPORTANCE_HIGH)
        // Bipe's soft sound has no asset yet, so soft plays the system sound.
        if (sound == NotificationSound.SILENT) messages.setSound(null, null)
        system.createNotificationChannel(messages)
        system.createNotificationChannel(
            NotificationChannel(INVITES_CHANNEL, context.getString(R.string.bipe_notifications_invites), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun messagesChannelId(): String = "messages_" + sound.name.lowercase()

    /** The plain text of a message. null for media until #561 and for undecryptable events. No Markdown. */
    private fun messageText(event: TimelineEvent): String? {
        val content = runCatching { event.content() }.getOrNull() as? TimelineEventContent.MessageLike ?: return null
        val message = content.content as? MessageLikeEventContent.RoomMessage ?: return null
        return when (val type = message.messageType) {
            is MessageType.Text -> type.content.body
            is MessageType.Emote -> type.content.body
            is MessageType.Notice -> type.content.body
            else -> null
        }
    }

    companion object {
        const val ACTION_OPEN_CHAT = "chat.bipe.app.OPEN_CHAT"
        const val EXTRA_ROOM_ID = "room_id"
        private const val INVITES_CHANNEL = "invites"
        private const val KEY_SOUND = "sound"
        private const val NOTIFICATION_ID = 1
        private const val INVITE_ID = 2
        private const val FAILURE_ID = 3
        private const val MAX_MESSAGES = 8
        private const val FETCH_TIMEOUT_MS = 25_000L
    }
}

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
import android.os.Bundle
import android.net.Uri
import chat.bipe.app.BipeApplication
import chat.bipe.shared.BipeNotificationTarget
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
    var visibleRoom: BipeNotificationTarget? = null

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
        val target = BipeNotificationTarget.create(session.userId, roomId) ?: return
        if (!isActive(session) || target == visibleRoom) return
        val status = runCatching {
            withTimeout(FETCH_TIMEOUT_MS) { client(session).getNotification(roomId, eventId) }
        }.getOrNull()
        if (!isActive(session) || target == visibleRoom) return
        when (status) {
            is NotificationStatus.Event -> show(status.item, target, eventId)
            NotificationStatus.EventFilteredOut, NotificationStatus.EventRedacted, NotificationStatus.EventNotFound -> Unit
            null -> showFallback(target, eventId)
        }
    }

    /** A legacy push cannot name a room or offer actions without its immutable account owner. */
    @SuppressLint("MissingPermission")
    fun unownedPush() {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, messagesChannelId())
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .setContentTitle(context.getString(R.string.bipe_app_name))
            .setContentText(context.getString(R.string.bipe_app_notification_new_message))
            .setAutoCancel(true)
            .build()
        manager.notify("unowned", NOTIFICATION_ID, notification)
    }

    /** Opening a chat, a read on another device, or markAsRead removes that chat's notifications. */
    fun clear(target: BipeNotificationTarget) {
        manager.cancel(target.identifier, NOTIFICATION_ID)
        manager.cancel(target.identifier, INVITE_ID)
        manager.cancel(target.identifier, FAILURE_ID)
    }

    /** A failed inline reply shows a new notification with the failure string. */
    @SuppressLint("MissingPermission")
    fun replyFailed(target: BipeNotificationTarget, isDm: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val text = context.getString(if (isDm) R.string.bipe_dm_inline_reply_failed else R.string.bipe_group_notification_inline_reply_failed)
        val notification = NotificationCompat.Builder(context, messagesChannelId())
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .addExtras(metadata(target))
            .setContentText(text)
            .setContentIntent(openIntent(target))
            .setAutoCancel(true)
            .build()
        manager.notify(target.identifier, FAILURE_ID, notification)
    }

    private suspend fun client(session: AccountSession): NotificationClient {
        val current = notificationClient
        if (current != null && clientSession === session) return current
        return session.client.notificationClient(NotificationProcessSetup.SingleProcess(session.syncService)).also {
            if (isActive(session)) {
                notificationClient = it
                clientSession = session
            }
        }
    }

    private fun show(item: NotificationItem, target: BipeNotificationTarget, eventId: String) {
        val room = item.roomInfo
        val senderName = item.senderInfo.displayName?.takeIf { it.isNotEmpty() }
        when (val event = item.event) {
            is NotificationEvent.Timeline -> {
                val text = messageText(event.event) ?: return showFallback(target, eventId)
                val sender = senderName ?: event.event.senderId()
                // ADR 0141: a DM shows the other person, never the room name.
                val title = if (room.isDm) sender else room.displayName
                post(target, eventId, title, isGroup = !room.isDm, sender, text, event.event.timestamp().toLong(), isNoisy = item.isNoisy ?: true)
            }
            is NotificationEvent.Invite -> {
                val sender = senderName ?: event.sender
                val text = if (room.isDm) {
                    context.getString(R.string.bipe_app_notification_chat_request, sender)
                } else {
                    context.getString(R.string.bipe_group_notification_room_invite_body_with_sender, sender)
                }
                postSimple(target, if (room.isDm) sender else room.displayName, text, INVITES_CHANNEL)
            }
        }
    }

    /** Kind fallback: the event could not be fetched or decrypted in time. */
    private fun showFallback(target: BipeNotificationTarget, eventId: String) {
        postSimple(target, context.getString(R.string.bipe_app_name), context.getString(R.string.bipe_app_notification_new_message), messagesChannelId(), eventId)
    }

    /** One conversation notification per chat, with its messages since the chat was last read. */
    @SuppressLint("MissingPermission")
    private fun post(target: BipeNotificationTarget, eventId: String, title: String, isGroup: Boolean, sender: String, text: String, sentAt: Long, isNoisy: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        // The earlier messages come from the notification on screen: decrypted text never goes to storage.
        val me = Person.Builder().setName(context.getString(R.string.bipe_app_name)).build()
        val shown = manager.activeNotifications.firstOrNull { it.tag == target.identifier && it.id == NOTIFICATION_ID }
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
            NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_REPLY, target, eventId, mutable = true),
        ).addRemoteInput(replyInput).setAllowGeneratedReplies(false).build()
        val markRead = NotificationCompat.Action.Builder(
            PhosphorIcon.CHECK.drawable,
            context.getString(R.string.bipe_app_notification_mark_read),
            NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_MARK_READ, target, eventId, mutable = false),
        ).build()
        val builder = NotificationCompat.Builder(context, messagesChannelId())
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .addExtras(metadata(target))
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(if (isGroup) "$sender: $text" else text)
            .setWhen(sentAt)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(target))
            .addAction(reply)
            .addAction(markRead)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (!isNoisy) builder.setSilent(true)
        manager.notify(target.identifier, NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun postSimple(target: BipeNotificationTarget, title: String, text: String, channel: String, eventId: String? = null) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .addExtras(metadata(target))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(target))
            .setAutoCancel(true)
            .build()
        manager.notify(target.identifier, if (eventId == null) INVITE_ID else NOTIFICATION_ID, notification)
    }

    /** Action open: the app on chat-timeline of the chat. */
    private fun openIntent(target: BipeNotificationTarget): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .setData(Uri.parse("bipe-notification://open/${target.identifier}"))
            .putExtra(EXTRA_ACCOUNT_USER_ID, target.accountUserId)
            .putExtra(EXTRA_ROOM_ID, target.roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Account sign-out invalidates only this account's cache and delivered notifications. */
    fun detach(userId: String) {
        if (clientSession?.userId == userId) {
            notificationClient = null
            clientSession = null
        }
        if (visibleRoom?.accountUserId == userId) visibleRoom = null
        manager.activeNotifications.filter { it.notification.extras.getString(EXTRA_ACCOUNT_USER_ID) == userId }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun isActive(session: AccountSession): Boolean =
        (context.applicationContext as BipeApplication).accountStart.sessions.value.any { it === session }

    private fun metadata(target: BipeNotificationTarget) = Bundle().apply {
        putString(EXTRA_ACCOUNT_USER_ID, target.accountUserId)
        putString(EXTRA_ROOM_ID, target.roomId)
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
        const val EXTRA_ACCOUNT_USER_ID = "account_user_id"
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

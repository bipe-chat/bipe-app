// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import chat.bipe.shared.account.BipeWorkHours
import java.security.MessageDigest
import java.time.Instant
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import chat.bipe.app.BipeApplication
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.shared.BipePushAccountRoutes
import chat.bipe.shared.BipeNotificationPreview
import org.json.JSONObject
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

/** The message sound of an account on this phone (spec notification-settings, state sound). A phone setting, not a push rule. */
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
    private val accountRoutes = BipePushAccountRoutes(
        read = {
            runCatching {
                val json = JSONObject(prefs.getString(KEY_ROUTES, null) ?: "{}")
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrDefault(emptyMap())
        },
        write = { prefs.edit().putString(KEY_ROUTES, JSONObject(it).toString()).apply() },
    )
    private var notificationClient: NotificationClient? = null
    private var clientSession: AccountSession? = null

    /** The chat on screen: its pushes show no notification. */
    @Volatile
    var visibleRoom: BipeNotificationTarget? = null

    /**
     * The sound of [userId]'s messages on this phone (bipe-chat/bipe#511: per account). The phone-wide value from
     * before is the start value of every account.
     */
    fun sound(userId: String): NotificationSound =
        (prefs.getString("$KEY_SOUND.$userId", null) ?: prefs.getString(KEY_SOUND, null))
            ?.let { runCatching { NotificationSound.valueOf(it) }.getOrNull() } ?: NotificationSound.PHONE

    /** Spec notification-settings, state preview: per account on this phone. Default true. */
    fun preview(userId: String): Boolean = prefs.getBoolean("$KEY_PREVIEW.$userId", true)

    fun setPreview(userId: String, isOn: Boolean) {
        prefs.edit().putBoolean("$KEY_PREVIEW.$userId", isOn).apply()
    }

    /** The phone's copy of a work account's opening hours (spec work-hours notes). Null: the account rings. */
    fun workHours(userId: String): BipeWorkHours? = prefs.getString("$KEY_WORK_HOURS.$userId", null)?.let(BipeWorkHours::decoded)

    fun setWorkHours(userId: String, hours: BipeWorkHours?) {
        prefs.edit().apply { if (hours == null) remove("$KEY_WORK_HOURS.$userId") else putString("$KEY_WORK_HOURS.$userId", hours.encoded()) }.apply()
    }

    /** A work account outside its opening hours (ADR 0138 rule 4): no sound, no vibration, no heads-up. */
    private fun isQuiet(userId: String): Boolean = workHours(userId)?.isQuiet(Instant.now()) ?: false

    init {
        createChannels()
    }

    /** Action setSound: moves [userId]'s messages channel to that sound (a channel keeps its sound once created). */
    fun setSound(userId: String, sound: NotificationSound): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(messagesChannelId(userId))
        }
        prefs.edit().putString("$KEY_SOUND.$userId", sound.name).apply()
        messagesChannel(userId)
    }.isSuccess

    /** Action pushReceived. */
    suspend fun pushReceived(session: AccountSession, roomId: String, eventId: String) {
        val target = BipeNotificationTarget.create(session.userId, roomId) ?: return
        if (!isActive(session) || target == visibleRoom) return
        val route = accountRoutes.routeFor(session.userId)
        val status = runCatching {
            withTimeout(FETCH_TIMEOUT_MS) { client(session).getNotification(roomId, eventId) }
        }.getOrNull()
        if (!isActive(session) || !owns(target, route) || target == visibleRoom) return
        when (status) {
            is NotificationStatus.Event -> show(status.item, target, route, eventId)
            NotificationStatus.EventFilteredOut, NotificationStatus.EventRedacted, NotificationStatus.EventNotFound -> Unit
            null -> showFallback(target, route, eventId)
        }
    }

    /** A legacy push cannot name a room or offer actions without its immutable account owner. */
    @SuppressLint("MissingPermission")
    fun unownedPush() {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL)
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .setContentTitle(context.getString(R.string.bipe_app_name))
            .setContentText(context.getString(R.string.bipe_app_notification_new_message))
            .setAutoCancel(true)
            .build()
        manager.notify("unowned", NOTIFICATION_ID, notification)
    }

    /** The random route of [userId] for its pusher's default_payload (spec push-registration). */
    fun accountRoute(userId: String): String = accountRoutes.routeFor(userId)

    /** The account a push's route was registered for on this phone; `null` when unknown or ambiguous. */
    fun accountFor(route: String): String? = accountRoutes.accountFor(route)

    /**
     * A read on another device: removes the message notifications of [userId]'s chats in [readRoomIds] (no unread
     * messages and not marked unread). Invites stay until they are answered.
     */
    fun clearRead(userId: String, readRoomIds: Set<String>) {
        if (readRoomIds.isEmpty()) return
        runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .filter { it.id != INVITE_ID && it.notification.extras.getString(EXTRA_ACCOUNT_USER_ID) == userId }
            .filter { it.notification.extras.getString(EXTRA_ROOM_ID) in readRoomIds }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** A push without an event (the unread count changed, for example after a read on another device). */
    suspend fun countChanged(session: AccountSession) {
        if (!isActive(session)) return
        val shown = runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .filter { it.id != INVITE_ID && it.notification.extras.getString(EXTRA_ACCOUNT_USER_ID) == session.userId }
            .mapNotNull { it.notification.extras.getString(EXTRA_ROOM_ID) }
            .toSet()
        val read = shown.filter { roomId ->
            val info = runCatching { withTimeout(FETCH_TIMEOUT_MS) { session.client.getRoom(roomId)?.roomInfo() } }.getOrNull()
            info != null && info.numUnreadMessages.toInt() == 0 && !info.isMarkedUnread
        }.toSet()
        if (isActive(session)) clearRead(session.userId, read)
    }

    /** Opening a chat, a read on another device, or markAsRead removes that chat's notifications. */
    fun clear(target: BipeNotificationTarget) {
        manager.cancel(target.identifier, NOTIFICATION_ID)
        manager.cancel(target.identifier, INVITE_ID)
        manager.cancel(target.identifier, FAILURE_ID)
    }

    /** A failed inline reply shows a new notification with the failure string. */
    @SuppressLint("MissingPermission")
    fun replyFailed(target: BipeNotificationTarget, route: String, isDm: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        if (!owns(target, route)) return
        val text = context.getString(if (isDm) R.string.bipe_dm_inline_reply_failed else R.string.bipe_group_notification_inline_reply_failed)
        val notification = NotificationCompat.Builder(context, messagesChannel(target.accountUserId))
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .addExtras(metadata(target, route))
            .setContentText(text)
            .setContentIntent(openIntent(target, route, "message"))
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

    private fun show(item: NotificationItem, target: BipeNotificationTarget, route: String, eventId: String) {
        val room = item.roomInfo
        val senderName = item.senderInfo.displayName?.takeIf { it.isNotEmpty() }
        when (val event = item.event) {
            is NotificationEvent.Timeline -> {
                // bipe-chat/bipe#511: without this account's preview, no sender and no text.
                if (!preview(target.accountUserId)) return showFallback(target, route, eventId)
                val text = messageText(event.event) ?: return showFallback(target, route, eventId)
                val sender = senderName ?: event.event.senderId()
                // ADR 0141: a DM shows the other person, never the room name.
                val title = if (room.isDm) sender else room.displayName
                post(target, route, eventId, title, isGroup = !room.isDm, sender, text, event.event.timestamp().toLong(), isNoisy = item.isNoisy ?: true)
            }
            is NotificationEvent.Invite -> {
                val sender = senderName ?: event.sender
                val text = if (room.isDm) {
                    context.getString(R.string.bipe_app_notification_chat_request, sender)
                } else {
                    context.getString(R.string.bipe_group_notification_room_invite_body_with_sender, sender)
                }
                if (preview(target.accountUserId)) {
                    postSimple(target, route, if (room.isDm) sender else room.displayName, text, INVITES_CHANNEL, kind = if (room.isDm) "chatRequest" else "groupInvite")
                } else {
                    postSimple(target, route, context.getString(R.string.bipe_app_name), context.getString(R.string.bipe_app_notification_new_message), INVITES_CHANNEL, kind = if (room.isDm) "chatRequest" else "groupInvite")
                }
            }
        }
    }

    /** Kind fallback: the event could not be fetched or decrypted in time. */
    private fun showFallback(target: BipeNotificationTarget, route: String, eventId: String) {
        postSimple(target, route, context.getString(R.string.bipe_app_name), context.getString(R.string.bipe_app_notification_new_message), messagesChannel(target.accountUserId), eventId)
    }

    /** One conversation notification per chat, with its messages since the chat was last read. */
    @SuppressLint("MissingPermission")
    private fun post(target: BipeNotificationTarget, route: String, eventId: String, title: String, isGroup: Boolean, sender: String, text: String, sentAt: Long, isNoisy: Boolean) {
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
            NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_REPLY, target, route, eventId, mutable = true),
        ).addRemoteInput(replyInput).setAllowGeneratedReplies(false).build()
        val markRead = NotificationCompat.Action.Builder(
            PhosphorIcon.CHECK.drawable,
            context.getString(R.string.bipe_app_notification_mark_read),
            NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_MARK_READ, target, route, eventId, mutable = false),
        ).build()
        val builder = NotificationCompat.Builder(context, messagesChannel(target.accountUserId))
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .addExtras(metadata(target, route))
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(if (isGroup) "$sender: $text" else text)
            .setWhen(sentAt)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(target, route, "message"))
            .addAction(reply)
            .addAction(markRead)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (!isNoisy || isQuiet(target.accountUserId)) builder.setSilent(true)
        manager.notify(target.identifier, NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun postSimple(target: BipeNotificationTarget, route: String, title: String, text: String, channel: String, eventId: String? = null, kind: String = "fallback") {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(PhosphorIcon.CHAT_CIRCLE.drawable)
            .addExtras(metadata(target, route))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(target, route, kind))
            .setAutoCancel(true)
            .setSilent(isQuiet(target.accountUserId))
            .build()
        manager.notify(target.identifier, if (eventId == null) INVITE_ID else NOTIFICATION_ID, notification)
    }

    /** Action open: the app on chat-timeline of the chat. */
    private fun openIntent(target: BipeNotificationTarget, route: String, kind: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .setData(Uri.parse("bipe-notification://open/${target.identifier}/$route/$kind"))
            .putExtra(EXTRA_ACCOUNT_USER_ID, target.accountUserId)
            .putExtra(EXTRA_ACCOUNT_ROUTE, route)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ROOM_ID, target.roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Account sign-out invalidates only this account's cache and delivered notifications. */
    fun detach(userId: String) {
        accountRoutes.remove(userId)
        if (clientSession?.userId == userId) {
            notificationClient = null
            clientSession = null
        }
        if (visibleRoom?.accountUserId == userId) visibleRoom = null
        manager.activeNotifications.filter { it.notification.extras.getString(EXTRA_ACCOUNT_USER_ID) == userId }
            .forEach { manager.cancel(it.tag, it.id) }
        // bipe-chat/bipe#512: the account's channel group goes with its channels.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannelGroup(groupId(userId))
        }
    }

    private fun isActive(session: AccountSession): Boolean =
        (context.applicationContext as BipeApplication).accountStart.sessions.value.any { it === session }

    /** Only local notifications with a still-live registration lifetime can open or write. */
    fun target(intent: Intent): BipeNotificationTarget? {
        val route = intent.getStringExtra(EXTRA_ACCOUNT_ROUTE) ?: return null
        val target = BipeNotificationTarget.create(intent.getStringExtra(EXTRA_ACCOUNT_USER_ID), intent.getStringExtra(EXTRA_ROOM_ID)) ?: return null
        return target.takeIf { owns(it, route) }
    }

    fun owns(target: BipeNotificationTarget, route: String): Boolean = accountRoutes.accountFor(route) == target.accountUserId

    private fun metadata(target: BipeNotificationTarget, route: String) = Bundle().apply {
        putString(EXTRA_ACCOUNT_USER_ID, target.accountUserId)
        putString(EXTRA_ACCOUNT_ROUTE, route)
        putString(EXTRA_ROOM_ID, target.roomId)
    }

    /**
     * Channels: requests and invites, and the fallback for a push without an owner. Each account's messages have their
     * own channel with that account's sound (bipe-chat/bipe#511), made when first needed. The phone-wide channels from
     * before go.
     */
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java)
        NotificationSound.entries.forEach { system.deleteNotificationChannel("messages_" + it.name.lowercase()) }
        system.createNotificationChannel(
            NotificationChannel(FALLBACK_CHANNEL, context.getString(R.string.bipe_app_notification_channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        system.createNotificationChannel(
            NotificationChannel(FALLBACK_CHANNEL, context.getString(R.string.bipe_app_notification_channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** [userId]'s messages channel, made with its sound when it does not exist yet. */
    private fun messagesChannel(userId: String): String {
        val id = messagesChannelId(userId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val system = context.getSystemService(NotificationManager::class.java)
            if (system.getNotificationChannel(id) == null) {
                val messages = NotificationChannel(id, context.getString(R.string.bipe_app_notification_channel_messages), NotificationManager.IMPORTANCE_HIGH)
                // Bipe's soft sound has no asset yet, so soft plays the system sound.
                if (sound(userId) == NotificationSound.SILENT) messages.setSound(null, null)
                system.createNotificationChannel(messages)
            }
        }
        return id
    }

    /** One channel per account and sound. The account part is a digest: a channel ID is never shown, but stays private. */
    private fun messagesChannelId(userId: String): String =
        "messages_" + sound(userId).name.lowercase() + "_" + accountDigest(userId)

    /** Plain body or decrypted attachment kind/caption; never fetch a file or use its computed filename. */
    private fun messageText(event: TimelineEvent): String? {
        val content = (runCatching { event.content() }.getOrNull() as? TimelineEventContent.MessageLike)?.content ?: return null
        if (content is MessageLikeEventContent.Sticker) return context.getString(R.string.bipe_app_media_sticker)
        val message = content as? MessageLikeEventContent.RoomMessage ?: return null
        return when (val type = message.messageType) {
            is MessageType.Text -> BipeNotificationPreview.text(type.content.body)
            is MessageType.Emote -> BipeNotificationPreview.text(type.content.body)
            is MessageType.Notice -> BipeNotificationPreview.text(type.content.body)
            is MessageType.Image -> attachmentPreview(R.string.bipe_app_media_image, type.content.caption)
            is MessageType.Video -> attachmentPreview(R.string.bipe_app_media_video, type.content.caption)
            is MessageType.File -> attachmentPreview(R.string.bipe_app_media_file, type.content.caption)
            is MessageType.Audio -> attachmentPreview(if (type.content.voice != null) R.string.bipe_app_media_voice else R.string.bipe_app_media_file, type.content.caption)
            else -> null
        }
    }

    private fun attachmentPreview(label: Int, caption: String?): String =
        BipeNotificationPreview.attachment(context.getString(label), caption)

    companion object {
        const val ACTION_OPEN_CHAT = "chat.bipe.app.OPEN_CHAT"
        const val EXTRA_ACCOUNT_USER_ID = "account_user_id"
        const val EXTRA_ACCOUNT_ROUTE = "account_route"
        const val EXTRA_KIND = "notification_kind"
        const val EXTRA_ROOM_ID = "room_id"
        private const val OLD_INVITES_CHANNEL = "invites"
        private const val KEY_SOUND = "sound"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_WORK_HOURS = "workHours"
        private const val FALLBACK_CHANNEL = "messages"

        /** A short stable digest of a Matrix ID, for channel IDs. */
        fun accountDigest(userId: String): String =
            MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        private const val KEY_ROUTES = "accountRoutes.v1"
        private const val NOTIFICATION_ID = 1
        private const val INVITE_ID = 2
        private const val FAILURE_ID = 3
        private const val MAX_MESSAGES = 8
        private const val FETCH_TIMEOUT_MS = 25_000L
    }
}

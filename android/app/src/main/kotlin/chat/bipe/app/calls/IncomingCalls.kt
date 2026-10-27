// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import chat.bipe.app.BipeApplication
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.shared.calls.BipeCallRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.SyncNotificationListener
import org.matrix.rustcomponents.sdk.TaskHandle

/** One ringing call of spec incoming-call. */
data class IncomingCall(
    val accountUserId: String,
    val roomId: String,
    val ringEventId: String,
    val callerName: String,
    val callerAvatarUrl: String?,
    val video: Boolean,
    val expiresAt: Long,
)

/** Spec incoming-call: rings for a DM call while the app holds a sync connection, then answer, decline, elsewhere or missed. */
object IncomingCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ringing = MutableStateFlow<IncomingCall?>(null)
    val ringing: StateFlow<IncomingCall?> = _ringing.asStateFlow()
    private val sessions = mutableMapOf<String, AccountSession>()
    private var timer: Job? = null
    private var roomInfoHandle: TaskHandle? = null
    private var tone: RingTone? = null
    private lateinit var appContext: Context

    /** Listens to the account's sync notifications for ring events. */
    fun watch(context: Context, session: AccountSession) {
        appContext = context.applicationContext
        if (sessions[session.userId] === session) return
        sessions[session.userId] = session
        scope.launch {
            session.client.registerNotificationHandler(object : SyncNotificationListener {
                override fun onNotification(notification: NotificationItem, roomId: String) {
                    scope.launch { received(notification, roomId, session) }
                }
            })
        }
    }

    /**
     * A push (play flavour, bipe-chat/bipe#760) brought this item: a ring rings the phone, also locked, and gets no
     * message notification. true when the item was a ring event.
     */
    fun fromPush(context: Context, session: AccountSession, item: NotificationItem, roomId: String): Boolean {
        appContext = context.applicationContext
        sessions.putIfAbsent(session.userId, session)
        return received(item, roomId, session)
    }

    private fun received(item: NotificationItem, roomId: String, session: AccountSession): Boolean {
        val event = runCatching { Json.parseToJsonElement(item.rawEvent) as? JsonObject }.getOrNull() ?: return false
        if (event.text("type") != BipeCallRing.EVENT_TYPE) return false
        val eventId = event.text("event_id") ?: return true
        val sender = event.text("sender") ?: return true
        val content = event["content"] as? JsonObject ?: return true
        val now = System.currentTimeMillis()
        val read = BipeCallRing.read(content, sender, event.number("origin_server_ts") ?: now, session.userId, now)
        // A group call notification stays a normal notification.
        if (read.notify) return false
        if (!read.ring || _ringing.value?.ringEventId == eventId || _ringing.value != null || CallCenter.isInCall(roomId)) return true
        start(IncomingCall(session.userId, roomId, eventId, item.senderInfo.displayName ?: sender, item.senderInfo.avatarUrl, read.video, read.expiresAt))
        return true
    }

    private fun start(call: IncomingCall) {
        val session = sessions[call.accountUserId] ?: return
        _ringing.value = call
        tone = RingTone(appContext).also { it.start() }
        // The call notification (CallStyle, full-screen intent) in a phoneCall foreground service; the screen at once
        // when the app is in front.
        CallService.ring(appContext)
        if (CallService.isAppInFront()) {
            appContext.startActivity(Intent(appContext, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        TelecomCalls.incoming(appContext, call.callerName, call.video, onAnswer = { answerFromSystem() }, onDisconnect = { decline() })
        val room = runCatching { session.client.getRoom(call.roomId) }.getOrNull()
        // Answered on another device of the own user, or the caller hung up.
        roomInfoHandle = room?.subscribeToRoomInfoUpdates(object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                scope.launch { roomChanged(roomInfo, call, session.userId) }
            }
        })
        timer?.cancel()
        timer = scope.launch {
            delay((call.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            missed(call)
        }
    }

    private fun roomChanged(info: RoomInfo, call: IncomingCall, ownUserId: String) {
        if (_ringing.value != call) return
        when {
            ownUserId in info.activeRoomCallParticipants -> stop()
            !info.hasRoomCall -> missed(call)
        }
    }

    /** Answer from a headset, a car or the system call UI (core-telecom): the call screen opens. */
    private fun answerFromSystem() {
        val intent = Intent(appContext, IncomingCallActivity::class.java).setAction(IncomingCallActivity.ACTION_ANSWER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    fun answer(context: Context) {
        val call = _ringing.value ?: return
        stop(answered = true)
        TelecomCalls.answered()
        val session = sessions[call.accountUserId] ?: return
        val room = runCatching { session.client.getRoom(call.roomId) }.getOrNull() ?: return
        scope.launch { CallCenter.start(context.applicationContext, session, room, call.callerName, call.video) }
    }

    fun decline() {
        val call = _ringing.value ?: return
        stop()
        val session = sessions[call.accountUserId] ?: return
        val room = runCatching { session.client.getRoom(call.roomId) }.getOrNull() ?: return
        scope.launch { runCatching { room.declineCall(call.ringEventId) } }
    }

    private fun missed(call: IncomingCall) {
        if (_ringing.value != call) return
        stop()
        MissedCalls.record(call.accountUserId, call.roomId)
        val target = BipeNotificationTarget.create(call.accountUserId, call.roomId) ?: return
        (appContext as? BipeApplication)?.notifications?.missedCall(target, call.callerName, call.ringEventId)
    }

    /** Avatars of the ringing screen. */
    fun media(call: IncomingCall): MediaLoader? = sessions[call.accountUserId]?.let { MediaLoader(it.client) }

    private fun stop(answered: Boolean = false) {
        if (!answered) TelecomCalls.end()
        CallService.ringEnded(appContext)
        tone?.stop()
        tone = null
        timer?.cancel()
        timer = null
        roomInfoHandle?.cancel()
        roomInfoHandle = null
        _ringing.value = null
    }
}

/** The missed calls of each account, for the chat list preview (spec incoming-call timeout). */
object MissedCalls {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bipe_missed_calls", Context.MODE_PRIVATE)
    }

    fun record(accountUserId: String, roomId: String) {
        val prefs = prefs ?: return
        prefs.edit().putStringSet(accountUserId, rooms(accountUserId) + roomId).apply()
    }

    fun isMissed(accountUserId: String, roomId: String): Boolean = roomId in rooms(accountUserId)

    /** The chat was opened: the preview shows the call kind again. */
    fun clear(accountUserId: String, roomId: String) {
        val prefs = prefs ?: return
        if (roomId in rooms(accountUserId)) prefs.edit().putStringSet(accountUserId, rooms(accountUserId) - roomId).apply()
    }

    private fun rooms(accountUserId: String): Set<String> = prefs?.getStringSet(accountUserId, emptySet()).orEmpty().toSet()
}

/**
 * The in-app ring (spec incoming-call notes): the ringtone of bipe_notifications_call_sound, else the phone's, with the
 * ringtone audio attributes, and vibration when the phone rings or is on vibrate.
 */
class RingTone(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start() {
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = CallSound.uri(context) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()?.also { tone ->
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
                tone.play()
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0)) }
        }
    }

    fun stop() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }
}

/** The ringtone the user picked for calls (bipe_notifications_call_sound). null: the phone's ringtone. */
object CallSound {
    private const val KEY = "call_sound"

    private fun prefs(context: Context) = context.getSharedPreferences("bipe_call_sound", Context.MODE_PRIVATE)

    fun uri(context: Context): Uri? = prefs(context).getString(KEY, null)?.let(Uri::parse)

    fun set(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY, uri?.toString()).apply()
    }
}

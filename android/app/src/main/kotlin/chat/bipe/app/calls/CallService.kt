// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.bipe.shared.R

/**
 * Spec call and incoming-call: the foreground service of a ringing call (type phoneCall, a CallStyle incoming
 * notification with a full-screen intent, bipe-chat/bipe#760) and of a running call (microphone, camera and phoneCall,
 * a CallStyle ongoing notification with return, mute and end).
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> CallCenter.active.value?.toggleMicrophone()
            ACTION_END -> CallCenter.active.value?.end()
            ACTION_DECLINE -> IncomingCalls.decline()
        }
        val call = CallCenter.active.value
        val ringing = IncomingCalls.ringing.value
        val notification: Notification
        var type: Int
        when {
            call != null && intent?.action != ACTION_RING -> {
                notification = ongoing(call)
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or PHONE_CALL_TYPE
                if (intent?.getBooleanExtra(EXTRA_CAMERA, false) == true || call.ui.value.cameraOn) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            ringing != null -> {
                notification = incoming(ringing)
                type = PHONE_CALL_TYPE
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 30) type else 0) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    /** CallStyle.forIncomingCall: answer opens the ringing screen, which joins; decline needs no screen. */
    private fun incoming(call: IncomingCall): Notification {
        createChannels(this)
        val screen = Intent(this, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val full = PendingIntent.getActivity(this, 10, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val answer = PendingIntent.getActivity(this, 11, Intent(screen).setAction(IncomingCallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val person = Person.Builder().setName(call.callerName).setImportant(true).build()
        val builder = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(chat.bipe.app.R.drawable.ic_notification)
            .setContentTitle(call.callerName)
            .setContentText(getString(if (call.video) R.string.bipe_call_incoming_video else R.string.bipe_call_incoming_voice, call.callerName))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, action(ACTION_DECLINE, 12), answer).setIsVideo(call.video))
        // Android 14 and later: the full-screen intent needs the user's grant; without it the notification is a heads-up.
        val system = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 34 || system.canUseFullScreenIntent()) builder.setFullScreenIntent(full, true)
        return builder.build()
    }

    /** CallStyle.forOngoingCall with return and mute next to hang up. */
    private fun ongoing(call: CallSession): Notification {
        createChannels(this)
        val muted = !call.ui.value.microphoneOn
        val open = PendingIntent.getActivity(this, 0, Intent(this, CallActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder().setName(call.roomName).setImportant(true).build()
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(chat.bipe.app.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.bipe_call_ongoing))
            .setContentText(call.roomName)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, action(ACTION_END, 2)).setIsVideo(call.video))
            .addAction(0, getString(R.string.bipe_call_return), open)
            .addAction(0, getString(if (muted) R.string.bipe_call_unmute else R.string.bipe_call_mute), action(ACTION_MUTE, 1))
            .build()
    }

    private fun action(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, CallService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CHANNEL_ONGOING = "calls_ongoing"
        private const val CHANNEL_INCOMING = "calls_incoming"
        private const val NOTIFICATION_ID = 7_001
        private const val ACTION_START = "chat.bipe.app.call.START"
        private const val ACTION_RING = "chat.bipe.app.call.RING"
        private const val ACTION_UPDATE = "chat.bipe.app.call.UPDATE"
        private const val ACTION_MUTE = "chat.bipe.app.call.MUTE"
        private const val ACTION_END = "chat.bipe.app.call.END"
        private const val ACTION_DECLINE = "chat.bipe.app.call.DECLINE"
        private const val EXTRA_CAMERA = "camera"
        private val PHONE_CALL_TYPE = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0

        fun start(context: Context, camera: Boolean) {
            val intent = Intent(context, CallService::class.java).setAction(ACTION_START).putExtra(EXTRA_CAMERA, camera)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** The ringing call of IncomingCalls. */
        fun ring(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(ACTION_RING)) }
        }

        /** The ring ended: the service goes on only for a running call. */
        fun ringEnded(context: Context) {
            if (CallCenter.active.value == null) stop(context) else update(context)
        }

        /** Redraws the notification (mute state, camera). */
        fun update(context: Context) {
            runCatching { context.startService(Intent(context, CallService::class.java).setAction(ACTION_UPDATE)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        /** The app shows a screen now: the ringing screen can open without the full-screen intent. */
        fun isAppInFront(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        private fun createChannels(context: Context) {
            val system = context.getSystemService(NotificationManager::class.java)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.bipe_call_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
            )
            // The app plays the ringtone itself (RingTone): the channel has no sound.
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.bipe_call_channel_incoming), NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) },
            )
        }
    }
}

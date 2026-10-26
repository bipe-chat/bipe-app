// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.bipe.shared.R

/**
 * Spec call: keeps the microphone (and the camera) of a running call while the app is in the background, with the
 * ongoing notification bipe_call_ongoing and its actions return, mute and end.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = CallCenter.active.value
        when (intent?.action) {
            ACTION_MUTE -> call?.toggleMicrophone()
            ACTION_END -> call?.end()
        }
        if (call == null && intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val camera = intent?.getBooleanExtra(EXTRA_CAMERA, false) ?: false
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (camera || intent?.action != ACTION_START && call?.ui?.value?.cameraOn == true) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 30) type else 0) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(): android.app.Notification {
        createChannel(this)
        val call = CallCenter.active.value
        val muted = call?.ui?.value?.microphoneOn == false
        val open = PendingIntent.getActivity(this, 0, Intent(this, CallActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(chat.bipe.app.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.bipe_call_ongoing))
            .setContentText(call?.roomName)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, getString(R.string.bipe_call_return), open)
            .addAction(0, getString(if (muted) R.string.bipe_call_unmute else R.string.bipe_call_mute), action(ACTION_MUTE, 1))
            .addAction(0, getString(R.string.bipe_call_end), action(ACTION_END, 2))
            .build()
    }

    private fun action(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, CallService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CHANNEL = "calls_ongoing"
        private const val NOTIFICATION_ID = 7_001
        private const val ACTION_START = "chat.bipe.app.call.START"
        private const val ACTION_UPDATE = "chat.bipe.app.call.UPDATE"
        private const val ACTION_MUTE = "chat.bipe.app.call.MUTE"
        private const val ACTION_END = "chat.bipe.app.call.END"
        private const val EXTRA_CAMERA = "camera"

        fun start(context: Context, camera: Boolean) {
            val intent = Intent(context, CallService::class.java).setAction(ACTION_START).putExtra(EXTRA_CAMERA, camera)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Redraws the notification (mute state). */
        fun update(context: Context) {
            runCatching { context.startService(Intent(context, CallService::class.java).setAction(ACTION_UPDATE)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        private fun createChannel(context: Context) {
            val system = context.getSystemService(NotificationManager::class.java)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.bipe_call_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.content.Context
import android.os.Handler
import android.os.Looper
import chat.bipe.app.BipeApplication
import chat.bipe.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging, the push transport of the Play flavour (ADR 0150). The Firebase project's client values
 * come from the build (Gradle properties bipeFirebase*, set by CI from repository variables): a build without them has
 * no transport, as before. No google-services.json and no Firebase plugin; only Messaging, no analytics.
 */
object PushTransports {
    fun create(app: BipeApplication): PushTransport? {
        if (!initialize(app)) return null
        return object : PushTransport {
            override fun requestToken() {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    if (!token.isNullOrEmpty()) app.push.tokenReceived(token)
                }
            }
        }
    }

    /** Starts the Bipe Firebase app once. False when the build has no Firebase values. */
    fun initialize(context: Context): Boolean {
        val values = listOf(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_SENDER_ID)
        if (values.any { it.isBlank() }) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val options = FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        return runCatching { FirebaseApp.initializeApp(context, options) }.isSuccess
    }
}

/** Receives the FCM token and the content-free data messages, and hands them to the app on the main thread. */
class BipeMessagingService : FirebaseMessagingService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        val app = application as BipeApplication
        main.post { app.push.tokenReceived(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as BipeApplication
        val data = HashMap(message.data)
        main.post { app.pushDataReceived(data) }
    }
}

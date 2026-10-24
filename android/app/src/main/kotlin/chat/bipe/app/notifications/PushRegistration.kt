// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind

/**
 * The system push service of this build. FCM comes in a follow-up, after the ADR on the Play services licence
 * and the Firebase project (bipe-chat/bipe#89). Until then no transport is set and the phone gets no pushes.
 */
interface PushTransport {
    /** Asks the push service for a token. The transport calls [PushRegistration.tokenReceived] with it. */
    fun requestToken()
}

/**
 * Spec push-registration (bipe-shared product/screens/push-registration.json). No screen: it registers this
 * phone with Bipe's Sygnal when the person allows notifications, and removes the pusher at sign-out.
 */
class PushRegistration(private val context: Context, private val scope: CoroutineScope) {
    enum class Permission { NOT_ASKED, ALLOWED, DENIED }

    /** Type PushRegistration, saved so a new token or language can be compared with it. */
    data class Registration(val userId: String, val pushkey: String, val appId: String, val lang: String, val registeredAt: Long)

    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val _permission = MutableStateFlow(readPermission())
    val permission: StateFlow<Permission> = _permission.asStateFlow()
    private val _registration = MutableStateFlow(loadRegistration())
    val registration: StateFlow<Registration?> = _registration.asStateFlow()

    var transport: PushTransport? = null
    private var session: AccountSession? = null

    private val isDebug: Boolean
        get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** The account is ready: keep its pusher current. */
    fun attach(session: AccountSession) {
        this.session = session
        appBecameActive()
    }

    /**
     * Action appBecameActive: reads the permission and asks for a token when allowed. A denied phone keeps
     * its pusher, so notifications come back when the person allows them again.
     */
    fun appBecameActive() {
        _permission.value = readPermission()
        if (_permission.value == Permission.ALLOWED) transport?.requestToken()
    }

    /** The system dialog was shown (Android 13 and later). Only notifications-prompt and notification-settings ask. */
    fun permissionAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        appBecameActive()
    }

    /** Action tokenReceived: sets the HTTP pusher when the token, the account or the language changed. */
    fun tokenReceived(pushkey: String) {
        val session = session ?: return
        if (_permission.value != Permission.ALLOWED) return
        val appId = AppConfig.pusherAppId(context.packageName, isDebug)
        val lang = appLanguage()
        val current = _registration.value
        if (current != null && current.userId == session.userId && current.pushkey == pushkey && current.appId == appId && current.lang == lang) return
        scope.launch {
            runCatching {
                // Content-free: event_id_only. FCM gets a data message with the room ID, the event ID and the count.
                session.client.setPusher(
                    identifiers = PusherIdentifiers(pushkey, appId),
                    kind = PusherKind.Http(HttpPusherData(AppConfig.PUSH_GATEWAY_URL, PushFormat.EVENT_ID_ONLY, null)),
                    appDisplayName = context.getString(R.string.bipe_app_name),
                    deviceDisplayName = Build.MODEL,
                    profileTag = null,
                    lang = lang,
                    append = false,
                )
                save(Registration(session.userId, pushkey, appId, lang, System.currentTimeMillis()))
            }
        }
    }

    /** Action signOut: deletes the pusher of this phone before the session ends. A failure does not stop the sign-out. */
    suspend fun signOut() {
        val session = session
        val registration = _registration.value
        if (session != null && registration != null) {
            runCatching { session.client.deletePusher(PusherIdentifiers(registration.pushkey, registration.appId)) }
        }
        save(null)
        this.session = null
    }

    private fun readPermission(): Permission {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return when {
            granted -> Permission.ALLOWED
            prefs.getBoolean(KEY_ASKED, false) -> Permission.DENIED
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> Permission.DENIED
            else -> Permission.NOT_ASKED
        }
    }

    /** pt-BR or en, the languages of the generated strings. */
    private fun appLanguage(): String = if (Locale.getDefault().language == "en") "en" else "pt-BR"

    private fun loadRegistration(): Registration? = prefs.getString(KEY_REGISTRATION, null)?.let { text ->
        runCatching {
            val json = JSONObject(text)
            Registration(json.getString("userId"), json.getString("pushkey"), json.getString("appId"), json.getString("lang"), json.getLong("registeredAt"))
        }.getOrNull()
    }

    private fun save(registration: Registration?) {
        _registration.value = registration
        val editor = prefs.edit()
        if (registration == null) {
            editor.remove(KEY_REGISTRATION)
        } else {
            editor.putString(
                KEY_REGISTRATION,
                JSONObject()
                    .put("userId", registration.userId)
                    .put("pushkey", registration.pushkey)
                    .put("appId", registration.appId)
                    .put("lang", registration.lang)
                    .put("registeredAt", registration.registeredAt)
                    .toString(),
            )
        }
        editor.apply()
    }

    private companion object {
        const val KEY_ASKED = "permissionAsked"
        const val KEY_REGISTRATION = "registration"
    }
}

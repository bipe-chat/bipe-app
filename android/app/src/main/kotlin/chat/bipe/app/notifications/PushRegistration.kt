// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeApplication
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.BipePushMessage
import chat.bipe.shared.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind

/**
 * The system push service of this build: Firebase Cloud Messaging in the Play flavour (ADR 0150), when the build has
 * the Firebase project's values (PushTransports); none in the foss flavour. Without a transport the phone gets no pushes.
 */
interface PushTransport {
    /** Asks the push service for a token. The transport calls [PushRegistration.tokenReceived] with it. */
    fun requestToken()
}

/**
 * Spec push-registration (bipe-shared product/screens/push-registration.json). No screen: it registers this
 * phone with Bipe's Sygnal when the person allows notifications, with one pusher for each session on the phone (every
 * account and every Interno member session), and removes a session's pusher when that session signs out.
 */
class PushRegistration(private val context: Context, private val scope: CoroutineScope) {
    enum class Permission { NOT_ASKED, ALLOWED, DENIED }

    /** Type PushRegistration, saved per Matrix ID so a new token, language or route can be compared with it. */
    data class Registration(
        val userId: String,
        val pushkey: String,
        val appId: String,
        val lang: String,
        val registeredAt: Long,
        /** The random account route in the pusher's default_payload. `null` for a pusher from before routing. */
        val accountRoute: String? = null,
    ) {
        /** The same pusher on the server: only [registeredAt] may differ. */
        fun isSamePusher(other: Registration): Boolean =
            userId == other.userId && pushkey == other.pushkey && appId == other.appId && lang == other.lang &&
                accountRoute == other.accountRoute
    }

    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val _permission = MutableStateFlow(readPermission())
    val permission: StateFlow<Permission> = _permission.asStateFlow()

    /** State registrations: the pushers this phone set, by Matrix ID. */
    private val _registrations = MutableStateFlow(loadRegistrations())
    val registrations: StateFlow<Map<String, Registration>> = _registrations.asStateFlow()

    var transport: PushTransport? = null

    /** Every session on the phone that gets a pusher, by Matrix ID: the accounts and the Interno member sessions. */
    private val sessions = MutableStateFlow<Map<String, AccountSession>>(emptyMap())

    /**
     * The pushers set in this process, by Matrix ID. The saved registration alone does not prove the pusher is on the
     * server: Synapse deletes a pusher when Sygnal rejects its token. So each launch sets each pusher once again.
     */
    private val setInProcess = ConcurrentHashMap<String, Registration>()

    /** One registration run at a time, so append false (see [register]) never runs after another session's pusher. */
    private val mutex = Mutex()

    private val isDebug: Boolean
        get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Action sessionAdded: an account or an Interno member session opened, or was built again. It gets its pusher. */
    fun add(session: AccountSession) {
        sessions.update { it + (session.userId to session) }
        setInProcess.remove(session.userId)
        appBecameActive()
    }

    /**
     * Action signOut: deletes the session's pusher before the session ends. The other sessions keep theirs. A failure
     * does not stop the sign-out.
     */
    suspend fun remove(userId: String) {
        // The lock waits for a registration that runs now, so its pusher cannot land after this delete.
        mutex.withLock {
            val session = sessions.value[userId]
            sessions.update { it - userId }
            setInProcess.remove(userId)
            val saved = _registrations.value[userId]
            save(userId, null)
            if (session != null && saved != null) {
                runCatching { session.client.deletePusher(PusherIdentifiers(saved.pushkey, saved.appId)) }
            }
        }
    }

    /**
     * Action appBecameActive: reads the permission and asks for a token when allowed. A denied phone keeps
     * its pushers, so notifications come back when the person allows them again.
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

    /**
     * Action tokenReceived: sets the pusher of each session whose token, language or route changed, or that this
     * process has not set yet.
     */
    fun tokenReceived(pushkey: String) {
        if (_permission.value != Permission.ALLOWED) return
        scope.launch { mutex.withLock { register(pushkey) } }
    }

    private suspend fun register(pushkey: String) {
        val appId = AppConfig.pusherAppId(context.packageName, isDebug)
        val lang = appLanguage()
        val notifications = (context.applicationContext as BipeApplication).notifications
        // append true keeps the pushers of the other sessions with this token (bipe-chat/bipe#1519). Only the first
        // pusher on a phone with no saved registration (a new install, or every account signed out) uses append false,
        // once: it removes pushers that an earlier install left with this token.
        var replaceOthers = _registrations.value.isEmpty()
        for (userId in sessions.value.keys.sorted()) {
            val session = sessions.value[userId] ?: continue
            // Spec push-registration: a random route, bound on this phone to the account, travels in the data message
            // so the push opens only that account. Sygnal never gets the Matrix ID.
            val route = notifications.accountRoute(userId)
            val wanted = Registration(userId, pushkey, appId, lang, System.currentTimeMillis(), route)
            if (setInProcess[userId]?.isSamePusher(wanted) == true) continue
            val defaultPayload = JSONObject().put(BipePushMessage.ACCOUNT_ROUTE, route).toString()
            val set = runCatching {
                // Content-free: event_id_only. FCM gets a data message with the room ID, the event ID, the count and
                // the account route of default_payload.
                session.client.setPusher(
                    identifiers = PusherIdentifiers(pushkey, appId),
                    kind = PusherKind.Http(HttpPusherData(AppConfig.PUSH_GATEWAY_URL, PushFormat.EVENT_ID_ONLY, defaultPayload)),
                    appDisplayName = context.getString(R.string.bipe_app_name),
                    deviceDisplayName = Build.MODEL,
                    profileTag = null,
                    lang = lang,
                    append = !replaceOthers,
                )
            }
            val error = set.exceptionOrNull()
            if (error is CancellationException) throw error
            if (error != null) {
                Log.w(TAG, "pusher registration failed")
                continue
            }
            replaceOthers = false
            // The session signed out or was built again while the pusher was set.
            if (sessions.value[userId] !== session) continue
            val old = _registrations.value[userId]
            setInProcess[userId] = wanted
            save(userId, wanted)
            // A new token or app ID is a new pusher: the one with the old token goes.
            if (old != null && (old.pushkey != pushkey || old.appId != appId)) {
                runCatching { session.client.deletePusher(PusherIdentifiers(old.pushkey, old.appId)) }
            }
        }
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

    private fun loadRegistrations(): Map<String, Registration> {
        prefs.getString(KEY_REGISTRATIONS, null)?.let { text ->
            runCatching {
                val json = JSONObject(text)
                return json.keys().asSequence().mapNotNull { key -> parse(json.getJSONObject(key))?.let { key to it } }.toMap()
            }
        }
        // The single registration of builds before bipe-chat/bipe#1519.
        val legacy = prefs.getString(KEY_LEGACY_REGISTRATION, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
        return if (legacy == null) emptyMap() else mapOf(legacy.userId to legacy)
    }

    private fun parse(json: JSONObject): Registration? = runCatching {
        Registration(
            json.getString("userId"),
            json.getString("pushkey"),
            json.getString("appId"),
            json.getString("lang"),
            json.getLong("registeredAt"),
            json.optString("accountRoute").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    private fun save(userId: String, registration: Registration?) {
        val all = _registrations.updateAndGet { if (registration == null) it - userId else it + (userId to registration) }
        val json = JSONObject()
        all.forEach { (key, value) ->
            json.put(
                key,
                JSONObject()
                    .put("userId", value.userId)
                    .put("pushkey", value.pushkey)
                    .put("appId", value.appId)
                    .put("lang", value.lang)
                    .put("registeredAt", value.registeredAt)
                    .put("accountRoute", value.accountRoute),
            )
        }
        prefs.edit().putString(KEY_REGISTRATIONS, json.toString()).remove(KEY_LEGACY_REGISTRATION).apply()
    }

    private companion object {
        const val KEY_ASKED = "permissionAsked"
        const val KEY_REGISTRATIONS = "registrations"
        const val KEY_LEGACY_REGISTRATION = "registration"
        const val TAG = "PushRegistration"
    }
}

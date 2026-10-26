// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.content.Context
import android.content.Intent
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.shared.calls.BipeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import org.matrix.rustcomponents.sdk.Room as MatrixRoom

/** The one call of the app (spec call). The call screen is CallActivity. */
object CallCenter {
    private val _active = MutableStateFlow<CallSession?>(null)
    val active: StateFlow<CallSession?> = _active.asStateFlow()
    var media: MediaLoader? = null
        private set

    /** The LiveKit focus of each server's well-known, null when it names none. */
    private val foci = mutableMapOf<String, String?>()

    /** The focus of the account's server (org.matrix.msc4143.rtc_foci). null: no call buttons. */
    suspend fun focusUrl(session: AccountSession): String? {
        val server = session.userId.substringAfter(':', "")
        if (server.isEmpty()) return null
        if (foci.containsKey(server)) return foci[server]
        val focus = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("https://$server/.well-known/matrix/client").openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    if (connection.responseCode != 200) return@runCatching null
                    val json = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
                    json?.let(BipeCall::focusUrl)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        } ?: return null
        foci[server] = focus
        return focus
    }

    fun isInCall(roomId: String): Boolean = _active.value?.roomId == roomId

    /**
     * Starts or joins the call of a room. A call in another room stays; its screen shows instead.
     * [ring]: who to ring when the room has no call yet (spec call start); null when answering a ring.
     */
    suspend fun start(context: Context, session: AccountSession, room: MatrixRoom, roomName: String, video: Boolean, ring: CallRingTarget? = null) {
        if (_active.value == null) {
            val focus = focusUrl(session) ?: return
            val call = runCatching { CallSession(context, session, room, roomName, focus, video, ring) }.getOrNull() ?: return
            call.onFinished = { if (_active.value === call) _active.value = null }
            media = MediaLoader(session.client)
            _active.value = call
        }
        show(context)
    }

    fun show(context: Context) {
        context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Closes a call that never joined (no microphone, no connection). */
    fun dismissFailed() {
        val call = _active.value ?: return
        if (call.ui.value.phase != CallSession.Phase.FAILED) return
        call.discard()
        _active.value = null
    }
}

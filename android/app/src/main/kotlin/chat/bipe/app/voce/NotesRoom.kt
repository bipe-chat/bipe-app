// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.Context
import chat.bipe.app.account.AccountSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

/** Bipe's self-chat protocol (ADR 0053), using the same wire key as BipeSelfChat in libraries/bipe. */
class NotesRoom(context: Context, private val session: AccountSession) {
    private val preferences = context.getSharedPreferences("bipe-next-notes", Context.MODE_PRIVATE)
    private val pendingKey = "pending.${session.userId}"
    private val mutex = Mutex()
    var roomId: String? = preferences.getString(pendingKey, null)
        private set

    suspend fun refresh() {
        val raw = session.client.accountData(EVENT_TYPE) ?: return
        val id = JSONObject(raw).getString("room_id")
        require(id.startsWith("!") && id.none(Char::isWhitespace))
        roomId = id
    }

    suspend fun open(title: String): String = mutex.withLock {
        // Offline or malformed account data never means "no notes yet".
        refresh()
        val pending = preferences.getString(pendingKey, null)
        val known = roomId
        if (known != null && known != pending) {
            if (runCatching { session.client.getRoom(known) }.getOrNull() == null) session.client.awaitRoomRemoteEcho(known)
            val room = session.client.getRoom(known)
            check(room.roomInfo().membership == Membership.JOINED)
            return@withLock known
        }
        val id = pending ?: session.client.createRoom(CreateRoomParameters(
            name = title, isEncrypted = true, isDirect = false,
            visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT, invite = null,
        )).also {
            // Finish a failed metadata write on retry, including after a restart, without duplicating the room.
            withContext(Dispatchers.IO) { check(preferences.edit().putString(pendingKey, it).commit()) }
        }
        roomId = id
        session.client.setAccountData(EVENT_TYPE, JSONObject().put("room_id", id).toString())
        preferences.edit().remove(pendingKey).apply()
        session.client.awaitRoomRemoteEcho(id)
        id
    }

    private companion object { const val EVENT_TYPE = "chat.bipe.self_chat" }
}

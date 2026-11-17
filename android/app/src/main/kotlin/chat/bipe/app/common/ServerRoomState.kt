// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client

/**
 * The full room state from the server (bipe-chat/bipe#439, spec chat-timeline). The SDK has no call that returns a
 * custom state event such as the assistant reader marker, so the app reads it with the Matrix client-server API
 * GET /_matrix/client/v3/rooms/{roomId}/state. Returns the response body, a JSON array of state events; throws on any
 * failure.
 */
suspend fun serverRoomState(client: Client, roomId: String): String = withContext(Dispatchers.IO) {
    val auth = client.session()
    val room = URLEncoder.encode(roomId, "UTF-8")
    val url = URL("${auth.homeserverUrl.trimEnd('/')}/_matrix/client/v3/rooms/$room/state")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        when (connection.responseCode) {
            200 -> connection.inputStream.bufferedReader().use { it.readText() }
            else -> error("room state: HTTP ${connection.responseCode}")
        }
    } finally {
        connection.disconnect()
    }
}

/**
 * One state event's content from GET /_matrix/client/v3/rooms/{roomId}/state/{eventType}/{stateKey}, as JSON, or null
 * when the room has no such event (404). Throws on any other failure.
 */
suspend fun serverStateContent(client: Client, roomId: String, eventType: String, stateKey: String): String? = withContext(Dispatchers.IO) {
    val auth = client.session()
    fun path(part: String) = URLEncoder.encode(part, "UTF-8").replace("+", "%20")
    val url = URL("${auth.homeserverUrl.trimEnd('/')}/_matrix/client/v3/rooms/${path(roomId)}/state/${path(eventType)}/${path(stateKey)}")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        when (connection.responseCode) {
            200 -> connection.inputStream.bufferedReader().use { it.readText() }
            404 -> null
            else -> error("room state: HTTP ${connection.responseCode}")
        }
    } finally {
        connection.disconnect()
    }
}

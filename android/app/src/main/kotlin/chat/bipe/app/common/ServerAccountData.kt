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
 * The server copy of a global account data event, not Client.accountData: the SDK copy waits for the next sync, so a
 * read-merge-write from it could drop what another device just added (bipe-chat/bipe#479). Matrix client-server API
 * GET user account data. Returns the content as JSON, or null when the account has no such event.
 */
suspend fun serverAccountData(client: Client, eventType: String): String? = withContext(Dispatchers.IO) {
    val auth = client.session()
    val user = URLEncoder.encode(auth.userId, "UTF-8")
    val type = URLEncoder.encode(eventType, "UTF-8")
    val url = URL("${auth.homeserverUrl.trimEnd('/')}/_matrix/client/v3/user/$user/account_data/$type")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        when (connection.responseCode) {
            200 -> connection.inputStream.bufferedReader().use { it.readText() }
            404 -> null
            else -> error("account data: HTTP ${connection.responseCode}")
        }
    } finally {
        connection.disconnect()
    }
}

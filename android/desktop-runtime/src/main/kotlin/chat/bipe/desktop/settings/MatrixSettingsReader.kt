// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.Client

/** Fresh authenticated reads use the current SDK session. Redirects never receive the bearer token. */
class MatrixSettingsReader(private val client: Client) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    suspend fun accountData(type: String): String? {
        val user = encode(client.userId())
        return get("/_matrix/client/v3/user/$user/account_data/${encode(type)}")
    }

    suspend fun devices(): List<LinkedComputer> {
        val json = get("/_matrix/client/v3/devices") ?: error("Missing device list")
        val own = client.session().deviceId
        return Json.parseToJsonElement(json).jsonObject.getValue("devices").jsonArray.map { value ->
            val device = value.jsonObject
            val id = device.getValue("device_id").jsonPrimitive.content
            LinkedComputer(id, device["display_name"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                id == own, device["last_seen_ts"]?.jsonPrimitive?.longOrNull)
        }
    }

    private suspend fun get(path: String): String? = runInterruptible {
        val auth = client.session()
        val base = URI(auth.homeserverUrl)
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
        require(base.scheme == "https" || (base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "::1")))
        val request = HttpRequest.newBuilder(URI(auth.homeserverUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${auth.accessToken}").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            when (response.statusCode()) {
                404 -> null
                200 -> {
                    val bytes = response.body()
                    check(bytes.size <= 1024 * 1024) { "Settings response too large" }
                    bytes.toString(Charsets.UTF_8)
                }
                else -> error("Settings read failed") // No token, account id or response body enters a log.
            }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

data class LinkedComputer(val id: String, val name: String?, val current: Boolean, val lastUsed: Long?)

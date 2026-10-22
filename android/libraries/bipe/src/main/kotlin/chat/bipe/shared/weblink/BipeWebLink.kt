// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The MAS web pages of specs linked-devices and link-computer-approve (ADR 0116, ADR 0118), written from the specs,
// the Matrix spec and api/openapi.yaml.

package chat.bipe.shared.weblink

import android.net.Uri
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Rules of the private web view that opens MAS pages with the hidden password. */
object BipeWebLink {
    /** The localpart of a Matrix ID: "@ana:bipe.net.br" → "ana". */
    fun localpart(userId: String): String = userId.removePrefix("@").substringBefore(':')

    /** The web view loads only https pages on the MAS host. */
    fun isAllowed(url: String, host: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(host, ignoreCase = true)
    }

    /** The MAS account page that ends one device's session (MSC2965 account management actions). */
    fun sessionEndUrl(accountManagementUri: String, deviceId: String): String = Uri.parse(accountManagementUri).buildUpon()
        .appendQueryParameter("action", "org.matrix.session_end")
        .appendQueryParameter("device_id", deviceId)
        .build()
        .toString()

    /**
     * JavaScript that fills the MAS login form once and submits it. The values go in as JSON strings, so they are
     * escaped. Returns true when the page had a password field.
     */
    fun loginScript(username: String, password: String): String {
        val values = Json.encodeToString(ListSerializer(String.serializer()), listOf(username, password))
        return """
            (function (v) {
              var field = document.querySelector('form input[type=password]');
              if (!field) { return false; }
              var form = field.form;
              var user = form.querySelector('input[name=username]');
              if (user) { user.value = v[0]; }
              field.value = v[1];
              if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
              return true;
            })($values);
        """.trimIndent()
    }

    /** JavaScript that answers whether the page has a password field (the MAS login form). */
    const val IS_LOGIN_FORM_SCRIPT = "!!document.querySelector('form input[type=password]')"
}

/** The parts of the auth metadata that the app reads (MSC2965). */
@Serializable
data class BipeAuthMetadata(
    @SerialName("issuer") val issuer: String,
    @SerialName("account_management_uri") val accountManagementUri: String? = null,
) {
    /** The MAS host, from the account management URI, else the issuer. */
    val host: String? get() = Uri.parse(accountManagementUri ?: issuer).host
}

/** One device of the account (Matrix spec client-server, GET /_matrix/client/v3/devices). */
@Serializable
data class BipeMatrixDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("last_seen_ts") val lastSeenTs: Long? = null,
)

@Serializable
private data class DevicesResponse(@SerialName("devices") val devices: List<BipeMatrixDevice>)

/** The few Matrix client-server calls that the SDK has no FFI for (spec linked-devices notes). */
class BipeMatrixAccountApi(homeserver: String) {
    private val root = homeserver.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /** `GET /_matrix/client/v3/devices`. */
    suspend fun devices(accessToken: String): List<BipeMatrixDevice> =
        json.decodeFromString(DevicesResponse.serializer(), get("/_matrix/client/v3/devices", accessToken)).devices

    /** `GET /_matrix/client/v1/auth_metadata`, then the unstable MSC2965 path. */
    suspend fun authMetadata(): BipeAuthMetadata {
        val text = try {
            get("/_matrix/client/v1/auth_metadata", null)
        } catch (error: BipeApiException) {
            get("/_matrix/client/unstable/org.matrix.msc2965/auth_metadata", null)
        }
        return json.decodeFromString(BipeAuthMetadata.serializer(), text)
    }

    private suspend fun get(path: String, accessToken: String?): String = withContext(Dispatchers.IO) {
        val connection = try {
            URL(root + path).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw BipeApiException.Network(error)
        }
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val status = connection.responseCode
            if (status !in 200..299) throw BipeApiException.UnexpectedResponse(status)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } catch (error: IOException) {
            throw BipeApiException.Network(error)
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class WebLinkFinish(@SerialName("since") val since: String)

@Serializable
private data class WebLinkFinished(@SerialName("finished") val finished: Int = 0)

/** `POST /v1/web-link/finish`: ends the MAS browser sessions that the private web view made (ADR 0118). */
suspend fun BipeApiClient.finishWebLink(since: Instant, accessToken: String) {
    send(
        "POST",
        listOf("v1", "web-link", "finish"),
        body = encode(WebLinkFinish.serializer(), WebLinkFinish(since.toString())),
        accessToken = accessToken,
        response = WebLinkFinished.serializer(),
    )
}

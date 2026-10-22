// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.weblink

import chat.bipe.shared.runCatchingExceptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/**
 * The Matrix and MAS calls that the web sign-in needs and the SDK does not offer for a `m.login.password` session.
 * [callFactory] must not log headers: requests carry the Matrix access token.
 */
class BipeMatrixAccountApi(
    private val callFactory: Call.Factory,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** `true` when `/versions` of [homeserverUrl] advertises QR sign-in (MSC4108). */
    suspend fun supportsQrLogin(homeserverUrl: String): Boolean = runCatchingExceptions {
        val body = get(homeserverUrl, listOf("_matrix", "client", "versions"), accessToken = null)
        json.parseToJsonElement(body).jsonObject["unstable_features"]
            ?.jsonObject
            ?.get(BipeWebLink.QR_LOGIN_FEATURE)
            ?.jsonPrimitive
            ?.booleanOrNull == true
    }.getOrDefault(false)

    /** The MAS issuer and account page (MSC2965). The stable path first, then the unstable one. */
    suspend fun authMetadata(homeserverUrl: String): BipeAuthMetadata {
        val body = runCatchingExceptions { get(homeserverUrl, listOf("_matrix", "client", "v1", "auth_metadata"), accessToken = null) }
            .getOrElse { get(homeserverUrl, listOf("_matrix", "client", "unstable", "org.matrix.msc2965", "auth_metadata"), accessToken = null) }
        return json.decodeFromString(BipeAuthMetadata.serializer(), body)
    }

    /** `GET /_matrix/client/v3/devices`: every device of the account, this one too. */
    suspend fun devices(homeserverUrl: String, accessToken: String): List<BipeMatrixDevice> {
        val body = get(homeserverUrl, listOf("_matrix", "client", "v3", "devices"), accessToken)
        return json.decodeFromString(DevicesResponse.serializer(), body).devices
    }

    private suspend fun get(homeserverUrl: String, path: List<String>, accessToken: String?): String = withContext(Dispatchers.IO) {
        val base = homeserverUrl.toHttpUrlOrNull() ?: throw IOException("Invalid homeserver URL")
        val url = path.fold(base.newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .build()
        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }

    @Serializable
    private data class DevicesResponse(@SerialName("devices") val devices: List<BipeMatrixDevice> = emptyList())
}

/** MSC2965 authorization server metadata. Only the fields the web sign-in uses. */
@Serializable
data class BipeAuthMetadata(
    @SerialName("issuer") val issuer: String,
    @SerialName("account_management_uri") val accountManagementUri: String? = null,
) {
    /** The MAS host. The private web view loads only this host. */
    val host: String? get() = issuer.toHttpUrlOrNull()?.host?.lowercase()
}

/** One device of the account (`GET /_matrix/client/v3/devices`). */
@Serializable
data class BipeMatrixDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("last_seen_ts") val lastSeenTs: Long? = null,
)

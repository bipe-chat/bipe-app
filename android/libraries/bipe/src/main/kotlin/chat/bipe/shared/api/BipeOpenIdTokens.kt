// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Matrix OpenID token (Matrix spec client-server, OpenID). The core bipe-api routes take it in place of the Synapse
 * access token (bipe-chat/bipe#646, contract schemes `matrixOpenId` and `matrixServerName`).
 */
data class BipeOpenIdToken(
    val accessToken: String,
    val matrixServerName: String,
    /** Milliseconds since the epoch. The app asks for a new token after this time. */
    internal val renewAtMillis: Long,
)

/**
 * Requests and caches the account's OpenID token, in memory only. One instance per account session. A token is renewed
 * 60 seconds before `expires_in` ends. Same as BipeKit `BipeOpenIDTokens` on iOS.
 */
class BipeOpenIdTokens(
    homeserverUrl: String,
    private val userId: String,
    /** The Synapse access token. Read before every request: the SDK may refresh it. */
    private val accessToken: suspend () -> String?,
    /** Milliseconds since the epoch. Tests can pass a fake. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val homeserver = BipeApiClient(homeserverUrl)
    private val mutex = Mutex()
    @Volatile private var cached: BipeOpenIdToken? = null

    /** The cached token, or a new one when there is none or it is near its end. */
    suspend fun token(): BipeOpenIdToken = mutex.withLock {
        cached?.takeIf { clock() < it.renewAtMillis } ?: request()
    }

    /** A new token after bipe-api refused [replacing]. A token that another call got after it is used as is. */
    suspend fun token(replacing: BipeOpenIdToken): BipeOpenIdToken = mutex.withLock {
        cached?.takeIf { it != replacing && clock() < it.renewAtMillis } ?: request()
    }

    /** `POST /_matrix/client/v3/user/{userId}/openid/request_token` with an empty body. Call with [mutex] held. */
    private suspend fun request(): BipeOpenIdToken {
        cached = null
        val response = BipeApiClient.withTokenRetry(accessToken) { token ->
            homeserver.send(
                "POST",
                listOf("_matrix", "client", "v3", "user", userId, "openid", "request_token"),
                body = "{}",
                accessToken = token,
                response = OpenIdResponse.serializer(),
            )
        }
        val lifetimeMillis = ((response.expiresIn - RENEW_MARGIN_SECONDS) * 1000).coerceAtLeast(0)
        return BipeOpenIdToken(response.accessToken, response.matrixServerName, clock() + lifetimeMillis).also { cached = it }
    }

    @Serializable
    private data class OpenIdResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("matrix_server_name") val matrixServerName: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    private companion object {
        const val RENEW_MARGIN_SECONDS = 60L
    }
}


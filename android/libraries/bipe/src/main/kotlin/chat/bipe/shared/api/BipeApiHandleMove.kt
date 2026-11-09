// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Handle move to a work account (bipe ADR 0287), from the same contract as BipeKit BipeHandleMove.swift. */

/** `GET /v1/me/handle` with a Matrix access token, for another account on this phone. */
suspend fun BipeApiClient.myHandle(accessToken: String): BipeMyHandle =
    send("GET", listOf("v1", "me", "handle"), accessToken = accessToken, response = BipeMyHandle.serializer())

/** `POST /v1/me/handle/move`. The caller is a personal account with an @. */
suspend fun BipeApiClient.startHandleMove(accessToken: String): BipeHandleMoveCode =
    send("POST", listOf("v1", "me", "handle", "move"), accessToken = accessToken, response = BipeHandleMoveCode.serializer())

/** `POST /v1/me/handle/move/claim`. The caller is a work account with no @; it takes the @ and its public feed. */
suspend fun BipeApiClient.claimHandleMove(code: String, accessToken: String): BipeMyHandle =
    send("POST", listOf("v1", "me", "handle", "move", "claim"), body = encode(MoveClaim.serializer(), MoveClaim(code)),
        accessToken = accessToken, response = BipeMyHandle.serializer())

/** Contract `HandleMoveCode`: a one-time code. The app passes it to the claim and never shows it. */
@Serializable
data class BipeHandleMoveCode(
    val code: String,
    @Serializable(with = InstantSerializer::class) @SerialName("expires_at") val expiresAt: Instant,
)

@Serializable
private data class MoveClaim(val code: String)

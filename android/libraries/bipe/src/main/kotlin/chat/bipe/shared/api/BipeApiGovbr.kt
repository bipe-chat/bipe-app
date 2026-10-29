// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import chat.bipe.shared.BadgeEnvelope
import chat.bipe.shared.BadgeLevel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

// gov.br verification, trust step 5 (bipe-chat/bipe#544). Contract: bipe-shared/api/openapi.yaml, POST
// /v1/verifications/govbr and PUT /v1/verifications/govbr/{verificationId}/document. Same calls as BipeKit
// BipeAPIClient.startGovbrVerification and sendGovbrDocument on iOS.

/** GovbrChallenge. [pdf] is base64 in the JSON. */
@Serializable
data class BipeGovbrChallenge(val verificationId: String, val pdf: String, val fileName: String, val expiresAt: String) {
    /** The challenge PDF bytes; empty when the base64 does not decode. */
    val pdfBytes: ByteArray get() = runCatching { Base64.getDecoder().decode(pdf) }.getOrDefault(ByteArray(0))
}

/** VerificationStatus.reason. */
enum class BipeGovbrReason(val id: String) {
    NOT_SIGNED("not_signed"),
    NOT_GOVBR("not_govbr"),
    WRONG_DOCUMENT("wrong_document"),
    EXPIRED("expired"),
    CPF_IN_USE("cpf_in_use"),
    CHANGED_AFTER_SIGNING("changed_after_signing"),
    ;

    companion object {
        fun fromId(id: String?): BipeGovbrReason? = entries.firstOrNull { it.id == id }
    }
}

/** VerificationStatus. */
@Serializable
data class BipeVerificationStatus(
    val verificationId: String,
    val state: String,
    val badge: BadgeEnvelope? = null,
    val reason: String? = null,
)

private val govbrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** `POST /v1/verifications/govbr`. A challenge PDF bound to this device, valid 30 minutes. */
suspend fun BipeApiClient.startGovbrVerification(accessToken: String): BipeGovbrChallenge =
    send("POST", listOf("v1", "verifications", "govbr"), accessToken = accessToken, response = BipeGovbrChallenge.serializer())

/** `PUT /v1/verifications/govbr/{id}/document`. The signed PDF. A rejection is a 200 with a reason, not an error. */
suspend fun BipeApiClient.sendGovbrDocument(verificationId: String, pdf: ByteArray, accessToken: String): BipeVerificationStatus {
    val (status, text) = exchange(
        "PUT",
        listOf("v1", "verifications", "govbr", verificationId, "document"),
        body = pdf,
        contentType = "application/pdf",
        accessToken = accessToken,
    )
    if (status == 401) throw BipeApiException.Server(BipeApiErrorCode.UNAUTHORIZED, reason = null, statusCode = 401)
    if (status !in 200..299) throw BipeApiException.UnexpectedResponse(status)
    return try {
        govbrJson.decodeFromString(BipeVerificationStatus.serializer(), text)
    } catch (error: SerializationException) {
        throw BipeApiException.UnexpectedResponse(status, error)
    } catch (error: IllegalArgumentException) {
        throw BipeApiException.UnexpectedResponse(status, error)
    }
}

@Serializable
private data class LoosePayload(val name: String? = null, val lvl: BadgeLevel? = null)

private fun BadgeEnvelope.loosePayload(): LoosePayload? = runCatching {
    govbrJson.decodeFromString(LoosePayload.serializer(), String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
}.getOrNull()

/** The `name` in the payload, read without the signature check. Only for the owner's own screen, never to show a seal. */
val BadgeEnvelope.unverifiedName: String? get() = loosePayload()?.name

/** The `lvl` in the payload, read without the signature check. Only for the owner's own trust card, never to show a seal. */
val BadgeEnvelope.unverifiedLevel: BadgeLevel? get() = loosePayload()?.lvl

/** The level of the envelope in a 200 answer, read without the signature check (see [unverifiedLevel]). */
val BipeBadgeLookup.unverifiedLevel: BadgeLevel?
    get() = (this as? BipeBadgeLookup.Envelope)?.json
        ?.let { runCatching { govbrJson.decodeFromString(BadgeEnvelope.serializer(), it) }.getOrNull() }
        ?.unverifiedLevel

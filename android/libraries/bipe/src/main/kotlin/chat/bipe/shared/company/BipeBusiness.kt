// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeBusinessClaim
import chat.bipe.shared.api.BipeBusinessDomain
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Spec business-claim (ADR 0042, ADR 0036): the calls of /v1/me/business. The server decides; the app shows.

@Serializable
private data class CnpjBody(@SerialName("cnpj") val cnpj: String)

@Serializable
private data class SignatureBody(@SerialName("signature") val signature: String) {
    override fun toString(): String = "SignatureBody"
}

@Serializable
private data class DomainBody(@SerialName("domain") val domain: String)

/** `POST /v1/me/business`. One claim per account. */
suspend fun BipeApiClient.claimBusiness(cnpj: String, accessToken: String): BipeBusinessClaim =
    send("POST", listOf("v1", "me", "business"), body = encode(CnpjBody.serializer(), CnpjBody(cnpj)), accessToken = accessToken, response = BipeBusinessClaim.serializer())

/** `DELETE /v1/me/business`. Also ends a verification. */
suspend fun BipeApiClient.withdrawBusiness(accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "business"), accessToken = accessToken, response = null)
}

/** `POST /v1/me/business/ecnpj`. [signature] is the .p7s file as it is (DER CMS); it goes out in base64. */
suspend fun BipeApiClient.proveBusinessWithEcnpj(signature: ByteArray, accessToken: String): BipeBusinessClaim = send(
    "POST",
    listOf("v1", "me", "business", "ecnpj"),
    body = encode(SignatureBody.serializer(), SignatureBody(Base64.getEncoder().encodeToString(signature))),
    accessToken = accessToken,
    response = BipeBusinessClaim.serializer(),
)

/** `POST /v1/me/business/domains`. The answer holds the TXT record to publish. */
suspend fun BipeApiClient.addBusinessDomain(domain: String, accessToken: String): BipeBusinessDomain =
    send("POST", listOf("v1", "me", "business", "domains"), body = encode(DomainBody.serializer(), DomainBody(domain)), accessToken = accessToken, response = BipeBusinessDomain.serializer())

/** `POST /v1/me/business/domains/{domain}/check`. `verifiedAt` is set when the record matched. */
suspend fun BipeApiClient.checkBusinessDomain(domain: String, accessToken: String): BipeBusinessDomain =
    send("POST", listOf("v1", "me", "business", "domains", domain, "check"), body = "{}", accessToken = accessToken, response = BipeBusinessDomain.serializer())

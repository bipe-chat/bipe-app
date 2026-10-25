// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Brand marks for resellers (ADR 0162, bipe-chat/bipe#486). Contract: bipe-shared/api/openapi.yaml, the
// /v1/me/brand-marks and /v1/brand-marks/invites routes. Same calls as BipeKit BipeBrandMarksAPI on iOS. The public
// GET /v1/brand-marks/{userId} is in BipeApiPeople.kt.

/** HeldBrandMark: a mark the caller's work account holds. */
@Serializable
data class BipeHeldBrandMark(
    val id: String,
    val brand: String,
    @SerialName("brand_name") val brandName: String,
    val label: String,
    @SerialName("granted_at") val grantedAt: String,
)

/** BrandMarkInvite: an open invite of the caller's brand. */
@Serializable
data class BipeBrandMarkInvite(
    val id: String,
    val label: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** CreatedBrandMarkInvite: the code is returned only here. */
@Serializable
data class BipeCreatedBrandMarkInvite(val invite: BipeBrandMarkInvite, val code: String, val link: String)

/** GrantedBrandMark: an account that holds the caller's mark. */
@Serializable
data class BipeGrantedBrandMark(
    val id: String,
    val reseller: String,
    val label: String,
    @SerialName("granted_at") val grantedAt: String,
)

/** GrantedBrandMarks. */
@Serializable
data class BipeGrantedBrandMarks(val invites: List<BipeBrandMarkInvite>, val marks: List<BipeGrantedBrandMark>)

/** BrandMarkInvitePreview: what an invite grants, before the reseller answers. */
@Serializable
data class BipeBrandMarkInvitePreview(
    val brand: String,
    @SerialName("brand_name") val brandName: String,
    val cnpj: String,
    val label: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class HeldBrandMarks(val marks: List<BipeHeldBrandMark>)

@Serializable
private data class BrandMarkInviteRequest(val label: String)

object BipeBrandMarkCode {
    private val pattern = Regex("""(?:^|[#/\s])([A-Za-z0-9_-]{16})(?=$|[\s.,;!?)])""")

    /** The 16-character code out of a bare code, a `/marca/#<code>` link or a pasted message, like bipe-api brandMarkCode(). */
    fun extract(raw: String): String? = pattern.find(raw.trim())?.groupValues?.get(1)
}

/** `GET /v1/me/brand-marks`. */
suspend fun BipeApiClient.heldBrandMarks(accessToken: String): List<BipeHeldBrandMark> =
    send("GET", listOf("v1", "me", "brand-marks"), accessToken = accessToken, response = HeldBrandMarks.serializer()).marks

/** `DELETE /v1/me/brand-marks/{markId}`: the brand or the reseller ends a mark. */
suspend fun BipeApiClient.endBrandMark(markId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "brand-marks", markId), accessToken = accessToken, response = null)
}

/** `GET /v1/brand-marks/invites/{code}`. 404 for every code that does not work. */
suspend fun BipeApiClient.brandMarkInvite(code: String, accessToken: String): BipeBrandMarkInvitePreview =
    send("GET", listOf("v1", "brand-marks", "invites", code), accessToken = accessToken, response = BipeBrandMarkInvitePreview.serializer())

/** `POST /v1/brand-marks/invites/{code}/accept`. */
suspend fun BipeApiClient.acceptBrandMark(code: String, accessToken: String): BipeHeldBrandMark =
    send("POST", listOf("v1", "brand-marks", "invites", code, "accept"), accessToken = accessToken, response = BipeHeldBrandMark.serializer())

/** `POST /v1/brand-marks/invites/{code}/refuse`. */
suspend fun BipeApiClient.refuseBrandMark(code: String, accessToken: String) {
    send<Unit>("POST", listOf("v1", "brand-marks", "invites", code, "refuse"), accessToken = accessToken, response = null)
}

/** `GET /v1/me/brand-marks/granted`. */
suspend fun BipeApiClient.grantedBrandMarks(accessToken: String): BipeGrantedBrandMarks =
    send("GET", listOf("v1", "me", "brand-marks", "granted"), accessToken = accessToken, response = BipeGrantedBrandMarks.serializer())

/** `POST /v1/me/brand-marks/invites`. 403 brand_not_verified without a verified claim. */
suspend fun BipeApiClient.createBrandMarkInvite(label: String, accessToken: String): BipeCreatedBrandMarkInvite = send(
    "POST",
    listOf("v1", "me", "brand-marks", "invites"),
    body = encode(BrandMarkInviteRequest.serializer(), BrandMarkInviteRequest(label)),
    accessToken = accessToken,
    response = BipeCreatedBrandMarkInvite.serializer(),
)

/** `DELETE /v1/me/brand-marks/invites/{inviteId}`. */
suspend fun BipeApiClient.cancelBrandMarkInvite(inviteId: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "brand-marks", "invites", inviteId), accessToken = accessToken, response = null)
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import chat.bipe.shared.BipeBadgeEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Bipe API calls of people and profiles (bipe-chat/bipe#568): handle lookup, account type, badges, public profiles,
// who can message me, contact discovery. Contract: bipe-shared/api/openapi.yaml. Same calls as BipeKit
// BipeAPIClient+People.swift on iOS.

/** `GET /v1/handles/{handle}`. Exact lookup (ADR 0013). */
suspend fun BipeApiClient.handle(handle: String, accessToken: String): BipeHandleRecord =
    send("GET", listOf("v1", "handles", handle), accessToken = accessToken, response = BipeHandleRecord.serializer())

/** `POST /v1/handles/resolve`. Matrix ID to handle; IDs without a handle are absent. */
suspend fun BipeApiClient.resolveHandles(userIds: List<String>, accessToken: String): Map<String, String> = send(
    "POST",
    listOf("v1", "handles", "resolve"),
    body = encode(ResolveRequest.serializer(), ResolveRequest(userIds)),
    accessToken = accessToken,
    response = ResolveResponse.serializer(),
).handles

/** `GET /v1/accounts/{userId}/type`. No auth. Fail closed: any failure is a personal account (ADR 0132). */
suspend fun BipeApiClient.accountType(userId: String): BipeAccountKind = runCatching {
    send("GET", listOf("v1", "accounts", userId, "type"), response = AccountTypeResponse.serializer()).accountType
}.getOrNull()?.let(BipeAccountKind::fromId) ?: BipeAccountKind.PERSONAL

/** `GET /v1/badges/{userId}`. No auth. 200 is an envelope to verify on the device, 410 a revoked claim, 404 no badge. */
suspend fun BipeApiClient.badge(userId: String): BipeBadgeLookup {
    val (status, text) = exchange("GET", listOf("v1", "badges", userId))
    return when (status) {
        200 -> BipeBadgeLookup.Envelope(decode(BipeBadgeEnvelope.serializer(), text, status))
        404 -> BipeBadgeLookup.NoBadge
        410 -> BipeBadgeLookup.Gone(
            runCatching { BipeApiClient.json.decodeFromString(BadgeGoneBody.serializer(), text).reason }.getOrNull()
                ?.let(BipeBadgeGoneReason::fromId) ?: BipeBadgeGoneReason.REVOKED,
        )
        else -> throw this.error(status, text)
    }
}

/** `GET /v1/profiles/{handle}`. No auth. `null` on 404: no such handle, or the profile is off. */
suspend fun BipeApiClient.publicProfile(handle: String): BipePublicProfile? = try {
    send("GET", listOf("v1", "profiles", handle), response = BipePublicProfile.serializer())
} catch (error: BipeApiException.Server) {
    if (error.statusCode == 404) null else throw error
} catch (error: BipeApiException.UnexpectedResponse) {
    if (error.statusCode == 404) null else throw error
}

/** `GET /v1/me/profile`. A user who never saved one gets `enabled` false and an empty name. */
suspend fun BipeApiClient.myProfile(accessToken: String): BipeMyProfile =
    send("GET", listOf("v1", "me", "profile"), accessToken = accessToken, response = BipeMyProfile.serializer())

/** `PUT /v1/me/profile`. The server answers `profile_invalid` with a profile problem id. */
suspend fun BipeApiClient.saveMyProfile(enabled: Boolean, name: String, bio: String, accessToken: String): BipeMyProfile = send(
    "PUT",
    listOf("v1", "me", "profile"),
    body = encode(ProfileUpdate.serializer(), ProfileUpdate(enabled, name, bio)),
    accessToken = accessToken,
    response = BipeMyProfile.serializer(),
)

/** `PUT /v1/me/profile/photo` with a JPEG from `BipeProfilePhoto.jpeg`. */
suspend fun BipeApiClient.setMyProfilePhoto(jpeg: ByteArray, accessToken: String): BipeMyProfile =
    sendRaw("PUT", listOf("v1", "me", "profile", "photo"), jpeg, "image/jpeg", accessToken, BipeMyProfile.serializer())

/** `DELETE /v1/me/profile/photo`. */
suspend fun BipeApiClient.removeMyProfilePhoto(accessToken: String): BipeMyProfile =
    send("DELETE", listOf("v1", "me", "profile", "photo"), accessToken = accessToken, response = BipeMyProfile.serializer())

/** `GET /v1/me/messaging` (ADR 0038). */
suspend fun BipeApiClient.messagingSetting(accessToken: String): BipeWhoCanMessage =
    BipeWhoCanMessage.fromId(
        send("GET", listOf("v1", "me", "messaging"), accessToken = accessToken, response = MessagingSetting.serializer()).whoCanMessage,
    )

/** `PUT /v1/me/messaging`. Returns the saved value. */
suspend fun BipeApiClient.setMessagingSetting(value: BipeWhoCanMessage, accessToken: String): BipeWhoCanMessage =
    BipeWhoCanMessage.fromId(
        send(
            "PUT",
            listOf("v1", "me", "messaging"),
            body = encode(MessagingSetting.serializer(), MessagingSetting(value.id)),
            accessToken = accessToken,
            response = MessagingSetting.serializer(),
        ).whoCanMessage,
    )

/** Most numbers per `POST /v1/contacts/lookup`. */
const val CONTACT_LOOKUP_CHUNK = 500

/**
 * `POST /v1/contacts/lookup` with at most [CONTACT_LOOKUP_CHUNK] E.164 numbers. Returns E.164 to Matrix ID.
 * The server keeps none of the numbers; the app keeps no copy of what it sent (ADR 0015).
 */
suspend fun BipeApiClient.lookupContacts(phones: List<String>, accessToken: String): Map<String, String> = send(
    "POST",
    listOf("v1", "contacts", "lookup"),
    body = encode(ContactLookupRequest.serializer(), ContactLookupRequest(phones)),
    accessToken = accessToken,
    response = ContactLookupResponse.serializer(),
).matches.associate { it.phone to it.userId }

/** `GET /v1/me/invite-connections`: the people the user invited who joined, and whoever invited the user (ADR 0034). */
suspend fun BipeApiClient.inviteConnections(accessToken: String): Set<String> =
    send("GET", listOf("v1", "me", "invite-connections"), accessToken = accessToken, response = InviteConnections.serializer())
        .userIds.toSet()

/** `account_type` of `GET /v1/accounts/{userId}/type`. A company's avatar is a rounded square (ADR 0132). */
enum class BipeAccountKind(val id: String) {
    PERSONAL("personal"),
    COMPANY("company"),
    ;

    companion object {
        fun fromId(id: String): BipeAccountKind? = entries.firstOrNull { it.id == id }
    }
}

/** Contract `MessagingSetting.who_can_message`. */
enum class BipeWhoCanMessage(val id: String) {
    EVERYONE("everyone"),
    VERIFIED("verified"),
    CONTACTS("contacts"),
    ;

    companion object {
        fun fromId(id: String): BipeWhoCanMessage = entries.firstOrNull { it.id == id } ?: EVERYONE
    }
}

/** What `GET /v1/badges/{userId}` answered. */
sealed interface BipeBadgeLookup {
    data class Envelope(val envelope: BipeBadgeEnvelope) : BipeBadgeLookup
    data class Gone(val reason: BipeBadgeGoneReason) : BipeBadgeLookup
    data object NoBadge : BipeBadgeLookup
}

/** Contract `BadgeGone.reason`. */
enum class BipeBadgeGoneReason(val id: String) {
    REVOKED("revoked"),
    NOT_ACTIVE("not_active"),
    NOT_FOUND("not_found"),
    STAFF("staff"),
    ;

    companion object {
        fun fromId(id: String): BipeBadgeGoneReason? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class BipeHandleRecord(
    @SerialName("handle") val handle: String,
    @SerialName("user_id") val userId: String,
)

/** Contract `MyProfile`. */
@Serializable
data class BipeMyProfile(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String,
    @SerialName("has_photo") val hasPhoto: Boolean,
    @SerialName("handle") val handle: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
)

/** Contract `PublicProfile`. Never carries the Matrix ID. */
@Serializable
data class BipePublicProfile(
    @SerialName("handle") val handle: String,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("badge") val badge: BipeBadgeEnvelope? = null,
)

@Serializable
private data class ResolveRequest(@SerialName("user_ids") val userIds: List<String>)

@Serializable
private data class ResolveResponse(@SerialName("handles") val handles: Map<String, String>)

@Serializable
private data class AccountTypeResponse(@SerialName("account_type") val accountType: String)

@Serializable
private data class BadgeGoneBody(@SerialName("reason") val reason: String)

@Serializable
private data class ProfileUpdate(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String,
)

@Serializable
private data class MessagingSetting(@SerialName("who_can_message") val whoCanMessage: String)

@Serializable
private data class ContactLookupRequest(@SerialName("phones") val phones: List<String>)

@Serializable
private data class ContactLookupResponse(@SerialName("matches") val matches: List<Match>) {
    @Serializable
    data class Match(@SerialName("phone") val phone: String, @SerialName("user_id") val userId: String)
}

@Serializable
private data class InviteConnections(@SerialName("user_ids") val userIds: List<String>)

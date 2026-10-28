// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// The Bipe API calls of people and profiles (bipe-chat/bipe#568): handle lookup, account type, badges, public profiles,
// contact discovery. Contract: bipe-shared/api/openapi.yaml. Same calls as BipeKit BipeAPIClient on iOS.
// Who can message me is in settings/BipeSettingsSupport.kt.

/** `GET /v1/handles/{handle}`. Exact lookup (ADR 0013). */
suspend fun BipeApiClient.handle(handle: String, openId: BipeOpenIdToken): BipeHandleRecord = send(
    "GET",
    listOf("v1", "handles", handle),
    accessToken = openId.accessToken,
    serverName = openId.matrixServerName,
    response = BipeHandleRecord.serializer(),
)

/** `POST /v1/handles/resolve`. Matrix ID to handle; IDs without a handle are absent. */
suspend fun BipeApiClient.resolveHandles(userIds: List<String>, openId: BipeOpenIdToken): Map<String, String> = send(
    "POST",
    listOf("v1", "handles", "resolve"),
    body = encode(ResolveRequest.serializer(), ResolveRequest(userIds)),
    accessToken = openId.accessToken,
    serverName = openId.matrixServerName,
    response = ResolveResponse.serializer(),
).handles

/** `GET /v1/accounts/{userId}/type`. No auth. `true` for a company (ADR 0132). Fail closed: any failure is a person. */
suspend fun BipeApiClient.isCompany(userId: String): Boolean = try {
    send("GET", listOf("v1", "accounts", userId, "type"), response = AccountTypeResponse.serializer()).accountType == "company"
} catch (error: BipeApiException) {
    false
}

/** `GET /v1/badges/{userId}`. No auth. 200 is envelope JSON to verify on the device, 410 a revoked claim, 404 no badge. */
suspend fun BipeApiClient.badge(userId: String): BipeBadgeLookup {
    val (status, text) = exchange("GET", listOf("v1", "badges", userId))
    return when (status) {
        200 -> BipeBadgeLookup.Envelope(text)
        404 -> BipeBadgeLookup.NoBadge
        410 -> BipeBadgeLookup.Gone(
            runCatching { peopleJson.decodeFromString(BadgeGoneBody.serializer(), text).reason }.getOrNull()
                ?.let(BipeBadgeGoneReason::fromId) ?: BipeBadgeGoneReason.REVOKED,
        )
        else -> throw BipeApiException.UnexpectedResponse(status)
    }
}

/**
 * `GET /v1/brand-marks/{userId}` (ADR 0162). No auth. Each envelope's JSON, not yet verified, in the server's order.
 */
suspend fun BipeApiClient.brandMarks(userId: String): List<String> {
    val (status, text) = exchange("GET", listOf("v1", "brand-marks", userId))
    if (status != 200) throw BipeApiException.UnexpectedResponse(status)
    val marks = runCatching { peopleJson.parseToJsonElement(text).jsonObject["marks"]?.jsonArray }.getOrNull()
        ?: throw BipeApiException.UnexpectedResponse(status)
    return marks.map { it.toString() }
}

/** `GET /v1/profiles/{handle}`. No auth. `null` on 404: no such handle, or the profile is off. */
suspend fun BipeApiClient.publicProfile(handle: String): BipePublicProfile? {
    val (status, text) = exchange("GET", listOf("v1", "profiles", handle))
    return when (status) {
        in 200..299 -> decodePeople(BipePublicProfile.serializer(), text, status)
        404 -> null
        else -> throw serverError(status, text)
    }
}

/** `GET /v1/me/profile`. A user who never saved one gets `enabled` false and an empty name. */
suspend fun BipeApiClient.myProfile(accessToken: String): BipeMyProfile =
    send("GET", listOf("v1", "me", "profile"), accessToken = accessToken, response = BipeMyProfile.serializer())

/** `PUT /v1/me/profile`. The server answers `profile_invalid` with a profile problem id. */
suspend fun BipeApiClient.setMyProfile(update: BipeProfileUpdate, accessToken: String): BipeMyProfile = send(
    "PUT",
    listOf("v1", "me", "profile"),
    body = encode(BipeProfileUpdate.serializer(), update),
    accessToken = accessToken,
    response = BipeMyProfile.serializer(),
)

/** `PUT /v1/me/profile/photo` with a JPEG that fits registries/profiles.json. */
suspend fun BipeApiClient.setMyProfilePhoto(jpeg: ByteArray, accessToken: String): BipeMyProfile {
    val (status, text) = exchange("PUT", listOf("v1", "me", "profile", "photo"), jpeg, "image/jpeg", accessToken)
    if (status !in 200..299) throw serverError(status, text)
    return decodePeople(BipeMyProfile.serializer(), text, status)
}

/** `DELETE /v1/me/profile/photo`. */
suspend fun BipeApiClient.deleteMyProfilePhoto(accessToken: String): BipeMyProfile =
    send("DELETE", listOf("v1", "me", "profile", "photo"), accessToken = accessToken, response = BipeMyProfile.serializer())

/**
 * `POST /v1/contacts/lookup` with at most 500 E.164 numbers. The server keeps none of the numbers; the app keeps no
 * copy of what it sent (ADR 0015).
 */
suspend fun BipeApiClient.lookupContacts(phones: List<String>, openId: BipeOpenIdToken): List<BipeContactMatch> = send(
    "POST",
    listOf("v1", "contacts", "lookup"),
    body = encode(ContactLookupRequest.serializer(), ContactLookupRequest(phones)),
    accessToken = openId.accessToken,
    serverName = openId.matrixServerName,
    response = ContactLookupResponse.serializer(),
).matches

/** `GET /v1/me/invite-connections`: the people the user invited who joined, and whoever invited the user (ADR 0034). */
suspend fun BipeApiClient.inviteConnections(accessToken: String): Set<String> =
    send("GET", listOf("v1", "me", "invite-connections"), accessToken = accessToken, response = InviteConnections.serializer())
        .userIds.toSet()

/** Sends a request and returns the status and the body text of any answer. The body may be any content type. */
internal suspend fun BipeApiClient.exchange(
    method: String,
    path: List<String>,
    body: ByteArray? = null,
    contentType: String = "application/json",
    accessToken: String? = null,
): Pair<Int, String> = withContext(Dispatchers.IO) {
    // Each segment is percent-encoded, so a handle cannot add path segments.
    val url = URL(root + path.joinToString(separator = "/", prefix = "/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
    val connection = try {
        url.openConnection() as HttpURLConnection
    } catch (error: IOException) {
        throw BipeApiException.Network(error)
    }
    try {
        connection.requestMethod = method
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            .orEmpty()
        status to text
    } catch (error: IOException) {
        throw BipeApiException.Network(error)
    } finally {
        connection.disconnect()
    }
}

private val peopleJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun <T> decodePeople(serializer: KSerializer<T>, text: String, status: Int): T = try {
    peopleJson.decodeFromString(serializer, text)
} catch (error: SerializationException) {
    throw BipeApiException.UnexpectedResponse(status, error)
} catch (error: IllegalArgumentException) {
    throw BipeApiException.UnexpectedResponse(status, error)
}

internal fun serverError(status: Int, text: String): BipeApiException {
    val body = runCatching { peopleJson.decodeFromString(PeopleErrorBody.serializer(), text) }.getOrNull()
        ?: return BipeApiException.UnexpectedResponse(status)
    return BipeApiException.Server(
        code = BipeApiErrorCode.fromId(body.error) ?: BipeApiErrorCode.INTERNAL,
        reason = body.reason,
        statusCode = status,
    )
}

/** What `GET /v1/badges/{userId}` answered. */
sealed interface BipeBadgeLookup {
    /** HTTP 200: the envelope JSON as the server sent it. Verify these exact bytes. */
    data class Envelope(val json: String) : BipeBadgeLookup

    /** HTTP 410: the account had a badge and its claim ended. */
    data class Gone(val reason: BipeBadgeGoneReason) : BipeBadgeLookup

    /** HTTP 404: no badge. */
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

/** Contract `ProfileUpdate`. */
@Serializable
data class BipeProfileUpdate(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String,
)

/** Contract `PublicProfile`. Never carries the Matrix ID. */
@Serializable
data class BipePublicProfile(
    @SerialName("handle") val handle: String,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    /** The same envelope as `GET /v1/badges/{userId}`. Absent when that call would not answer 200. */
    @SerialName("badge") val badge: chat.bipe.shared.BadgeEnvelope? = null,
)

/** One match of `POST /v1/contacts/lookup`. */
@Serializable
data class BipeContactMatch(
    /** E.164, as normalised by the server. */
    @SerialName("phone") val phone: String,
    @SerialName("user_id") val userId: String,
) {
    /** Never prints the number. */
    override fun toString(): String = "BipeContactMatch($userId)"
}

@Serializable
private data class ResolveRequest(@SerialName("user_ids") val userIds: List<String>)

@Serializable
private data class ResolveResponse(@SerialName("handles") val handles: Map<String, String>)

@Serializable
private data class AccountTypeResponse(@SerialName("account_type") val accountType: String)

@Serializable
private data class BadgeGoneBody(@SerialName("reason") val reason: String)

@Serializable
private data class ContactLookupRequest(@SerialName("phones") val phones: List<String>)

@Serializable
private data class ContactLookupResponse(@SerialName("matches") val matches: List<BipeContactMatch>)

@Serializable
private data class InviteConnections(@SerialName("user_ids") val userIds: List<String>)

@Serializable
private data class PeopleErrorBody(
    @SerialName("error") val error: String,
    @SerialName("reason") val reason: String? = null,
)

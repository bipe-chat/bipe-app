// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import android.content.Context
import chat.bipe.shared.BipeHandleProblem
import chat.bipe.shared.BipeProfileProblem
import chat.bipe.shared.R
import chat.bipe.shared.account.BipePinProblem
import chat.bipe.shared.generated.BipeRegistries
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Client for the Bipe API (bipe-api). Contract: bipe-shared/api/openapi.yaml. Same calls as BipeKit `BipeAPIClient` on iOS.
 * Requests carry PINs and access tokens: nothing here logs a body or a header.
 */
class BipeApiClient(baseUrl: String) {
    internal val root = baseUrl.trimEnd('/')

    // Sign-up

    /** `GET /v1/signup/config`. No auth. The sign-up mode (ADR 0072). */
    suspend fun signupConfig(): BipeSignupConfig = send("GET", listOf("v1", "signup", "config"), response = BipeSignupConfig.serializer())

    /** `POST /v1/signup`. No auth. */
    suspend fun signup(request: BipeSignupRequest): BipeSignupResponse =
        send("POST", listOf("v1", "signup"), body = encode(BipeSignupRequest.serializer(), request), response = BipeSignupResponse.serializer())

    /** `POST /v1/attest/challenge`. No auth. A one-time challenge for the sign-up attestation (5 minutes). */
    suspend fun attestChallenge(): String =
        send("POST", listOf("v1", "attest", "challenge"), response = BipeAttestChallengeResponse.serializer()).challenge

    /** `POST /v1/signup/resume`. No auth. The answer of an earlier sign-up with this key (ADR 0094); `not_found` when none. */
    suspend fun resumeSignup(signupKey: String): BipeSignupResponse = send(
        "POST",
        listOf("v1", "signup", "resume"),
        body = encode(BipeSignupResumeRequest.serializer(), BipeSignupResumeRequest(signupKey)),
        response = BipeSignupResponse.serializer(),
    )

    /** `POST /v1/waitlist`. No auth. 202 for every well-formed address, so it never tells whether the address is known. */
    suspend fun joinWaitlist(email: String, lang: String) {
        send<Unit>(
            "POST",
            listOf("v1", "waitlist"),
            body = encode(BipeWaitlistRequest.serializer(), BipeWaitlistRequest(email, platform = "android", lang = lang, source = "app")),
            response = null,
        )
    }

    // Invites

    /** `GET /v1/invites/{code}`. No auth. Validity and the inviter's handle. */
    suspend fun invitePreview(code: String): BipeInvitePreview =
        send("GET", listOf("v1", "invites", code), response = BipeInvitePreview.serializer())

    // Handles

    /** `GET /v1/handles/{handle}/availability`. No auth. */
    suspend fun handleAvailability(handle: String): BipeHandleAvailability =
        send("GET", listOf("v1", "handles", handle, "availability"), response = BipeHandleAvailability.serializer())

    /** `GET /v1/me/handle`. */
    suspend fun myHandle(accessToken: String): BipeMyHandle =
        send("GET", listOf("v1", "me", "handle"), accessToken = accessToken, response = BipeMyHandle.serializer())

    /** `PUT /v1/me/handle`. */
    suspend fun setMyHandle(handle: String, accessToken: String): BipeMyHandle = send(
        "PUT",
        listOf("v1", "me", "handle"),
        body = encode(BipeSetHandleRequest.serializer(), BipeSetHandleRequest(handle)),
        accessToken = accessToken,
        response = BipeMyHandle.serializer(),
    )

    /** `POST /v1/handle-changes` (ADR 0066). At most [BipeHandleChange.BATCH_SIZE] IDs. Oldest first. */
    suspend fun handleChanges(userIds: List<String>, since: java.time.Instant?, accessToken: String): List<BipeHandleChange> = send(
        "POST",
        listOf("v1", "handle-changes"),
        body = encode(BipeHandleChangesRequest.serializer(), BipeHandleChangesRequest(userIds, since?.toString())),
        accessToken = accessToken,
        response = BipeHandleChangesResponse.serializer(),
    ).changes

    /** `POST /v1/profile-facts` (ADR 0049). At most [BipeProfileFacts.BATCH_SIZE] IDs. IDs the API does not know are absent. */
    suspend fun profileFacts(userIds: List<String>, accessToken: String): List<BipeProfileFacts> = send(
        "POST",
        listOf("v1", "profile-facts"),
        body = encode(BipeProfileFactsRequest.serializer(), BipeProfileFactsRequest(userIds)),
        accessToken = accessToken,
        response = BipeProfileFactsResponse.serializer(),
    ).facts

    /** `DELETE /v1/me/handle` (ADR 0066). The cooldown stays. */
    suspend fun removeMyHandle(accessToken: String): BipeMyHandle =
        send("DELETE", listOf("v1", "me", "handle"), accessToken = accessToken, response = BipeMyHandle.serializer())

    // Account and terms

    /** `GET /v1/me/account`. */
    suspend fun account(accessToken: String): BipeAccountStatus =
        send("GET", listOf("v1", "me", "account"), accessToken = accessToken, response = BipeAccountStatus.serializer())

    /** `GET /v1/me/business` (ADR 0042). `null` when the account has no CNPJ claim (404). */
    suspend fun myBusiness(accessToken: String): BipeBusinessClaim? = try {
        send("GET", listOf("v1", "me", "business"), accessToken = accessToken, response = BipeBusinessClaim.serializer())
    } catch (error: BipeApiException) {
        val status = when (error) {
            is BipeApiException.Server -> error.statusCode
            is BipeApiException.UnexpectedResponse -> error.statusCode
            is BipeApiException.Network -> null
        }
        if (status == 404) null else throw error
    }

    /** `POST /v1/me/terms`. Records that the person ticked the box for [termsVersion] (ADR 0113). */
    suspend fun acceptTerms(termsVersion: String, accessToken: String) {
        send<Unit>(
            "POST",
            listOf("v1", "me", "terms"),
            body = encode(BipeTermsRequest.serializer(), BipeTermsRequest(termsVersion)),
            accessToken = accessToken,
            response = null,
        )
    }

    // Recovery (ADR 0057, ADR 0059)

    /** `POST /v1/me/device-first`. A beta account gets a hidden password and a recovery token, once. */
    suspend fun moveToDeviceFirst(accessToken: String): BipeCredentials =
        send("POST", listOf("v1", "me", "device-first"), accessToken = accessToken, response = BipeCredentials.serializer())

    /**
     * `PUT /v1/me/voice-media/{server}/{mediaId}` (ADR 0164, bipe-chat/bipe#622): the caller's own voice upload, so the
     * server can expire its audio. 404 when the media is not the caller's upload on this server.
     */
    suspend fun registerVoiceMedia(server: String, mediaId: String, transcript: Boolean, accessToken: String) {
        send<Unit>(
            "PUT",
            listOf("v1", "me", "voice-media", server, mediaId),
            body = encode(BipeVoiceMediaRequest.serializer(), BipeVoiceMediaRequest(transcript)),
            accessToken = accessToken,
            response = null,
        )
    }

    /** `GET /v1/me/kept-media` (ADR 0164): the voice message audio the caller keeps. */
    suspend fun keptMedia(accessToken: String): List<BipeKeptMedia> =
        send("GET", listOf("v1", "me", "kept-media"), accessToken = accessToken, response = BipeKeptMediaList.serializer()).media

    /** `PUT /v1/me/kept-media/{server}/{mediaId}` (ADR 0164). 409 keep_limit at maxKeptPerAccount. */
    suspend fun keepMedia(server: String, mediaId: String, reason: BipeKeptMedia.Reason, accessToken: String) {
        send<Unit>(
            "PUT",
            listOf("v1", "me", "kept-media", server, mediaId),
            body = encode(BipeKeepMediaRequest.serializer(), BipeKeepMediaRequest(reason)),
            accessToken = accessToken,
            response = null,
        )
    }

    /** `DELETE /v1/me/kept-media/{server}/{mediaId}` (ADR 0164). */
    suspend fun unkeepMedia(server: String, mediaId: String, accessToken: String) {
        send<Unit>("DELETE", listOf("v1", "me", "kept-media", server, mediaId), accessToken = accessToken, response = null)
    }

    /** `POST /v1/me/recoveries/{id}/cancel`. An old device cancels a waiting recovery. */
    suspend fun cancelRecovery(id: String, accessToken: String) {
        send<Unit>("POST", listOf("v1", "me", "recoveries", id, "cancel"), accessToken = accessToken, response = null)
    }

    /**
     * `POST /v1/me/deletion` (ADR 0140). The app checks the device owner first; the server checks the senha and deletes
     * the account after [BipeRegistries.DELETION_COOLDOWN_HOURS]. `deletion_pending` when a request already waits.
     */
    suspend fun requestDeletion(pin: String, redactMessages: Boolean, accessToken: String): BipeAccountDeletion = send(
        "POST",
        listOf("v1", "me", "deletion"),
        body = encode(BipeDeletionRequest.serializer(), BipeDeletionRequest(pin, redactMessages)),
        accessToken = accessToken,
        response = BipeAccountDeletion.serializer(),
    )

    /** `POST /v1/me/deletions/{id}/cancel` (ADR 0140). Any signed-in device, until `due_at`; `not_found` when it no longer waits. */
    suspend fun cancelDeletion(id: String, accessToken: String) {
        send<Unit>("POST", listOf("v1", "me", "deletions", id, "cancel"), accessToken = accessToken, response = null)
    }

    /** `POST /v1/recovery/token`. No auth. The synced token, then the PIN. New credentials, once. */
    suspend fun recoverWithToken(token: String, pin: String): BipeCredentials = send(
        "POST",
        listOf("v1", "recovery", "token"),
        body = encode(BipeTokenRecoveryRequest.serializer(), BipeTokenRecoveryRequest(token, pin)),
        response = BipeCredentials.serializer(),
    )

    /** `POST /v1/recovery/email`. No auth. 202 for every well-formed address. */
    suspend fun requestRecoveryEmail(email: String) {
        send<Unit>(
            "POST",
            listOf("v1", "recovery", "email"),
            body = encode(BipeRecoveryEmailRequest.serializer(), BipeRecoveryEmailRequest(email)),
            response = null,
        )
    }

    /** `POST /v1/recovery/email/confirm`. No auth. The link token and exactly one of [pin] (access) or [newPin] (PIN reset). */
    suspend fun confirmRecoveryEmail(linkToken: String, pin: String?, newPin: String?): BipeRecoveryStarted = send(
        "POST",
        listOf("v1", "recovery", "email", "confirm"),
        body = encode(BipeEmailConfirmRequest.serializer(), BipeEmailConfirmRequest(linkToken, pin, newPin)),
        response = BipeRecoveryStarted.serializer(),
    )

    /** `POST /v1/recovery/{id}/claim`. No auth. Polls a recovery; the credentials come once when the delay has ended. */
    suspend fun claimRecovery(id: String, claimSecret: String): BipeRecoveryClaim = send(
        "POST",
        listOf("v1", "recovery", id, "claim"),
        body = encode(BipeClaimRequest.serializer(), BipeClaimRequest(claimSecret)),
        response = BipeRecoveryClaim.serializer(),
    )

    internal fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    /** Sends the request and decodes a 2xx answer with [response] (`null`: the body is not read). */
    internal suspend fun <T> send(
        method: String,
        path: List<String>,
        body: String? = null,
        accessToken: String? = null,
        response: KSerializer<T>?,
        query: List<Pair<String, String>> = emptyList(),
    ): T = withContext(Dispatchers.IO) {
        // Each segment is percent-encoded, so a handle or a code cannot add path segments.
        val encodedQuery = query.joinToString(separator = "&", prefix = if (query.isEmpty()) "" else "?") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val url = URL(root + path.joinToString(separator = "/", prefix = "/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + encodedQuery)
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw BipeApiException.Network(error)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            if (status !in 200..299) throw error(status, text)
            @Suppress("UNCHECKED_CAST")
            if (response == null) return@withContext Unit as T
            try {
                json.decodeFromString(response, text)
            } catch (error: SerializationException) {
                throw BipeApiException.UnexpectedResponse(status, error)
            } catch (error: IllegalArgumentException) {
                throw BipeApiException.UnexpectedResponse(status, error)
            }
        } catch (error: IOException) {
            throw BipeApiException.Network(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun error(status: Int, text: String): BipeApiException {
        val body = runCatching { json.decodeFromString(BipeErrorBody.serializer(), text) }.getOrNull()
            ?: return BipeApiException.UnexpectedResponse(status)
        return BipeApiException.Server(
            code = BipeApiErrorCode.fromId(body.error) ?: BipeApiErrorCode.INTERNAL,
            reason = body.reason,
            statusCode = status,
            triesLeft = body.triesLeft,
        )
    }

    companion object {
        private const val TIMEOUT_MS = 30_000
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** The `lang` for e-mails Bipe sends: English for an English device, else Portuguese. Same rule as iOS. */
        fun emailLanguage(): String = if (Locale.getDefault().language == "en") "en" else "pt"

        /**
         * Runs [request] with the token from [accessToken]. On HTTP 401 it reads the token again and retries once:
         * the SDK may have refreshed it meanwhile.
         */
        suspend fun <T> withTokenRetry(accessToken: suspend () -> String?, request: suspend (String) -> T): T {
            val token = accessToken() ?: throw BipeApiException.Server(BipeApiErrorCode.UNAUTHORIZED, reason = null, statusCode = 401)
            return try {
                request(token)
            } catch (error: BipeApiException) {
                val fresh = if (error.isUnauthorized) accessToken() else null
                request(fresh ?: throw error)
            }
        }
    }
}

/** The contract's `Error.error` values. */
enum class BipeApiErrorCode(val id: String) {
    BAD_REQUEST("bad_request"),
    INVITE_INVALID("invite_invalid"),
    INVITE_QUOTA_EXHAUSTED("invite_quota_exhausted"),
    EMAIL_TAKEN("email_taken"),
    HANDLE_INVALID("handle_invalid"),
    HANDLE_TAKEN("handle_taken"),
    HANDLE_COOLDOWN("handle_cooldown"),
    ATTESTATION_INVALID("attestation_invalid"),
    UNAUTHORIZED("unauthorized"),
    NOT_FOUND("not_found"),
    RATE_LIMITED("rate_limited"),
    INTERNAL("internal"),
    PIN_WEAK("pin_weak"),
    PIN_WRONG("pin_wrong"),
    PIN_BLOCKED("pin_blocked"),
    PIN_NOT_SET("pin_not_set"),
    RECOVERY_INVALID("recovery_invalid"),
    EMAIL_UNAVAILABLE("email_unavailable"),

    /** `PUT /v1/me/profile`: `reason` is a profile problem id (ADR 0041). */
    PROFILE_INVALID("profile_invalid"),

    /** HTTP 403: the sign-up or the account lacks the current legal version (ADR 0113). */
    TERMS_REQUIRED("terms_required"),

    /** HTTP 409: that Apple or Google account is linked to another Bipe account (ADR 0096). */
    LINK_TAKEN("link_taken"),

    /** HTTP 503: the server has no configuration for that provider (ADR 0096). */
    LINK_UNAVAILABLE("link_unavailable"),
    /** HTTP 409: an account deletion already waits (ADR 0140). */
    DELETION_PENDING("deletion_pending"),

    /** A JPEG the server cannot take (profile or catalogue photo). */
    PHOTO_INVALID("photo_invalid"),

    // Agenda (contract AgendaError, bipe-shared api/business-schemas.json).
    SLOT_TAKEN("slot_taken"),
    HOLD_EXPIRED("hold_expired"),
    TOO_MANY_OPEN_BOOKINGS("too_many_open_bookings"),
    OUTSIDE_NOTICE("outside_notice"),
    OUTSIDE_HORIZON("outside_horizon"),
    AGENDA_NOT_PUBLISHED("agenda_not_published"),
    CANCELLATION_WINDOW_CLOSED("cancellation_window_closed"),

    // Fila virtual, Orçamento and Pedidos (contract business errors, bipe-shared api/business-schemas.json).
    QUEUE_CLOSED("queue_closed"),
    ALREADY_IN_QUEUE("already_in_queue"),
    PLACE_NOT_ACTIVE("place_not_active"),
    QUOTE_EXPIRED("quote_expired"),
    QUOTE_VERSION_CHANGED("quote_version_changed"),
    QUOTE_NOT_OPEN("quote_not_open"),
    ORDER_ALREADY_OPEN("order_already_open"),
    ORDER_NOT_OPEN("order_not_open"),
    ITEM_UNAVAILABLE("item_unavailable"),
    PRICE_NOT_FIXED("price_not_fixed"),
    ORDERS_CLOSED("orders_closed"),

    /** Pix needs a verified CNPJ (ADR 0080). The app never offers Pix; the text says to pay on site. */
    PAYMENT_REQUIRES_VERIFIED_BUSINESS("payment_requires_verified_business"),
    PIX_CHARGE_UNAVAILABLE("pix_charge_unavailable"),
    /** Business claims (ADR 0042, business-claim). */
    CNPJ_INVALID("cnpj_invalid"),
    CNPJ_NOT_ACTIVE("cnpj_not_active"),
    CNPJ_TAKEN("cnpj_taken"),
    SIGNATURE_INVALID("signature_invalid"),

    /** 403. Only a company with a verified claim grants brand marks (ADR 0162). */
    BRAND_NOT_VERIFIED("brand_not_verified"),

    /** 409. The account already holds the most brand marks (ADR 0162); the invite stays open. */
    BRAND_MARK_LIMIT("brand_mark_limit"),

    /** 409. The account already keeps maxKeptPerAccount voice audios (ADR 0164). */
    KEEP_LIMIT("keep_limit"),
    ;

    companion object {
        fun fromId(id: String): BipeApiErrorCode? = entries.firstOrNull { it.id == id }
    }
}

sealed class BipeApiException(cause: Throwable? = null) : Exception(cause) {
    /** The server answered with the contract's error body. */
    data class Server(
        val code: BipeApiErrorCode,
        val reason: String?,
        val statusCode: Int,
        /** With [BipeApiErrorCode.PIN_WRONG]: wrong PINs left. */
        val triesLeft: Int? = null,
    ) : BipeApiException()

    /** The request did not reach the server or got no answer. */
    class Network(cause: Throwable? = null) : BipeApiException(cause)

    /** The server answered with something outside the contract. */
    class UnexpectedResponse(val statusCode: Int?, cause: Throwable? = null) : BipeApiException(cause)

    val isUnauthorized: Boolean
        get() = when (this) {
            is Server -> statusCode == 401
            is UnexpectedResponse -> statusCode == 401
            is Network -> false
        }

    /** The text a person reads. */
    fun localizedMessage(context: Context): String = when (this) {
        is Server -> when (code) {
            BipeApiErrorCode.INVITE_INVALID -> context.getString(R.string.bipe_error_invite_invalid)
            BipeApiErrorCode.INVITE_QUOTA_EXHAUSTED -> context.getString(R.string.bipe_error_invite_quota)
            BipeApiErrorCode.EMAIL_TAKEN -> context.getString(R.string.bipe_error_email_taken)
            BipeApiErrorCode.ATTESTATION_INVALID -> context.getString(R.string.bipe_error_attestation_invalid)
            BipeApiErrorCode.HANDLE_TAKEN -> BipeHandleProblem.TAKEN.localizedMessage(context)
            BipeApiErrorCode.HANDLE_INVALID ->
                (reason?.let(BipeHandleProblem::fromId) ?: BipeHandleProblem.INVALID_CHARACTERS).localizedMessage(context)
            BipeApiErrorCode.HANDLE_COOLDOWN -> context.getString(R.string.bipe_error_handle_cooldown)
            BipeApiErrorCode.RATE_LIMITED -> context.getString(R.string.bipe_error_rate_limited)
            BipeApiErrorCode.UNAUTHORIZED -> context.getString(R.string.bipe_error_unauthorized)
            BipeApiErrorCode.PIN_WEAK -> (reason?.let(BipePinProblem::fromId) ?: BipePinProblem.SEQUENCE).localizedMessage(context)
            BipeApiErrorCode.PIN_WRONG -> context.getString(R.string.bipe_pin_wrong, (triesLeft ?: 0).toString())
            BipeApiErrorCode.PIN_BLOCKED -> context.getString(R.string.bipe_pin_blocked_body, BipeRegistries.PIN_MAX_WRONG_TRIES.toString())
            BipeApiErrorCode.PIN_NOT_SET -> context.getString(R.string.bipe_deletion_pin_not_set)
            BipeApiErrorCode.DELETION_PENDING -> context.getString(R.string.bipe_deletion_error)
            BipeApiErrorCode.RECOVERY_INVALID -> context.getString(R.string.bipe_error_recovery_invalid)
            BipeApiErrorCode.EMAIL_UNAVAILABLE -> context.getString(R.string.bipe_error_email_unavailable)
            BipeApiErrorCode.TERMS_REQUIRED -> context.getString(R.string.bipe_signup_terms_required)
            BipeApiErrorCode.PROFILE_INVALID ->
                reason?.let(BipeProfileProblem::fromId)?.localizedMessage(context) ?: context.getString(R.string.bipe_error_generic)
            BipeApiErrorCode.LINK_TAKEN -> context.getString(R.string.bipe_linked_account_taken)
            BipeApiErrorCode.LINK_UNAVAILABLE -> context.getString(R.string.bipe_linked_account_error)
            BipeApiErrorCode.SLOT_TAKEN -> context.getString(R.string.bipe_agenda_error_slot_taken)
            BipeApiErrorCode.HOLD_EXPIRED -> context.getString(R.string.bipe_agenda_error_hold_expired)
            BipeApiErrorCode.TOO_MANY_OPEN_BOOKINGS -> context.getString(R.string.bipe_agenda_error_too_many_open)
            BipeApiErrorCode.OUTSIDE_NOTICE -> context.getString(R.string.bipe_agenda_error_outside_notice)
            BipeApiErrorCode.OUTSIDE_HORIZON -> context.getString(R.string.bipe_agenda_error_outside_horizon)
            BipeApiErrorCode.AGENDA_NOT_PUBLISHED -> context.getString(R.string.bipe_agenda_error_not_published)
            BipeApiErrorCode.CANCELLATION_WINDOW_CLOSED -> context.getString(R.string.bipe_agenda_error_cancel_window)
            BipeApiErrorCode.QUEUE_CLOSED -> context.getString(R.string.bipe_queue_closed)
            BipeApiErrorCode.ALREADY_IN_QUEUE -> context.getString(R.string.bipe_queue_error_already_joined)
            BipeApiErrorCode.PLACE_NOT_ACTIVE -> context.getString(R.string.bipe_queue_error_inactive)
            BipeApiErrorCode.QUOTE_EXPIRED -> context.getString(R.string.bipe_quote_error_expired)
            BipeApiErrorCode.QUOTE_VERSION_CHANGED -> context.getString(R.string.bipe_quote_error_version)
            BipeApiErrorCode.QUOTE_NOT_OPEN -> context.getString(R.string.bipe_quote_error)
            BipeApiErrorCode.ORDER_ALREADY_OPEN -> context.getString(R.string.bipe_order_error_already_open)
            BipeApiErrorCode.ORDER_NOT_OPEN -> context.getString(R.string.bipe_order_error)
            BipeApiErrorCode.ITEM_UNAVAILABLE -> context.getString(R.string.bipe_order_error_item)
            BipeApiErrorCode.PRICE_NOT_FIXED -> context.getString(R.string.bipe_order_error_price)
            BipeApiErrorCode.ORDERS_CLOSED -> context.getString(R.string.bipe_order_error_closed)
            BipeApiErrorCode.PAYMENT_REQUIRES_VERIFIED_BUSINESS, BipeApiErrorCode.PIX_CHARGE_UNAVAILABLE ->
                context.getString(R.string.bipe_order_error_pix)
            BipeApiErrorCode.CNPJ_INVALID -> context.getString(R.string.bipe_claim_cnpj_invalid)
            BipeApiErrorCode.CNPJ_NOT_ACTIVE -> context.getString(R.string.bipe_claim_rejected)
            BipeApiErrorCode.CNPJ_TAKEN -> context.getString(R.string.bipe_claim_cnpj_taken)
            BipeApiErrorCode.SIGNATURE_INVALID -> context.getString(R.string.bipe_claim_signature_invalid)
            BipeApiErrorCode.BRAND_NOT_VERIFIED -> context.getString(R.string.bipe_brand_marks_not_verified)
            BipeApiErrorCode.BRAND_MARK_LIMIT -> context.getString(R.string.bipe_brand_marks_limit)
            BipeApiErrorCode.KEEP_LIMIT ->
                context.getString(R.string.bipe_voice_keep_limit, BipeRegistries.VOICE_MEDIA_MAX_KEPT_PER_ACCOUNT.toString())
            BipeApiErrorCode.BAD_REQUEST, BipeApiErrorCode.NOT_FOUND, BipeApiErrorCode.INTERNAL, BipeApiErrorCode.PHOTO_INVALID ->
                context.getString(R.string.bipe_error_generic)
        }
        is Network -> context.getString(R.string.bipe_error_network)
        is UnexpectedResponse -> context.getString(R.string.bipe_error_generic)
    }
}

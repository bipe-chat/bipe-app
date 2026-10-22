// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import android.content.Context
import chat.bipe.shared.BipeHandleProblem
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

    /** `DELETE /v1/me/handle` (ADR 0066). The cooldown stays. */
    suspend fun removeMyHandle(accessToken: String): BipeMyHandle =
        send("DELETE", listOf("v1", "me", "handle"), accessToken = accessToken, response = BipeMyHandle.serializer())

    // Account and terms

    /** `GET /v1/me/account`. */
    suspend fun account(accessToken: String): BipeAccountStatus =
        send("GET", listOf("v1", "me", "account"), accessToken = accessToken, response = BipeAccountStatus.serializer())

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

    internal fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    /** Sends the request and decodes a 2xx answer with [response] (`null`: the body is not read). */
    internal suspend fun <T> send(
        method: String,
        path: List<String>,
        body: String? = null,
        accessToken: String? = null,
        response: KSerializer<T>?,
    ): T = withContext(Dispatchers.IO) {
        // Each segment is percent-encoded, so a handle or a code cannot add path segments.
        val url = URL(root + path.joinToString(separator = "/", prefix = "/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
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
    RECOVERY_INVALID("recovery_invalid"),
    EMAIL_UNAVAILABLE("email_unavailable"),

    /** HTTP 403: the sign-up or the account lacks the current legal version (ADR 0113). */
    TERMS_REQUIRED("terms_required"),
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
            BipeApiErrorCode.RECOVERY_INVALID -> context.getString(R.string.bipe_error_recovery_invalid)
            BipeApiErrorCode.EMAIL_UNAVAILABLE -> context.getString(R.string.bipe_error_email_unavailable)
            BipeApiErrorCode.TERMS_REQUIRED -> context.getString(R.string.bipe_signup_terms_required)
            BipeApiErrorCode.BAD_REQUEST, BipeApiErrorCode.NOT_FOUND, BipeApiErrorCode.INTERNAL -> context.getString(R.string.bipe_error_generic)
        }
        is Network -> context.getString(R.string.bipe_error_network)
        is UnexpectedResponse -> context.getString(R.string.bipe_error_generic)
    }
}

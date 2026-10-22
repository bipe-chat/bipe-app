// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import chat.bipe.shared.BipeHandleProblem
import chat.bipe.shared.BipeProfileProblem
import chat.bipe.shared.R
import chat.bipe.shared.account.BipePinProblem
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.settings.BipeDataExportFile
import chat.bipe.shared.runCatchingExceptions
import chat.bipe.shared.ensureTrailingSlash
import chat.bipe.shared.BipeStrings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Client for the Bipe API. Contract: bipe-shared/api/openapi.yaml. */
class BipeApiClient internal constructor(private val service: BipeApiService) {
    // Sign-up

    /** `POST /v1/signup`. No auth. */
    suspend fun signup(request: BipeSignupRequest): BipeSignupResponse = call { service.signup(request) }

    /** `GET /v1/signup/config`. No auth. The sign-up mode (ADR 0072). */
    suspend fun signupConfig(): BipeSignupConfig = call { service.signupConfig() }

    /** `GET /v1/banner`. No auth. The remote critical banner (ADR 0117), or null. */
    suspend fun remoteBanner(): BipeRemoteBanner? = call { service.remoteBanner() }.banner

    /** `POST /v1/signup/resume`. No auth. The answer of an earlier sign-up with this key (ADR 0094); `not_found` when none. */
    suspend fun resumeSignup(signupKey: String): BipeSignupResponse = call { service.resumeSignup(BipeSignupResumeRequest(signupKey)) }

    // Device-first accounts (ADR 0057, ADR 0059)

    /** `GET /v1/me/account`. PIN state and the recoveries of the caller's account. */
    suspend fun account(accessToken: String): BipeAccountStatus = call { service.account(bearer(accessToken)) }

    /**
     * `POST /v1/waitlist`. No auth. HTTP 202 for every well-formed request, so it never tells whether the address is known (ADR 0025).
     * [lang] is "pt" or "en", the language of the one e-mail Bipe sends. From the "Não tem convite?" sheet (bipe-chat/bipe#415).
     */
    suspend fun joinWaitlist(email: String, lang: String) {
        checked { service.joinWaitlist(WaitlistRequest(email = email, platform = "android", lang = lang, source = "app")) }
    }

    /** `POST /v1/me/terms`. Records that the caller accepted the legal texts of [termsVersion] (ADR 0113). HTTP 204. */
    suspend fun acceptTerms(termsVersion: String, accessToken: String) {
        checked { service.acceptTerms(bearer(accessToken), TermsRequest(termsVersion)) }
    }

    /** `PUT /v1/me/pin`. Set or change the PIN. The app does the biometric check before it calls this. */
    suspend fun setPin(pin: String, accessToken: String) {
        checked { service.setPin(bearer(accessToken), PinRequest(pin)) }
    }

    /** `POST /v1/me/device-first`. Moves a beta account to a hidden password. Needs a PIN first. */
    suspend fun moveToDeviceFirst(accessToken: String): BipeCredentials = call { service.moveToDeviceFirst(bearer(accessToken)) }

    /** `PUT /v1/me/email`. HTTP 202: the server sends a link to confirm the address; it stays pending until then. */
    suspend fun setRecoveryEmail(email: String, accessToken: String) {
        checked { service.setRecoveryEmail(bearer(accessToken), EmailRequest(email, emailLanguage())) }
    }

    /** `DELETE /v1/me/email`. Removes the verified and the pending address. */
    suspend fun removeRecoveryEmail(accessToken: String) {
        checked { service.removeRecoveryEmail(bearer(accessToken)) }
    }

    /** `POST /v1/me/recoveries/{recoveryId}/cancel`. An old device cancels a waiting recovery. */
    suspend fun cancelRecovery(recoveryId: String, accessToken: String) {
        checked { service.cancelRecovery(bearer(accessToken), recoveryId) }
    }

    /** `POST /v1/recovery/token`. No auth. The synced token first, then the PIN. */
    suspend fun recoverWithToken(recoveryToken: String, pin: String): BipeCredentials =
        call { service.recoverWithToken(TokenRecoveryRequest(recoveryToken, pin)) }

    /** `POST /v1/recovery/email`. No auth. Always 202 for a well-formed request. */
    suspend fun requestEmailRecovery(email: String) {
        checked { service.requestEmailRecovery(EmailRecoveryRequest(email)) }
    }

    /** `POST /v1/recovery/email/confirm`. No auth. Exactly one of [pin] and [newPin]. */
    suspend fun confirmEmailRecovery(linkToken: String, pin: String?, newPin: String?): BipeRecoveryStarted =
        call { service.confirmEmailRecovery(EmailRecoveryConfirmRequest(linkToken, pin, newPin)) }

    /** `POST /v1/recovery/{recoveryId}/claim`. No auth; the claim secret proves the device. */
    suspend fun claimRecovery(recoveryId: String, claimSecret: String): BipeRecoveryClaim =
        call { service.claimRecovery(recoveryId, ClaimRequest(claimSecret)) }

    // Handle changes (ADR 0066)

    /** `POST /v1/handle-changes`. At most 100 IDs. Oldest first. */
    suspend fun handleChanges(userIds: List<String>, since: Instant?, accessToken: String): List<BipeHandleChange> =
        call { service.handleChanges(bearer(accessToken), HandleChangesRequest(userIds, since?.toString())) }.changes

    // Web sign-in (ADR 0118)

    /** `GET /v1/web-link/place`. The approximate place of the browser IP that the MAS consent page shows. */
    suspend fun webLinkPlace(ip: String, accessToken: String): BipeWebLinkPlace = call { service.webLinkPlace(bearer(accessToken), ip) }

    /** `POST /v1/web-link/finish`. Ends the MAS browser sessions that the private web view made since [since]. */
    suspend fun finishWebLink(since: Instant, accessToken: String): Int =
        call { service.finishWebLink(bearer(accessToken), WebLinkFinishRequest(since.toString())) }.finished

    // Invites

    /** `GET /v1/invites/{code}`. No auth. Validity and the inviter's handle. */
    suspend fun invitePreview(code: String): BipeInvitePreview = call { service.invitePreview(code) }

    /** `GET /v1/me/invites`. */
    suspend fun myInvites(accessToken: String): BipeMyInvites = call { service.myInvites(bearer(accessToken)) }

    /** `GET /v1/me/invite-connections`. The caller's invite pairs (ADR 0034): Matrix user IDs. */
    suspend fun inviteConnections(accessToken: String): List<String> = call { service.inviteConnections(bearer(accessToken)) }.userIds

    /** `POST /v1/me/invites`. The code is returned once. */
    suspend fun createInvite(accessToken: String): BipeCreatedInvite = call { service.createInvite(bearer(accessToken)) }

    /** `DELETE /v1/me/invites/{inviteId}`. Revokes a pending invite. */
    suspend fun revokeInvite(id: String, accessToken: String) {
        // Retrofit returns a null body for 204, so this call checks the status only.
        checked { service.revokeInvite(bearer(accessToken), id) }
    }

    // Phone discovery (bipe/docs/phone-link.md)

    /** `POST /v1/contacts/lookup`. At most 500 E.164 numbers per call; returns only the matches. */
    suspend fun lookupContacts(phones: List<String>, accessToken: String): List<BipeContactMatch> =
        call { service.lookupContacts(bearer(accessToken), ContactsLookupRequest(phones)) }.matches

    // Handles

    /** `GET /v1/handles/{handle}/availability`. No auth. */
    suspend fun handleAvailability(handle: String): BipeHandleAvailability = call { service.handleAvailability(handle) }

    /** `GET /v1/handles/{handle}`. Exact lookup. */
    suspend fun handle(handle: String, accessToken: String): BipeHandleRecord = call { service.handle(bearer(accessToken), handle) }

    /** `POST /v1/handles/resolve`. Returns Matrix ID to handle. IDs without a handle are absent. */
    suspend fun resolveHandles(userIds: List<String>, accessToken: String): Map<String, String> =
        call { service.resolveHandles(bearer(accessToken), ResolveHandlesRequest(userIds)) }.handles

    /** `GET /v1/me/handle`. */
    suspend fun myHandle(accessToken: String): BipeMyHandle = call { service.myHandle(bearer(accessToken)) }

    /** `GET /v1/me/business` (ADR 0042). `null` when the account has no CNPJ claim (404). */
    suspend fun myBusiness(accessToken: String): BipeBusinessClaim? = try {
        call { service.myBusiness(bearer(accessToken)) }
    } catch (error: BipeApiException.Server) {
        if (error.code == BipeApiErrorCode.NOT_FOUND) null else throw error
    }

    /** `PUT /v1/me/handle`. */
    suspend fun setMyHandle(handle: String, accessToken: String): BipeMyHandle =
        call { service.setMyHandle(bearer(accessToken), SetHandleRequest(handle)) }

    /** `DELETE /v1/me/handle` (ADR 0066). Allowed at any time; the cooldown stays. */
    suspend fun removeMyHandle(accessToken: String): BipeMyHandle = call { service.removeMyHandle(bearer(accessToken)) }

    // Public profiles (ADR 0041)

    /** `GET /v1/profiles/{handle}`. No auth. HTTP 404 for "no such handle" and "profile off" alike. */
    suspend fun publicProfile(handle: String): BipePublicProfile = call { service.publicProfile(handle) }

    // gov.br verification (bipe#545)

    /** `POST /v1/verifications/govbr`. A challenge PDF bound to this device, valid 30 minutes. */
    suspend fun startGovbrVerification(accessToken: String): BipeGovbrChallenge = call { service.startGovbrVerification(bearer(accessToken)) }

    /** `PUT /v1/verifications/govbr/{id}/document`. The signed PDF. A rejection is a 200 with a reason, not an error. */
    suspend fun sendGovbrDocument(verificationId: String, pdf: ByteArray, accessToken: String): BipeVerificationStatus =
        call { service.sendGovbrDocument(bearer(accessToken), verificationId, pdf.toRequestBody(PDF)) }

    // Badges (bipe-shared/docs/badge-attestation.md)

    /**
     * `GET /v1/badges/{userId}`. No auth. Reads HTTP 200, 404 and 410 itself: the 410 body has no `error` field.
     * The caller must verify the envelope with [chat.bipe.shared.BadgeVerifier]. The status alone proves nothing.
     */
    suspend fun badge(userId: String): BipeBadgeLookup {
        val response = send { service.badge(userId) }
        val body = runCatchingExceptions { (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() } }.getOrNull()
        return when (response.code()) {
            200 -> BipeBadgeLookup.Envelope(body ?: throw BipeApiException.UnexpectedResponse(statusCode = 200))
            404 -> BipeBadgeLookup.None
            410 -> BipeBadgeLookup.Gone(BipeBadgeGoneReason.fromBody(body))
            else -> throw response.error(body)
        }
    }

    /** `GET /v1/accounts/{userId}/type` (ADR 0132). No auth. `null` when the account was not made through Bipe sign-up (404). */
    suspend fun accountType(userId: String): String? = try {
        call { service.accountType(userId) }.accountType
    } catch (error: BipeApiException.Server) {
        if (error.code == BipeApiErrorCode.NOT_FOUND) null else throw error
    }

    /** `GET /v1/me/profile`. */
    suspend fun myProfile(accessToken: String): BipeMyProfile = call { service.myProfile(bearer(accessToken)) }

    // Support DM (ADR 0114)

    /** `POST /v1/me/support`. The caller's room with the support account. The server invites the caller; the app joins. */
    suspend fun supportRoomId(accessToken: String): String = call { service.supportRoom(bearer(accessToken)) }.roomId

    /** `PUT /v1/me/profile`. Error `profile_invalid` carries a [BipeProfileProblem] id as the reason. */
    suspend fun setMyProfile(update: BipeProfileUpdate, accessToken: String): BipeMyProfile =
        call { service.setMyProfile(bearer(accessToken), update) }

    /** `PUT /v1/me/profile/photo`. [jpeg] must already be re-encoded within the limits of registries/profiles.json. */
    suspend fun setMyProfilePhoto(jpeg: ByteArray, accessToken: String): BipeMyProfile =
        call { service.setMyProfilePhoto(bearer(accessToken), jpeg.toRequestBody(JPEG)) }

    /** `DELETE /v1/me/profile/photo`. */
    suspend fun deleteMyProfilePhoto(accessToken: String): BipeMyProfile = call { service.deleteMyProfilePhoto(bearer(accessToken)) }

    // Privacy (ADR 0016, ADR 0112)

    /** `GET /v1/me/messaging`. Who can message the caller. */
    suspend fun messaging(accessToken: String): BipeMessagingSetting = call { service.messaging(bearer(accessToken)) }

    /** `PUT /v1/me/messaging`. */
    suspend fun setMessaging(setting: BipeMessagingSetting, accessToken: String): BipeMessagingSetting =
        call { service.setMessaging(bearer(accessToken), setting) }

    /** `GET /v1/me/discovery`. */
    suspend fun discovery(accessToken: String): BipeDiscoverySettings = call { service.discovery(bearer(accessToken)) }

    /** `PUT /v1/me/discovery`. */
    suspend fun setDiscovery(settings: BipeDiscoverySettings, accessToken: String): BipeDiscoverySettings =
        call { service.setDiscovery(bearer(accessToken), settings) }

    /** `GET /v1/me/phone`. The link status, never the number. */
    suspend fun phone(accessToken: String): BipePhoneStatus = call { service.phone(bearer(accessToken)) }

    /**
     * `GET /v1/me/export` (LGPD art. 18, #364). The body is saved as it is, not parsed. The file name comes from
     * `content-disposition` when it has the contract's form, else the app makes the same name for today.
     */
    suspend fun export(accessToken: String): BipeDataExportFile {
        val response = checked { service.export(bearer(accessToken)) }
        val body = response.body() ?: throw BipeApiException.UnexpectedResponse(statusCode = response.code())
        val bytes = body.use { it.bytes() }
        val fileName = response.headers()["content-disposition"]
            ?.let { EXPORT_FILE_NAME.find(it)?.value }
            ?: "bipe-dados-${LocalDate.now()}.json"
        return BipeDataExportFile(fileName = fileName, bytes = bytes)
    }

    companion object {
        private val JPEG = "image/jpeg".toMediaType()
        private val PDF = "application/pdf".toMediaType()
        private val EXPORT_FILE_NAME = Regex("""bipe-dados-\d{4}-\d{2}-\d{2}\.json""")

        /** The `lang` for emails Bipe sends: English for an English device, else Portuguese (the app's fallback). Same rule as iOS. */
        fun emailLanguage(): String = appLanguage()

        /** The app language for Bipe pages and copy: `en` for an English device, else `pt` (the app's fallback). */
        fun appLanguage(): String = if (Locale.getDefault().language == "en") "en" else "pt"

        /**
         * [baseUrl] is the API root, e.g. `https://api.bipe.net.br`.
         * [callFactory] must not log bodies or headers: requests carry passwords and access tokens.
         */
        fun create(callFactory: Call.Factory, json: Json, baseUrl: String): BipeApiClient {
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl.ensureTrailingSlash())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .callFactory(callFactory)
                .build()
            return BipeApiClient(retrofit.create(BipeApiService::class.java))
        }

        /**
         * Runs [request] with the token from [accessToken]. On HTTP 401 it reads the token again and retries once,
         * because Matrix access tokens are short-lived and the SDK may have refreshed it meanwhile.
         */
        suspend fun <T> withTokenRetry(accessToken: suspend () -> String?, request: suspend (String) -> T): T {
            val token = accessToken() ?: throw BipeApiException.Server(BipeApiErrorCode.UNAUTHORIZED, reason = null, statusCode = 401)
            return try {
                request(token)
            } catch (error: BipeApiException) {
                val freshToken = if (error.isUnauthorized) accessToken() else null
                freshToken ?: throw error
                request(freshToken)
            }
        }

        private val errorJson = Json { ignoreUnknownKeys = true }
    }

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    private suspend fun <T : Any> call(block: suspend () -> Response<T>): T {
        val response = checked(block)
        return response.body() ?: throw BipeApiException.UnexpectedResponse(statusCode = response.code())
    }

    /** Sends the request and maps transport failures and non-2xx answers to [BipeApiException]. */
    private suspend fun <T> checked(block: suspend () -> Response<T>): Response<T> {
        val response = send(block)
        if (!response.isSuccessful) {
            throw response.error(runCatchingExceptions { response.errorBody()?.string() }.getOrNull())
        }
        return response
    }

    /** Sends the request and maps transport failures to [BipeApiException]. Any HTTP status comes back. */
    private suspend fun <T> send(block: suspend () -> Response<T>): Response<T> {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            throw BipeApiException.Network(error)
        } catch (error: Exception) {
            // Encoding and decoding failures: the answer is outside the contract.
            throw BipeApiException.UnexpectedResponse(statusCode = null, cause = error)
        }
    }

    /** The exception for a non-2xx answer with [errorText] as its body. */
    private fun Response<*>.error(errorText: String?): BipeApiException {
        val errorBody = errorText?.let { runCatchingExceptions { errorJson.decodeFromString(BipeErrorBody.serializer(), it) }.getOrNull() }
            ?: return BipeApiException.UnexpectedResponse(statusCode = code())
        return BipeApiException.Server(
            code = BipeApiErrorCode.fromId(errorBody.error) ?: BipeApiErrorCode.INTERNAL,
            reason = errorBody.reason,
            statusCode = code(),
            triesLeft = errorBody.triesLeft,
        )
    }
}

/** `POST /v1/me/terms` body. Internal, not private: [BipeApiService] takes it, and Kotlin does not let an internal interface expose a file-private type. */
@Serializable
internal data class TermsRequest(@SerialName("terms_version") val termsVersion: String)

/** `POST /v1/waitlist` body. Internal for the same reason as [TermsRequest]. */
@Serializable
internal data class WaitlistRequest(
    val email: String,
    val platform: String,
    val lang: String,
    val source: String,
)

/** `POST /v1/web-link/finish` body. Internal for the same reason as [TermsRequest]. */
@Serializable
internal data class WebLinkFinishRequest(val since: String)

@Serializable
internal data class WebLinkFinishResponse(val finished: Int)

/** `POST /v1/me/support` answer. */
@Serializable
internal data class SupportRoomResponse(@SerialName("room_id") val roomId: String)

/** Retrofit encodes each `@Path` value, so a handle cannot add path segments. */
internal interface BipeApiService {
    @POST("v1/me/support")
    suspend fun supportRoom(@Header("Authorization") authorization: String): Response<SupportRoomResponse>

    @POST("v1/signup")
    suspend fun signup(@Body request: BipeSignupRequest): Response<BipeSignupResponse>

    @GET("v1/signup/config")
    suspend fun signupConfig(): Response<BipeSignupConfig>

    @GET("v1/banner")
    suspend fun remoteBanner(): Response<BipeRemoteBannerResponse>

    @POST("v1/signup/resume")
    suspend fun resumeSignup(@Body request: BipeSignupResumeRequest): Response<BipeSignupResponse>

    @GET("v1/me/account")
    suspend fun account(@Header("Authorization") authorization: String): Response<BipeAccountStatus>

    @POST("v1/waitlist")
    suspend fun joinWaitlist(@Body request: WaitlistRequest): Response<Unit>

    @POST("v1/me/terms")
    suspend fun acceptTerms(@Header("Authorization") authorization: String, @Body request: TermsRequest): Response<Unit>

    @PUT("v1/me/pin")
    suspend fun setPin(@Header("Authorization") authorization: String, @Body request: PinRequest): Response<Unit>

    @POST("v1/me/device-first")
    suspend fun moveToDeviceFirst(@Header("Authorization") authorization: String): Response<BipeCredentials>

    @PUT("v1/me/email")
    suspend fun setRecoveryEmail(@Header("Authorization") authorization: String, @Body request: EmailRequest): Response<Unit>

    @DELETE("v1/me/email")
    suspend fun removeRecoveryEmail(@Header("Authorization") authorization: String): Response<Unit>

    @POST("v1/me/recoveries/{recoveryId}/cancel")
    suspend fun cancelRecovery(@Header("Authorization") authorization: String, @Path("recoveryId") recoveryId: String): Response<Unit>

    @POST("v1/recovery/token")
    suspend fun recoverWithToken(@Body request: TokenRecoveryRequest): Response<BipeCredentials>

    @POST("v1/recovery/email")
    suspend fun requestEmailRecovery(@Body request: EmailRecoveryRequest): Response<Unit>

    @POST("v1/recovery/email/confirm")
    suspend fun confirmEmailRecovery(@Body request: EmailRecoveryConfirmRequest): Response<BipeRecoveryStarted>

    @POST("v1/recovery/{recoveryId}/claim")
    suspend fun claimRecovery(@Path("recoveryId") recoveryId: String, @Body request: ClaimRequest): Response<BipeRecoveryClaim>

    @POST("v1/handle-changes")
    suspend fun handleChanges(@Header("Authorization") authorization: String, @Body request: HandleChangesRequest): Response<HandleChangesResponse>

    @GET("v1/web-link/place")
    suspend fun webLinkPlace(@Header("Authorization") authorization: String, @Query("ip") ip: String): Response<BipeWebLinkPlace>

    @POST("v1/web-link/finish")
    suspend fun finishWebLink(@Header("Authorization") authorization: String, @Body request: WebLinkFinishRequest): Response<WebLinkFinishResponse>

    @GET("v1/invites/{code}")
    suspend fun invitePreview(@Path("code") code: String): Response<BipeInvitePreview>

    @GET("v1/me/invites")
    suspend fun myInvites(@Header("Authorization") authorization: String): Response<BipeMyInvites>

    @GET("v1/me/invite-connections")
    suspend fun inviteConnections(@Header("Authorization") authorization: String): Response<BipeInviteConnections>

    @POST("v1/me/invites")
    suspend fun createInvite(@Header("Authorization") authorization: String): Response<BipeCreatedInvite>

    @DELETE("v1/me/invites/{inviteId}")
    suspend fun revokeInvite(@Header("Authorization") authorization: String, @Path("inviteId") inviteId: String): Response<Unit>

    @POST("v1/contacts/lookup")
    suspend fun lookupContacts(@Header("Authorization") authorization: String, @Body request: ContactsLookupRequest): Response<ContactsLookupResponse>

    @GET("v1/handles/{handle}/availability")
    suspend fun handleAvailability(@Path("handle") handle: String): Response<BipeHandleAvailability>

    @GET("v1/handles/{handle}")
    suspend fun handle(@Header("Authorization") authorization: String, @Path("handle") handle: String): Response<BipeHandleRecord>

    @POST("v1/handles/resolve")
    suspend fun resolveHandles(
        @Header("Authorization") authorization: String,
        @Body request: ResolveHandlesRequest,
    ): Response<ResolveHandlesResponse>

    @GET("v1/me/handle")
    suspend fun myHandle(@Header("Authorization") authorization: String): Response<BipeMyHandle>

    @GET("v1/me/business")
    suspend fun myBusiness(@Header("Authorization") authorization: String): Response<BipeBusinessClaim>

    @PUT("v1/me/handle")
    suspend fun setMyHandle(@Header("Authorization") authorization: String, @Body request: SetHandleRequest): Response<BipeMyHandle>

    @DELETE("v1/me/handle")
    suspend fun removeMyHandle(@Header("Authorization") authorization: String): Response<BipeMyHandle>

    @GET("v1/me/messaging")
    suspend fun messaging(@Header("Authorization") authorization: String): Response<BipeMessagingSetting>

    @PUT("v1/me/messaging")
    suspend fun setMessaging(@Header("Authorization") authorization: String, @Body request: BipeMessagingSetting): Response<BipeMessagingSetting>

    @GET("v1/me/discovery")
    suspend fun discovery(@Header("Authorization") authorization: String): Response<BipeDiscoverySettings>

    @PUT("v1/me/discovery")
    suspend fun setDiscovery(@Header("Authorization") authorization: String, @Body request: BipeDiscoverySettings): Response<BipeDiscoverySettings>

    @GET("v1/me/phone")
    suspend fun phone(@Header("Authorization") authorization: String): Response<BipePhoneStatus>

    @GET("v1/me/export")
    suspend fun export(@Header("Authorization") authorization: String): Response<ResponseBody>

    @GET("v1/badges/{userId}")
    suspend fun badge(@Path("userId") userId: String): Response<ResponseBody>

    @GET("v1/accounts/{userId}/type")
    suspend fun accountType(@Path("userId") userId: String): Response<BipeAccountTypeResponse>

    @GET("v1/profiles/{handle}")
    suspend fun publicProfile(@Path("handle") handle: String): Response<BipePublicProfile>

    @GET("v1/me/profile")
    suspend fun myProfile(@Header("Authorization") authorization: String): Response<BipeMyProfile>

    @PUT("v1/me/profile")
    suspend fun setMyProfile(@Header("Authorization") authorization: String, @Body request: BipeProfileUpdate): Response<BipeMyProfile>

    /** Retrofit sends a [RequestBody] as it is, past the JSON converter. */
    @PUT("v1/me/profile/photo")
    suspend fun setMyProfilePhoto(@Header("Authorization") authorization: String, @Body photo: RequestBody): Response<BipeMyProfile>

    @POST("v1/verifications/govbr")
    suspend fun startGovbrVerification(@Header("Authorization") authorization: String): Response<BipeGovbrChallenge>

    @PUT("v1/verifications/govbr/{verificationId}/document")
    suspend fun sendGovbrDocument(
        @Header("Authorization") authorization: String,
        @Path("verificationId") verificationId: String,
        @Body pdf: RequestBody,
    ): Response<BipeVerificationStatus>

    @DELETE("v1/me/profile/photo")
    suspend fun deleteMyProfilePhoto(@Header("Authorization") authorization: String): Response<BipeMyProfile>
}

/** The contract's `Error.error` enum. */
enum class BipeApiErrorCode(val id: String) {
    BAD_REQUEST("bad_request"),
    INVITE_INVALID("invite_invalid"),
    INVITE_QUOTA_EXHAUSTED("invite_quota_exhausted"),
    EMAIL_TAKEN("email_taken"),
    HANDLE_INVALID("handle_invalid"),
    HANDLE_TAKEN("handle_taken"),
    HANDLE_COOLDOWN("handle_cooldown"),
    PASSWORD_WEAK("password_weak"),
    ATTESTATION_INVALID("attestation_invalid"),
    PROFILE_INVALID("profile_invalid"),
    PHOTO_INVALID("photo_invalid"),
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

    /** HTTP 403, reason `missing` or `outdated`: the sign-up or the account lacks the current legal version (ADR 0113). */
    TERMS_REQUIRED("terms_required"),

    /** HTTP 503: the support account is not set up on the server (ADR 0114). */
    SUPPORT_UNAVAILABLE("support_unavailable");

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
        /** With [BipeApiErrorCode.PIN_WRONG]: wrong PINs left before the PIN blocks. */
        val triesLeft: Int? = null,
    ) : BipeApiException()

    /** The request did not reach the server or got no answer. */
    class Network(cause: Throwable? = null) : BipeApiException(cause)

    /** The server answered with something outside the contract. */
    class UnexpectedResponse(val statusCode: Int?, cause: Throwable? = null) : BipeApiException(cause)

    /** HTTP 401: the access token was rejected. */
    val isUnauthorized: Boolean
        get() = when (this) {
            is Server -> statusCode == 401
            is UnexpectedResponse -> statusCode == 401
            is Network -> false
        }

    /** User-facing message (pt-BR / en). */
    fun localizedMessage(stringProvider: BipeStrings): String = when (this) {
        is Server -> when (code) {
            BipeApiErrorCode.INVITE_INVALID -> stringProvider.getString(R.string.bipe_error_invite_invalid)
            BipeApiErrorCode.INVITE_QUOTA_EXHAUSTED -> stringProvider.getString(R.string.bipe_error_invite_quota)
            BipeApiErrorCode.EMAIL_TAKEN -> stringProvider.getString(R.string.bipe_error_email_taken)
            BipeApiErrorCode.PASSWORD_WEAK -> stringProvider.getString(R.string.bipe_error_password_weak)
            BipeApiErrorCode.HANDLE_TAKEN -> BipeHandleProblem.TAKEN.localizedMessage(stringProvider)
            BipeApiErrorCode.HANDLE_INVALID ->
                (reason?.let(BipeHandleProblem::fromId) ?: BipeHandleProblem.INVALID_CHARACTERS).localizedMessage(stringProvider)
            BipeApiErrorCode.HANDLE_COOLDOWN -> stringProvider.getString(R.string.bipe_error_handle_cooldown)
            BipeApiErrorCode.PROFILE_INVALID ->
                reason?.let(BipeProfileProblem::fromId)?.localizedMessage(stringProvider) ?: stringProvider.getString(R.string.bipe_error_generic)
            BipeApiErrorCode.PHOTO_INVALID -> stringProvider.getString(R.string.bipe_public_profile_photo_invalid)
            BipeApiErrorCode.RATE_LIMITED -> stringProvider.getString(R.string.bipe_error_rate_limited)
            BipeApiErrorCode.UNAUTHORIZED -> stringProvider.getString(R.string.bipe_error_unauthorized)
            BipeApiErrorCode.PIN_WEAK ->
                (reason?.let(BipePinProblem::fromId) ?: BipePinProblem.SEQUENCE).localizedMessage(stringProvider)
            BipeApiErrorCode.PIN_WRONG -> stringProvider.getString(R.string.bipe_pin_wrong, (triesLeft ?: 0).toString())
            BipeApiErrorCode.PIN_BLOCKED ->
                stringProvider.getString(R.string.bipe_pin_blocked_body, BipeRegistries.PIN_MAX_WRONG_TRIES.toString())
            BipeApiErrorCode.RECOVERY_INVALID -> stringProvider.getString(R.string.bipe_error_recovery_invalid)
            BipeApiErrorCode.EMAIL_UNAVAILABLE -> stringProvider.getString(R.string.bipe_error_email_unavailable)
            BipeApiErrorCode.TERMS_REQUIRED -> stringProvider.getString(R.string.bipe_signup_terms_required)
            // App Attest is iOS only. Android sign-up gets Play Integrity later (contract: Attestation).
            BipeApiErrorCode.SUPPORT_UNAVAILABLE -> stringProvider.getString(R.string.bipe_support_open_failed)
            BipeApiErrorCode.ATTESTATION_INVALID, BipeApiErrorCode.PIN_NOT_SET,
            BipeApiErrorCode.BAD_REQUEST, BipeApiErrorCode.NOT_FOUND, BipeApiErrorCode.INTERNAL ->
                stringProvider.getString(R.string.bipe_error_generic)
        }
        is Network -> stringProvider.getString(R.string.bipe_error_network)
        is UnexpectedResponse -> stringProvider.getString(R.string.bipe_error_generic)
    }
}

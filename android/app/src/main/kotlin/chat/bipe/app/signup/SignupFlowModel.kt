// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.SessionStore
import chat.bipe.app.account.SignupOutcome
import chat.bipe.shared.BipeInviteLink
import chat.bipe.shared.BipeSignupRules
import chat.bipe.shared.BipeStrings
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.account.BipePin
import chat.bipe.shared.account.BipePinProblem
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeCredentials
import chat.bipe.shared.api.BipeSignupConfig
import chat.bipe.shared.api.BipeSignupRequest
import chat.bipe.shared.api.BipeSignupResponse
import chat.bipe.shared.generated.BipeOnboardingStart
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Type PendingInvite of spec onboarding-welcome. */
data class PendingInvite(val code: String, val inviterHandle: String?, val isValid: Boolean) {
    override fun toString(): String = "PendingInvite(valid=$isValid)"
}

/**
 * The sign-up flow: onboarding-welcome, signup-invite-code, signup-waitlist, signup-name, signup-pin, signup-fallback and
 * signup-create (bipe-shared product/screens). State and action names follow the specs, as on iOS.
 */
class SignupFlowModel(
    private val context: Context,
    private val api: BipeApiClient,
    private val vault: BipeAccountVault,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope,
    private val onSignedUp: (AccountSession, SignupOutcome) -> Unit,
) {
    /** The steps on top of onboarding-welcome. System back pops one step (action back). */
    enum class Route { INVITE_CODE, NAME, PIN, PIN_CONFIRM, FALLBACK, CREATE }

    private val _path = MutableStateFlow<List<Route>>(emptyList())
    val path: StateFlow<List<Route>> = _path.asStateFlow()

    private fun go(route: Route) {
        val current = _path.value
        val index = current.indexOf(route)
        _path.value = if (index >= 0) current.subList(0, index + 1) else current + route
    }

    /** System back. `false` on onboarding-welcome, where the app closes. */
    fun back(): Boolean {
        val current = _path.value
        if (current.isEmpty() || current.last() == Route.CREATE) return current.isNotEmpty()
        _path.value = current.dropLast(1)
        // Action back of signup-pin: leaving confirm goes back to create and forgets firstPin.
        if (current.last() == Route.PIN_CONFIRM) resetPin()
        return true
    }

    // onboarding-welcome

    enum class Variant { LOADING, INVITED, INVITE_ONLY, OPEN }
    enum class PasteResult { NONE, NOT_AN_INVITE }

    val variant = MutableStateFlow(Variant.LOADING)
    val invite = MutableStateFlow<PendingInvite?>(null)
    private var inviteRequired = true
    private var termsVersion: String? = null
    val clipboardHasText = MutableStateFlow(false)
    val pasteResult = MutableStateFlow(PasteResult.NONE)

    // signup-invite-code

    enum class InviteCodePhase { EDITING, CHECKING, INVALID }

    val code = MutableStateFlow("")
    val inviteCodePhase = MutableStateFlow(InviteCodePhase.EDITING)
    val inviteCodeError = MutableStateFlow<String?>(null)

    // signup-waitlist

    enum class WaitlistPhase { EDITING, SENDING, DONE, FAILED }

    val showsWaitlist = MutableStateFlow(false)
    val waitlistEmail = MutableStateFlow("")
    val waitlistPhase = MutableStateFlow(WaitlistPhase.EDITING)
    val waitlistEmailInvalid = MutableStateFlow(false)
    val waitlistError = MutableStateFlow<String?>(null)

    // signup-name

    val displayName = MutableStateFlow("")
    val termsAccepted = MutableStateFlow(false)
    val showTermsRequired = MutableStateFlow(false)

    // signup-pin

    enum class PinStep { CREATE, CONFIRM }
    enum class PinProblem { NONE, REPEATED_DIGIT, SEQUENCE, DATE, MISMATCH }

    val pinStep = MutableStateFlow(PinStep.CREATE)
    val pin = MutableStateFlow("")
    private var firstPin = ""
    val pinProblem = MutableStateFlow(PinProblem.NONE)

    // signup-fallback

    enum class FallbackPhase { CHOOSE, EMAIL }

    val fallbackPhase = MutableStateFlow(FallbackPhase.CHOOSE)
    val fallbackEmail = MutableStateFlow("")
    val fallbackEmailInvalid = MutableStateFlow(false)

    // signup-create

    enum class CreatePhase { ATTESTING, CREATING, SIGNING_IN, FAILED, DONE }

    sealed interface CreateError {
        data object None : CreateError
        data object Network : CreateError
        data object RateLimited : CreateError
        data object InviteInvalid : CreateError
        data object TermsRequired : CreateError
        data class PinWeak(val problem: BipePinProblem) : CreateError
        data object EmailTaken : CreateError
        data object AttestationInvalid : CreateError
        data object SignInFailed : CreateError
        data object Generic : CreateError
    }

    val createPhase = MutableStateFlow(CreatePhase.ATTESTING)
    val createError = MutableStateFlow<CreateError>(CreateError.None)

    /** The form of signup-create (type SignupForm). The PIN stays in memory only. */
    private var formPin = ""
    private var formEmail: String? = null
    /** Credentials stored but not signed in yet: a retry signs in again without a new request. */
    private var storedCredentials: BipeCredentials? = null
    private var inviterUserId: String? = null

    // onboarding-welcome actions

    /** Action appear: reads the sign-up mode (at most 3 s, then inviteOnly). */
    fun appear() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboardHasText.value = clipboard?.hasPrimaryClip() == true
        scope.launch {
            val config: BipeSignupConfig? = withTimeoutOrNull(3_000) { runCatching { api.signupConfig() }.getOrNull() }
            inviteRequired = config?.inviteRequired ?: true
            termsVersion = config?.termsVersion
            updateVariant()
        }
    }

    /** Action openInviteLink: a bipe.chat/convite/<code> link the app was opened with. */
    fun openInviteLink(url: Uri) {
        val found = BipeInviteLink.code(url.toString()) ?: return
        scope.launch { preview(found) }
    }

    /** Action pasteInvite: reads the clipboard once, after a tap. */
    fun pasteInvite() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        val found = text?.let(BipeInviteLink::normalizedCode)
        if (found == null) {
            pasteResult.value = PasteResult.NOT_AN_INVITE
            return
        }
        pasteResult.value = PasteResult.NONE
        scope.launch { preview(found) }
    }

    /** Action primary: invited and open go to signup-name, inviteOnly to signup-invite-code. */
    fun primary() {
        when (variant.value) {
            Variant.INVITED, Variant.OPEN -> go(Route.NAME)
            Variant.INVITE_ONLY -> go(Route.INVITE_CODE)
            Variant.LOADING -> Unit
        }
    }

    val startVariant: BipeOnboardingStart
        get() {
            val pending = invite.value
            return BipeOnboardingStart.variant(invitePending = pending?.isValid == true && pending.inviterHandle != null, inviteRequired = inviteRequired)
        }

    private suspend fun preview(found: String) {
        val preview = runCatching { api.invitePreview(found) }.getOrNull()
        invite.value = PendingInvite(found, preview?.inviterHandle, preview?.valid ?: false)
        if (preview?.valid == true) code.value = found
        updateVariant()
    }

    private fun updateVariant() {
        variant.value = when (startVariant) {
            BipeOnboardingStart.INVITED -> Variant.INVITED
            BipeOnboardingStart.INVITE_ONLY -> Variant.INVITE_ONLY
            BipeOnboardingStart.OPEN -> Variant.OPEN
        }
    }

    // signup-invite-code actions

    /** Action editCode. */
    fun editCode(text: String) {
        code.value = text
        inviteCodeError.value = null
        inviteCodePhase.value = InviteCodePhase.EDITING
    }

    /** Action submit: takes the code out of the text and asks the server. */
    fun submitCode() {
        if (code.value.isBlank()) return
        val normalized = BipeInviteLink.normalizedCode(code.value)
        if (normalized == null) {
            inviteCodePhase.value = InviteCodePhase.INVALID
            inviteCodeError.value = context.getString(R.string.bipe_error_invite_invalid)
            return
        }
        inviteCodePhase.value = InviteCodePhase.CHECKING
        scope.launch {
            try {
                val preview = api.invitePreview(normalized)
                if (preview.valid) {
                    invite.value = PendingInvite(normalized, preview.inviterHandle, true)
                    inviteCodePhase.value = InviteCodePhase.EDITING
                    go(Route.NAME)
                } else {
                    inviteCodePhase.value = InviteCodePhase.INVALID
                    inviteCodeError.value = context.getString(R.string.bipe_error_invite_invalid)
                }
            } catch (error: BipeApiException) {
                inviteCodePhase.value = InviteCodePhase.EDITING
                inviteCodeError.value = context.getString(if (error.isRateLimited) R.string.bipe_error_rate_limited else R.string.bipe_error_network)
            }
        }
    }

    /** Action noInvite: opens signup-waitlist as a sheet. */
    fun noInvite() {
        showsWaitlist.value = true
    }

    // signup-waitlist actions

    /** Action editEmail of signup-waitlist. */
    fun editWaitlistEmail(text: String) {
        waitlistEmail.value = text
        waitlistEmailInvalid.value = false
    }

    /** Action join. */
    fun joinWaitlist() {
        val email = waitlistEmail.value.trim()
        if (!BipeSignupRules.isPlausibleEmail(email)) {
            waitlistEmailInvalid.value = true
            return
        }
        waitlistPhase.value = WaitlistPhase.SENDING
        waitlistError.value = null
        scope.launch {
            try {
                api.joinWaitlist(email, BipeApiClient.emailLanguage())
                waitlistPhase.value = WaitlistPhase.DONE
            } catch (error: BipeApiException) {
                waitlistPhase.value = WaitlistPhase.FAILED
                waitlistError.value = context.getString(if (error.isRateLimited) R.string.bipe_error_rate_limited else R.string.bipe_error_network)
            }
        }
    }

    /** Action close: back to signup-invite-code. */
    fun closeWaitlist() {
        showsWaitlist.value = false
    }

    // signup-name actions

    /** Action editName. */
    fun editName(text: String) {
        displayName.value = text.take(BipeSignupRules.DISPLAY_NAME_MAX_LENGTH)
    }

    /** Action toggleTerms. */
    fun toggleTerms() {
        termsAccepted.value = !termsAccepted.value
        if (termsAccepted.value) showTermsRequired.value = false
    }

    /** The legal version the checkbox accepts. */
    private val acceptedTermsVersion: String get() = termsVersion ?: BipeRegistries.LEGAL_VERSION

    fun canContinueName(name: String, accepted: Boolean): Boolean = name.isNotBlank() && accepted

    /** Action continue of signup-name. */
    fun continueFromName() {
        if (!canContinueName(displayName.value, termsAccepted.value)) return
        resetPin()
        go(Route.PIN)
    }

    // signup-pin actions

    /** Actions typeDigit and deleteDigit: the text of the digit field, digits only (paste is allowed). */
    fun editPin(text: String) {
        val digits = BipePin.sanitizeInput(text)
        if (digits.length != pin.value.length) pinProblem.value = PinProblem.NONE
        pin.value = digits
        if (digits.length != BipeRegistries.PIN_LENGTH) return
        when (pinStep.value) {
            PinStep.CREATE -> {
                val problem = BipePin.problem(digits)
                if (problem != null) {
                    pinProblem.value = pinProblemOf(problem)
                    pin.value = ""
                } else {
                    firstPin = digits
                    pin.value = ""
                    pinStep.value = PinStep.CONFIRM
                    go(Route.PIN_CONFIRM)
                }
            }
            PinStep.CONFIRM -> if (digits == firstPin) {
                formPin = digits
                pin.value = ""
                go(Route.FALLBACK)
            } else {
                resetPin()
                pinProblem.value = PinProblem.MISMATCH
                go(Route.PIN)
            }
        }
    }

    private fun resetPin() {
        pinStep.value = PinStep.CREATE
        pin.value = ""
        firstPin = ""
        pinProblem.value = PinProblem.NONE
    }

    private fun pinProblemOf(problem: BipePinProblem): PinProblem = when (problem) {
        BipePinProblem.REPEATED_DIGIT -> PinProblem.REPEATED_DIGIT
        BipePinProblem.DATE -> PinProblem.DATE
        BipePinProblem.SEQUENCE, BipePinProblem.NOT_DIGITS, BipePinProblem.WRONG_LENGTH -> PinProblem.SEQUENCE
    }

    // signup-fallback actions

    /** Action addEmail. */
    fun addEmail() {
        fallbackPhase.value = FallbackPhase.EMAIL
    }

    /** Action editEmail of signup-fallback. */
    fun editFallbackEmail(text: String) {
        fallbackEmail.value = text
        fallbackEmailInvalid.value = false
    }

    /** Action submitEmail. */
    fun submitEmail() {
        val email = fallbackEmail.value.trim()
        if (!BipeSignupRules.isPlausibleEmail(email)) {
            fallbackEmailInvalid.value = true
            return
        }
        formEmail = email
        create()
    }

    /** Action skip. */
    fun skipEmail() {
        formEmail = null
        create()
    }

    // signup-create actions

    /**
     * Action start of signup-create: retry key, device check, request, storage, sign-in (spec notes). Android has no
     * device check yet (Play Integrity, parity attestation): it sends none and the server decides.
     */
    fun create() {
        go(Route.CREATE)
        createError.value = CreateError.None
        scope.launch {
            storedCredentials?.let { return@launch signIn(it) }
            val pending = vault.pendingSignup(formPin)
            val accountPin = pending.pin ?: formPin
            createPhase.value = CreatePhase.CREATING
            val pendingInvite = invite.value
            val request = BipeSignupRequest(
                inviteCode = pendingInvite?.takeIf { it.isValid }?.code,
                email = formEmail,
                pin = accountPin,
                lang = BipeApiClient.emailLanguage(),
                displayName = displayName.value.trim(),
                signupKey = pending.key,
                termsVersion = if (termsAccepted.value) acceptedTermsVersion else null,
            )
            val response = try {
                signupRetryingLostAnswers(request)
            } catch (error: BipeApiException) {
                if (error is BipeApiException.Server && error.code != BipeApiErrorCode.RATE_LIMITED && error.code != BipeApiErrorCode.INTERNAL) {
                    // A field error: the server made nothing, so the next try takes the PIN from the form again.
                    vault.update { it.copy(pendingSignup = it.pendingSignup?.copy(pin = null)) }
                }
                fail(error)
                return@launch
            }
            val credentials = response.signedInCredentials ?: return@launch fail(BipeApiException.UnexpectedResponse(201))
            vault.store(credentials.userId, credentials.password, credentials.recoveryToken)
            vault.update { it.copy(pendingSignup = null) }
            storedCredentials = credentials
            inviterUserId = response.inviterUserId
            signIn(credentials)
        }
    }

    /** Action retry of signup-create. */
    fun retryCreate() = create()

    val canFix: Boolean
        get() = when (createError.value) {
            CreateError.InviteInvalid, CreateError.TermsRequired, is CreateError.PinWeak, CreateError.EmailTaken -> true
            else -> false
        }

    /** Action fix of signup-create: back to the step that owns the field. */
    fun fix() {
        when (createError.value) {
            CreateError.InviteInvalid -> {
                invite.value = null
                _path.value = listOf(Route.INVITE_CODE)
            }
            CreateError.TermsRequired -> {
                termsAccepted.value = false
                showTermsRequired.value = true
                _path.value = _path.value.takeWhile { it != Route.NAME } + Route.NAME
            }
            is CreateError.PinWeak -> {
                resetPin()
                _path.value = _path.value.takeWhile { it != Route.PIN } + Route.PIN
            }
            CreateError.EmailTaken -> {
                fallbackPhase.value = FallbackPhase.EMAIL
                _path.value = _path.value - Route.CREATE
            }
            else -> Unit
        }
    }

    /** The text of the error (spec signup-create notes: error map). */
    fun createErrorText(): String = when (val error = createError.value) {
        CreateError.None -> ""
        CreateError.Network -> context.getString(R.string.bipe_error_network)
        CreateError.RateLimited -> context.getString(R.string.bipe_error_rate_limited)
        CreateError.InviteInvalid -> context.getString(R.string.bipe_error_invite_invalid)
        CreateError.TermsRequired -> context.getString(R.string.bipe_signup_terms_required)
        is CreateError.PinWeak -> error.problem.localizedMessage(BipeStrings.from(context))
        CreateError.EmailTaken -> context.getString(R.string.bipe_error_email_taken)
        CreateError.AttestationInvalid -> context.getString(R.string.bipe_error_attestation_invalid)
        CreateError.SignInFailed -> context.getString(R.string.bipe_signup_signin_failed)
        CreateError.Generic -> context.getString(R.string.bipe_error_generic)
    }

    private suspend fun signIn(credentials: BipeCredentials) {
        createPhase.value = CreatePhase.SIGNING_IN
        try {
            val session = AccountSession.signIn(context, credentials.userId, credentials.password, sessionStore)
            createPhase.value = CreatePhase.DONE
            formPin = ""
            onSignedUp(session, SignupOutcome(inviterUserId, invite.value?.inviterHandle))
        } catch (error: Exception) {
            createPhase.value = CreatePhase.FAILED
            createError.value = CreateError.SignInFailed
        }
    }

    /**
     * Mobile data drops often. A try without an answer may still have made the account, and the same key returns it, so
     * a few quiet retries are safe (ADR 0094). 429 signup_in_progress waits and sends again.
     */
    private suspend fun signupRetryingLostAnswers(request: BipeSignupRequest): BipeSignupResponse {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return api.signup(request)
            } catch (error: BipeApiException) {
                val retryable = when (error) {
                    is BipeApiException.Network, is BipeApiException.UnexpectedResponse -> true
                    is BipeApiException.Server ->
                        (error.code == BipeApiErrorCode.RATE_LIMITED && error.reason == "signup_in_progress") || error.code == BipeApiErrorCode.INTERNAL
                }
                if (!retryable || attempt >= 4) throw error
                delay(2_000L * attempt)
            }
        }
    }

    private fun fail(error: BipeApiException) {
        createPhase.value = CreatePhase.FAILED
        val server = error as? BipeApiException.Server
        createError.value = when {
            error is BipeApiException.Network -> CreateError.Network
            server == null -> CreateError.Generic
            server.code == BipeApiErrorCode.RATE_LIMITED -> CreateError.RateLimited
            server.code == BipeApiErrorCode.INVITE_INVALID -> CreateError.InviteInvalid
            server.code == BipeApiErrorCode.TERMS_REQUIRED -> CreateError.TermsRequired
            server.code == BipeApiErrorCode.PIN_WEAK -> CreateError.PinWeak(server.reason?.let(BipePinProblem::fromId) ?: BipePinProblem.SEQUENCE)
            server.code == BipeApiErrorCode.EMAIL_TAKEN -> CreateError.EmailTaken
            server.code == BipeApiErrorCode.ATTESTATION_INVALID -> CreateError.AttestationInvalid
            else -> CreateError.Generic
        }
    }

    private val BipeApiException.isRateLimited: Boolean
        get() = this is BipeApiException.Server && code == BipeApiErrorCode.RATE_LIMITED
}

/** The credentials of a completed sign-up: the API sends the password and the recovery token once. */
internal val BipeSignupResponse.signedInCredentials: BipeCredentials?
    get() {
        val password = password ?: return null
        val recoveryToken = recoveryToken ?: return null
        return BipeCredentials(userId, password, recoveryToken)
    }
